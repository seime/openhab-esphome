/**
 * Copyright (c) 2023 Contributors to the Seime Openhab Addons project
 * <p>
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 * <p>
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * <p>
 * SPDX-License-Identifier: EPL-2.0
 */
package no.seime.openhab.binding.esphome.internal.comm;

import static no.seime.openhab.binding.esphome.internal.comm.ConnectionSelector.READ_BUFFER_SIZE;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.BadPaddingException;
import javax.crypto.ShortBufferException;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.GeneratedMessage;
import com.jano7.executor.KeyRunnable;
import com.jano7.executor.KeySequentialExecutor;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.HandshakeState;

import io.esphome.api.ApiOptions;
import no.seime.openhab.binding.esphome.internal.CommunicationListener;

public class EncryptedFrameHelper {
    public static final int PROTOCOL_PLAINTEXT = 0x00;
    public static final int PROTOCOL_ENCRYPTED = 0x01;
    private final static String NOISE_PROTOCOL = "Noise_NNpsk0_25519_ChaChaPoly_SHA256";
    protected final Logger logger = LoggerFactory.getLogger(EncryptedFrameHelper.class);
    private final String encryptionKeyBase64;
    private final String expectedServername;
    private final KeySequentialExecutor scheduler;
    private final MessageTypeToClassConverter messageTypeToClassConverter = new MessageTypeToClassConverter();
    protected CommunicationListener listener;
    protected ByteBuffer internalBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE * 2);
    protected ESPHomeConnection connection;
    protected String logPrefix;
    private HandshakeState client;
    private CipherStatePair cipherStatePair;
    private NoiseProtocolState state;
    private final String connectionId = UUID.randomUUID().toString();

    public EncryptedFrameHelper(ConnectionSelector connectionSelector, CommunicationListener listener,
            String encryptionKeyBase64, @Nullable String expectedServername, String logPrefix,
            KeySequentialExecutor packetProcessor) {
        this.logPrefix = logPrefix;
        this.listener = listener;
        this.encryptionKeyBase64 = encryptionKeyBase64;
        this.expectedServername = expectedServername;
        this.scheduler = packetProcessor;

        connection = new ESPHomeConnection(connectionSelector, this, logPrefix);
    }

    public void connect(InetSocketAddress espHomeAddress) throws ProtocolException {
        try {
            client = new HandshakeState(NOISE_PROTOCOL, HandshakeState.INITIATOR);

            // Set preshared key
            byte[] key = Base64.getDecoder().decode(encryptionKeyBase64);
            assert key.length == 32;
            client.setPreSharedKey(key, 0, key.length);

            // Set prologue
            byte[] prologue = "NoiseAPIInit".getBytes(StandardCharsets.US_ASCII);
            byte[] prologuePadded = new byte[prologue.length + 2]; // 2 nulls at the end
            System.arraycopy(prologue, 0, prologuePadded, 0, prologue.length);
            client.setPrologue(prologuePadded, 0, prologuePadded.length);

            client.start();

            connection.connect(espHomeAddress);
            state = NoiseProtocolState.HELLO;

            connection.send(createFrame(new byte[0]));

        } catch (NoSuchAlgorithmException e) {
            throw new ProtocolAPIError("Error initializing encryption", e);
        }
    }

    protected void headerReceived(byte[] headerData) throws ProtocolException {
        try {
            if (headerData[0] != PROTOCOL_ENCRYPTED) {
                if (headerData[0] == PROTOCOL_PLAINTEXT) {
                    listener.onParseError(CommunicationError.DEVICE_REQUIRES_PLAINTEXT);
                    return;
                } else {
                    listener.onParseError(CommunicationError.INVALID_PROTOCOL_PREAMBLE);
                    return;
                }
            }

            // Unwrap outer frame
            int protoPacketLength = bytesToShort(Arrays.copyOfRange(headerData, 1, 3));

            // Check if we have a complete packet
            if (internalBuffer.remaining() >= protoPacketLength) {
                byte[] packetData = readBytes(protoPacketLength);
                switch (state) {
                    case HELLO:
                        handleHello(packetData);
                        break;
                    case HANDSHAKE:
                        handleHandshake(packetData);
                        break;
                    case READY:
                        handleReady(packetData);
                        break;
                }

                // Prepare buffer for next packet processing
                internalBuffer.compact();

                // Continue processing in case we have more complete packets
                processBuffer();
            } else {
                // Compact the buffer to make room for more data while preserving existing data
                internalBuffer.compact();
            }
        } catch (ShortBufferException e) {
            throw new ProtocolAPIError(e.getMessage(), e);
        }
    }

    private void handleHello(byte[] packetData) throws ProtocolAPIError, ShortBufferException {
        if (packetData[0] != PROTOCOL_ENCRYPTED) {
            listener.onParseError(CommunicationError.DEVICE_REQUIRES_PLAINTEXT);
        } else {
            // Verify server name

            int nullByteIndex = 1;
            while (nullByteIndex < packetData.length && packetData[nullByteIndex] != 0) {
                nullByteIndex++;
            }

            byte[] serverNameBytes = Arrays.copyOfRange(packetData, 1, nullByteIndex);
            String serverName = new String(serverNameBytes, StandardCharsets.US_ASCII);

            if (expectedServername != null && !(expectedServername.equals(serverName))) {
                logger.warn("[{}] Expected server name '{}' but got '{}'", logPrefix, expectedServername, serverName);
                listener.onParseError(CommunicationError.DEVICE_NAME_MISMATCH);
                return;
            }

            final byte[] noiseHandshakeBuffer = new byte[64];
            final int noiseHandshakeLength;

            // Client handshake written to buffer
            noiseHandshakeLength = client.writeMessage(noiseHandshakeBuffer, 0, new byte[0], 0, 0);

            // Prepend with empty byte in array
            byte[] payload = new byte[noiseHandshakeLength + 1];
            System.arraycopy(noiseHandshakeBuffer, 0, payload, 1, noiseHandshakeLength);

            ByteBuffer frame = createFrame(payload);
            state = NoiseProtocolState.HANDSHAKE;
            connection.send(frame);
        }
    }

    private ByteBuffer createFrame(byte[] payload) {
        int frameLength = payload.length;
        ByteBuffer buffer = ByteBuffer.allocate(frameLength + 3);
        buffer.put((byte) 1);
        buffer.putShort((short) frameLength);
        buffer.put(payload);
        buffer.flip();

        return buffer;
    }

    private void handleHandshake(byte[] packetData) throws ProtocolException {
        if (packetData[0] != 0) {
            byte[] explanation = Arrays.copyOfRange(packetData, 1, packetData.length);
            listener.onParseError(CommunicationError.ENCRYPTION_KEY_INVALID);
        } else {
            try {
                byte[] handshakeRsp = Arrays.copyOfRange(packetData, 1, packetData.length);
                byte[] payload = new byte[64];
                client.readMessage(handshakeRsp, 0, handshakeRsp.length, payload, 0);

                cipherStatePair = client.split();
                state = NoiseProtocolState.READY;
                listener.onConnect();
            } catch (ShortBufferException | BadPaddingException e) {
                throw new ProtocolAPIError(e.getMessage());
            }
        }
    }

    private void handleReady(final byte[] packetData) {
        // Pass on to packet processor
        scheduler.execute(new KeyRunnable<>(connectionId, () -> {
            try {
                byte[] decrypted = decryptPacket(packetData);
                int messageType = (decrypted[0] << 8) | decrypted[1];
                byte[] messageData = Arrays.copyOfRange(decrypted, 4, decrypted.length);
                decodeProtoMessage(messageType, messageData);
            } catch (Exception e) {
                listener.onParseError(CommunicationError.PACKET_ERROR);
            }
        }));
    }

    public ByteBuffer encodeFrame(GeneratedMessage message) throws ProtocolAPIError {
        try {
            byte[] protoBytes = message.toByteArray();
            int type = message.getDescriptorForType().getOptions().getExtension(ApiOptions.id);
            byte[] typeAndLength = new byte[] { (byte) (type >> 8 & 0xFF), (byte) (type & 0xFF),
                    (byte) (protoBytes.length >> 8 & 0xFF), (byte) (protoBytes.length & 0xFF) };
            byte[] frameUnencrypted = concatArrays(typeAndLength, protoBytes);
            byte[] frameEncrypted = encryptPacket(frameUnencrypted);

            return createFrame(frameEncrypted);

        } catch (Exception e) {
            throw new ProtocolAPIError(e.getMessage());
        }
    }

    private byte[] encryptPacket(byte[] msg) throws Exception {
        byte[] encrypted = new byte[msg.length + 128];
        final int cipherTextLength = cipherStatePair.getSender().encryptWithAd(null, msg, 0, encrypted, 0, msg.length);

        byte[] result = new byte[cipherTextLength];
        System.arraycopy(encrypted, 0, result, 0, cipherTextLength);
        return result;
    }

    private byte[] decryptPacket(byte[] msg) throws Exception {
        byte[] decrypted = new byte[msg.length + 128];
        final int cipherTextLength = cipherStatePair.getReceiver().decryptWithAd(null, msg, 0, decrypted, 0,
                msg.length);

        byte[] result = new byte[cipherTextLength];
        System.arraycopy(decrypted, 0, result, 0, cipherTextLength);
        return result;
    }

    public void setPacketListener(CommunicationListener listener) {
        this.listener = listener;
    }

    public void close() {
        connection.close();
    }

    protected void processBuffer() throws ProtocolException {
        internalBuffer.flip(); // Prepare for reading

        // First check if we have at least a complete header (3 bytes)
        if (internalBuffer.remaining() >= 3) {
            byte[] header = new byte[3];
            internalBuffer.get(header);

            // Process the complete header
            headerReceived(header);
        } else if (internalBuffer.remaining() > 0) {
            // We have some data, but not a complete header yet
            // Process partial header if possible, otherwise keep data in buffer
            processPartialHeader();
        } else {
            // No data at all, just prepare the buffer for writing
            internalBuffer.clear();
        }
    }

    /**
     * Handles cases where a partial header might be present in the buffer.
     */
    protected void processPartialHeader() {
        // The buffer should be in reading mode (flipped) before calling this
        if (internalBuffer.remaining() >= 3) {
            // We have enough data for at least a header
            byte[] header = new byte[3];

            // Save the current position to restore it if we don't have a complete packet
            int initialPosition = internalBuffer.position();

            // Read but don't consume from the buffer
            internalBuffer.mark();
            internalBuffer.get(header, 0, 3);

            // Check protocol type
            if (header[0] != PROTOCOL_ENCRYPTED && header[0] != PROTOCOL_PLAINTEXT) {
                // Invalid protocol - reset and wait for more data
                internalBuffer.reset();
                return;
            }

            // Get the packet length
            int packetLength = bytesToShort(Arrays.copyOfRange(header, 1, 3));

            // Check if we have the full packet
            if (internalBuffer.remaining() + 3 >= packetLength + 3) {
                // Reset to the initial position so headerReceived can process it properly
                internalBuffer.position(initialPosition);

                // Now process the complete header and packet
                try {
                    headerReceived(header);
                } catch (ProtocolException e) {
                    logger.error("[{}] Error processing header: {}", logPrefix, e.getMessage());
                }
            } else {
                // Not enough data, reset and wait for more
                internalBuffer.reset();
            }
        }
    }

    protected byte[] readBytes(int numBytes) {
        if (internalBuffer.remaining() < numBytes) {
            return new byte[0];
        }
        byte[] data = new byte[numBytes];
        internalBuffer.get(data);
        return data;
    }

    protected void decodeProtoMessage(int messageType, byte[] bytes) {
        logger.trace("[{}] Received packet of type {} with data {}", logPrefix, messageType, bytes);

        try {
            Method parseMethod = messageTypeToClassConverter.getMethod(messageType);
            if (parseMethod != null) {
                GeneratedMessage invoke = (GeneratedMessage) parseMethod.invoke(null, bytes);
                if (invoke != null) {
                    listener.onPacket(invoke);
                } else {
                    logger.warn("[{}] Received null packet of type {}", logPrefix, parseMethod);
                }
            }
        } catch (Exception e) {
            logger.warn("[{}] Error parsing packet", logPrefix, e);
            listener.onParseError(CommunicationError.PACKET_ERROR);
        }
    }

    public void processReceivedData(ByteBuffer newDataBuffer) throws ProtocolException, IOException {
        // Copy new data into buffer
        newDataBuffer.flip();
        internalBuffer.put(newDataBuffer);
        processBuffer();
    }

    public void endOfStream(String message) {
        listener.onEndOfStream(message);
    }

    public void onParseError(CommunicationError error) {
        listener.onParseError(error);
    }

    public void send(GeneratedMessage message) throws ProtocolAPIError {
        if (logger.isDebugEnabled()) {
            // ToString method costs a bit
            logger.debug("[{}] Sending message type {} with content '{}'", logPrefix,
                    message.getClass().getSimpleName(), StringUtils.trimToEmpty(message.toString()));
        }
        try {
            if (connection != null) {
                connection.send(encodeFrame(message));
            } else {
                logger.debug("Connection is null, cannot send message");
            }
        } catch (ProtocolAPIError e) {
            logger.warn("Error sending message", e);
        }
    }

    private enum NoiseProtocolState {
        HELLO,
        HANDSHAKE,
        READY
    }

    private static short bytesToShort(final byte[] data) {
        short value = (short) (data[0] & 0xff);
        value <<= 8;
        value |= data[1] & 0xff;
        return value;
    }

    private static byte[] concatArrays(byte[] length, byte[] additionalLength) {
        byte[] result = new byte[length.length + additionalLength.length];
        System.arraycopy(length, 0, result, 0, length.length);
        System.arraycopy(additionalLength, 0, result, length.length, additionalLength.length);
        return result;
    }
}
