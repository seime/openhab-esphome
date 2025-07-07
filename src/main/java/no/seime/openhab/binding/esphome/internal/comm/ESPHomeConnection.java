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
package no.seime.openhab.binding.esphome.internal.comm;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ESPHomeConnection {

    private final Logger logger = LoggerFactory.getLogger(ESPHomeConnection.class);
    private final EncryptedFrameHelper frameHelper;
    private final ConnectionSelector connectionSelector;
    private final String logPrefix;
    private SocketChannel socketChannel;

    public ESPHomeConnection(ConnectionSelector connectionSelector, EncryptedFrameHelper frameHelper,
            String logPrefix) {
        this.frameHelper = frameHelper;
        this.connectionSelector = connectionSelector;
        this.logPrefix = logPrefix;
    }

    public synchronized void send(ByteBuffer buffer) throws ProtocolAPIError {
        if (socketChannel != null) {
            try {
                while (buffer.hasRemaining()) {
                    logger.trace("[{}] Writing data {} bytes", logPrefix, buffer.remaining());
                    socketChannel.write(buffer);
                }

            } catch (IOException e) {
                throw new ProtocolAPIError(String.format("[%s] Error sending message: %s ", logPrefix, e));
            }
        } else {
            logger.warn("[{}] Attempted to send data on a closed connection", logPrefix);
        }
    }

    public void connect(String hostname, int port) throws ProtocolAPIError {
        try {
            logger.info("[{}] Opening socket to {} at port {}.", logPrefix, hostname, port);

            socketChannel = SocketChannel.open();
            socketChannel.configureBlocking(false);
            InetSocketAddress espHomeAddress = new InetSocketAddress(hostname, port);
            socketChannel.connect(espHomeAddress);
            connectionSelector.register(socketChannel, frameHelper);

        } catch (Exception e) {
            throw new ProtocolAPIError("Failed to connect to '" + hostname + "' port " + port, e);
        }
    }

    public synchronized void close() {
        logger.info("[{}] Disconnecting socket.", logPrefix);
        try {
            if (socketChannel != null) {
                connectionSelector.unregister(socketChannel);
                socketChannel.close();
                socketChannel = null;
            }
        } catch (IOException e) {
            logger.debug("[{}] Error closing connection", logPrefix, e);
        }
    }
}
