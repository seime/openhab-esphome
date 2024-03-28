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

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.BadPaddingException;
import javax.crypto.ShortBufferException;

import org.eclipse.jdt.annotation.Nullable;

import com.google.protobuf.GeneratedMessageV3;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.HandshakeState;

import io.esphome.api.ApiOptions;
import no.seime.openhab.binding.esphome.internal.CommunicationListener;

public class EncryptedFrameHelper extends AbstractFrameHelper {
    private final static String NOISE_PROTOCOL = "Noise_NNpsk0_25519_ChaChaPoly_SHA256";
    private final String encryptionKeyBase64;
    private final String expectedServername;
    private HandshakeState client;
    private CipherStatePair cipherStatePair;
    private NoiseProtocolState state;

    public EncryptedFrameHelper(ConnectionSelector connectionSelector, CommunicationListener listener,
            String encryptionKeyBase64, @Nullable String expectedServername, String logPrefix) {
        this.encryptionKeyBase64 = encryptionKeyBase64;
        this.expectedServername = expectedServername;
        this.listener = listener;

        connection = new ESPHomeConnection(connectionSelector, this, logPrefix);
    }

    @Override
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
            if (buffer.remaining() >= protoPacketLength) {
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

                buffer.compact();
                processBuffer();
            } else {
                buffer.position(buffer.limit());
                buffer.limit(buffer.capacity());
            }
        } catch (ShortBufferException e) {
            throw new ProtocolAPIError(e.getMessage());
        }
    }

    private void handleHello(byte[] packetData) throws ProtocolAPIError, ShortBufferException {
        if (packetData[0] != PROTOCOL_ENCRYPTED) {
            listener.onParseError(CommunicationError.DEVICE_REQUIRES_PLAINTEXT);
        } else {
            // Verify server name
            byte[] serverName = Arrays.copyOfRange(packetData, 1, packetData.length - 1);
            String server = new String(serverName, StandardCharsets.US_ASCII);

            if (expectedServername != null && !server.equals(expectedServername)) {
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
            return;
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

    private void handleReady(byte[] packetData) {
        try {
            byte[] decrypted = decryptPacket(packetData);
            int messageType = (decrypted[0] << 8) | decrypted[1];
            byte[] messageData = Arrays.copyOfRange(decrypted, 4, decrypted.length);
            decodeProtoMessage(messageType, messageData);
        } catch (Exception e) {
            listener.onParseError(CommunicationError.PACKET_ERROR);
        }
    }

    public ByteBuffer encodeFrame(GeneratedMessageV3 message) throws ProtocolAPIError {
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

    private static short bytesToShort(final byte[] data) {
        short value = (short) (data[0] & 0xff);
        value <<= 8;
        value |= data[1] & 0xff;
        return value;
    }

    private enum NoiseProtocolState {
        HELLO,
        HANDSHAKE,
        READY
    }
}
