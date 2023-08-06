/**
 * Copyright (c) 2023 Contributors to the Seime Openhab Addons project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package no.seime.openhab.binding.esphome.internal.internal.comm;

import static no.seime.openhab.binding.esphome.internal.internal.comm.VarIntConverter.bytesToInt;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.GeneratedMessageV3;

import io.esphome.api.Api;
import no.seime.openhab.binding.esphome.internal.internal.PacketListener;

public class PlainTextPacketStreamReader {

    public static final int PREAMBLE = 0x00;
    public static final int ENCRYPTION_REQUIRED = 0x01;
    public static final int VAR_INT_MARKER = 0x80;
    private final Logger logger = LoggerFactory.getLogger(PlainTextPacketStreamReader.class);

    private boolean keepRunning = true;
    private Thread thread;
    private InputStream inputStream;

    private final PacketListener listener;

    private boolean closeQuietly = false;

    private final Map<Integer, Method> messageTypeToMessageClass = new HashMap<>();

    public PlainTextPacketStreamReader(PacketListener listener) {
        this.listener = listener;

        // Build a cache of message id to message class
        Api.getDescriptor().getMessageTypes().forEach(messageDescriptor -> {
            try {
                int id = messageDescriptor.getOptions().getExtension(io.esphome.api.ApiOptions.id);
                if (id > 0) {
                    Class<? extends GeneratedMessageV3> subclass = Class.forName(messageDescriptor.getFullName())
                            .asSubclass(GeneratedMessageV3.class);
                    Method parseMethod = subclass.getDeclaredMethod("parseFrom", byte[].class);

                    messageTypeToMessageClass.put(id, parseMethod);
                }
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void parseStream(InputStream inputStream) {
        this.inputStream = inputStream;
        thread = new Thread(() -> {
            while (keepRunning) {
                try {

                    byte[] data = new byte[3];
                    int read = inputStream.read(data);
                    if (read == -1) {
                        keepRunning = false;
                        if (!closeQuietly) {
                            logger.debug("End of stream");
                            listener.onEndOfStream();
                        }
                        return;
                    }

                    headerReceived(data);
                } catch (ProtocolException | IOException e) {
                    logger.debug("Error reading from socket", e);
                    keepRunning = false;
                    if (!closeQuietly) {
                        logger.debug("End of stream");
                        listener.onParseError();
                    }

                }
            }
        });
        thread.setName("ESPHome stream parser");
        thread.start();
    }

    public void headerReceived(byte[] headerData) throws IOException, ProtocolException {
        if (headerData[0] != PREAMBLE) {
            if (headerData[0] == ENCRYPTION_REQUIRED) {
                handleAndClose(new RequiresEncryptionAPIError("Connection requires encryption"));
                return;
            }

            handleAndClose(new ProtocolAPIError(String.format("Invalid preamble %02x", headerData[0])));
            return;
        }

        byte[] encodedProtoPacketLenghtBuffer;
        byte[] encodedMessageTypeBuffer;

        if ((headerData[1] & VAR_INT_MARKER) == VAR_INT_MARKER) {
            // Length is longer than 1 byte
            encodedProtoPacketLenghtBuffer = Arrays.copyOfRange(headerData, 1, 3);
            encodedMessageTypeBuffer = new byte[0];
        } else {
            // This is the most common case with 99% of messages
            // needing a single byte for length and type which means
            // we avoid 2 calls to readexactly
            encodedProtoPacketLenghtBuffer = Arrays.copyOfRange(headerData, 1, 2);
            encodedMessageTypeBuffer = Arrays.copyOfRange(headerData, 2, 3);
        }

        // If the message is long, we need to read the rest of the length
        while ((encodedProtoPacketLenghtBuffer[encodedProtoPacketLenghtBuffer.length - 1]
                & VAR_INT_MARKER) == VAR_INT_MARKER) {
            byte[] additionalLength = readBytes(1);
            if (additionalLength == null) {
                return;
            }
            encodedProtoPacketLenghtBuffer = concatArrays(encodedProtoPacketLenghtBuffer, additionalLength);
        }

        // If the message length was longer than 1 byte, we need to read the message type
        while (encodedMessageTypeBuffer.length == 0
                || (encodedMessageTypeBuffer[encodedMessageTypeBuffer.length - 1] & VAR_INT_MARKER) == VAR_INT_MARKER) {
            byte[] additionalencodedMessageTypeBuffer = readBytes(1);
            if (additionalencodedMessageTypeBuffer == null) {
                return;
            }
            encodedMessageTypeBuffer = concatArrays(encodedMessageTypeBuffer, additionalencodedMessageTypeBuffer);
        }

        Integer protoPacketLength = bytesToInt(encodedProtoPacketLenghtBuffer);
        Integer messageType = bytesToInt(encodedMessageTypeBuffer);

        if (protoPacketLength == 0) {
            decodeProtoMessage(messageType, new byte[0]);
            // If we have more data, continue processing
            return;
        }

        byte[] packetData = readBytes(protoPacketLength);
        decodeProtoMessage(messageType, packetData);
    }

    private byte[] readBytes(int numBytes) throws IOException {
        return inputStream.readNBytes(numBytes);
    }

    private void decodeProtoMessage(int messageType, byte[] bytes) {
        logger.debug("Received packet of type {} with data {}", messageType, bytes);

        try {
            Method parseMethod = messageTypeToMessageClass.get(messageType);
            if (parseMethod != null) {
                GeneratedMessageV3 invoke = (GeneratedMessageV3) parseMethod.invoke(null, bytes);
                if (invoke != null) {
                    listener.onPacket(invoke);
                } else {
                    logger.warn("Received null packet of type {}", parseMethod);
                }
            }
        } catch (ProtocolAPIError | IllegalAccessException | InvocationTargetException | IOException e) {
            logger.warn("Error parsing packet", e);
            listener.onParseError();
        }
    }

    private byte[] concatArrays(byte[] length, byte[] additionalLength) {
        byte[] result = new byte[length.length + additionalLength.length];
        System.arraycopy(length, PREAMBLE, result, PREAMBLE, length.length);
        System.arraycopy(additionalLength, PREAMBLE, result, length.length, additionalLength.length);
        return result;
    }

    private void handleAndClose(ProtocolException connectionRequiresEncryption) throws ProtocolException {
        // close socket
        try {
            inputStream.close();
        } catch (IOException e) {
            logger.warn("Error closing stream", e);
        }
        throw connectionRequiresEncryption;
    }

    public void close(boolean closeQuietly) {
        this.closeQuietly = closeQuietly;
        keepRunning = false;

        if (thread != null) {
            thread.interrupt();
        }
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException e) {
                logger.warn("Error closing stream", e);
            }
        }
    }
}
