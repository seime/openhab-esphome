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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.GeneratedMessageV3;

import no.seime.openhab.binding.esphome.internal.internal.PacketListener;

public class PlainTextConnection {

    private final Logger logger = LoggerFactory.getLogger(PlainTextConnection.class);

    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;

    private PlainTextPacketStreamReader packetStreamReader;
    private String hostname;

    public PlainTextConnection(PacketListener listener) {
        packetStreamReader = new PlainTextPacketStreamReader(listener);
    }

    public static byte[] encodeFrame(GeneratedMessageV3 message) {
        byte[] protoBytes = message.toByteArray();
        byte[] idVarUint = VarIntConverter
                .intToBytes(message.getDescriptorForType().getOptions().getExtension(io.esphome.api.ApiOptions.id));
        byte[] protoBytesLengthVarUint = VarIntConverter.intToBytes(protoBytes.length);

        byte[] frame = new byte[1 + idVarUint.length + protoBytesLengthVarUint.length + protoBytes.length];
        System.arraycopy(protoBytesLengthVarUint, 0, frame, 1, protoBytesLengthVarUint.length);
        System.arraycopy(idVarUint, 0, frame, idVarUint.length + 1, protoBytesLengthVarUint.length);
        System.arraycopy(protoBytes, 0, frame, idVarUint.length + protoBytesLengthVarUint.length + 1,
                protoBytes.length);
        return frame;
    }

    public synchronized void send(GeneratedMessageV3 message) throws ProtocolAPIError {
        try {
            logger.debug("[{}] Sending message: {}", hostname, message.getClass().getSimpleName());
            outputStream.write(encodeFrame(message));
            outputStream.flush();

        } catch (IOException e) {
            throw new ProtocolAPIError(String.format("[%s] Error sending message " + e, hostname));
        }
    }

    public void connect(String hostname, int port, int connectTimeout) throws ProtocolAPIError {
        this.hostname = hostname;
        try {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.connect(new InetSocketAddress(hostname, port), connectTimeout * 1000);

            logger.info("[{}] Socket opened to {} at port {}.", hostname, hostname, port);

            inputStream = new BufferedInputStream(socket.getInputStream());
            outputStream = socket.getOutputStream();

            packetStreamReader.parseStream(inputStream);

        } catch (IOException e) {
            throw new ProtocolAPIError("Failed to connect to " + hostname + ":" + port + ": " + e.getMessage());
        }
    }

    public void close(boolean quietly) {
        logger.info("[{}] Disconnecting socket.", hostname);
        try {
            if (packetStreamReader != null) {
                packetStreamReader.close(quietly);
                packetStreamReader = null;
            }
            if (inputStream != null) {
                inputStream.close();
                inputStream = null;
            }
            if (outputStream != null) {
                outputStream.flush();
                outputStream.close();
                outputStream = null;
            }
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (IOException e) {
            logger.debug("[{}] Error closing connection", hostname, e);
        }
    }
}
