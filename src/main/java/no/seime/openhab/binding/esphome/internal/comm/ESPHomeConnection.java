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

    private SocketChannel socketChannel;

    private final AbstractFrameHelper frameHelper;
    private final ConnectionSelector connectionSelector;

    private final String hostname;

    public ESPHomeConnection(ConnectionSelector connectionSelector, AbstractFrameHelper frameHelper, String hostname) {
        this.frameHelper = frameHelper;
        this.connectionSelector = connectionSelector;
        this.hostname = hostname;
    }

    public synchronized void send(ByteBuffer buffer) throws ProtocolAPIError {
        try {
            while (buffer.hasRemaining()) {
                logger.trace("Writing data");
                socketChannel.write(buffer);
            }
        } catch (IOException e) {
            throw new ProtocolAPIError(String.format("[%s] Error sending message: %s ", hostname, e));
        }
    }

    public void connect(InetSocketAddress espDeviceAddress) throws ProtocolAPIError {
        try {

            socketChannel = SocketChannel.open(espDeviceAddress);
            socketChannel.configureBlocking(false);
            connectionSelector.register(socketChannel, frameHelper);

            logger.info("[{}] Opening socket to {} at port {}.", hostname, hostname, espDeviceAddress.getPort());

        } catch (Exception e) {
            throw new ProtocolAPIError("Failed to connect to '" + hostname + "' port " + espDeviceAddress.getPort(), e);
        }
    }

    public void close() {
        logger.info("[{}] Disconnecting socket.", hostname);
        try {
            if (socketChannel != null) {
                connectionSelector.unregister(socketChannel);
                socketChannel.close();
                socketChannel = null;
            }
        } catch (IOException e) {
            logger.debug("[{}] Error closing connection", hostname, e);
        }
    }
}
