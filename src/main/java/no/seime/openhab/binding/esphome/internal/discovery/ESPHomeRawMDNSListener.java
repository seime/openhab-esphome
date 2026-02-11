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
package no.seime.openhab.binding.esphome.internal.discovery;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandlerFactory;

/**
 * The {@link ESPHomeRawMDNSListener} listens for raw mDNS packets to trigger reconnects.
 * This bypasses JmDNS caching issues by inspecting packets directly.
 */
@Component(immediate = true)
@NonNullByDefault
public class ESPHomeRawMDNSListener {

    private static final Logger logger = LoggerFactory.getLogger(ESPHomeRawMDNSListener.class);
    private static final int MDNS_PORT = 5353;
    private static final String MDNS_GROUP = "224.0.0.251";
    private static final String ESPHOME_SERVICE_TYPE = "_esphomelib._tcp.local";

    private final ESPHomeHandlerFactory espHomeHandlerFactory;
    @Nullable
    private Thread listenerThread;
    private volatile boolean running = false;
    @Nullable
    private MulticastSocket socket;

    @Activate
    public ESPHomeRawMDNSListener(@Reference ESPHomeHandlerFactory espHomeHandlerFactory) {
        this.espHomeHandlerFactory = espHomeHandlerFactory;
        startListener();
    }

    @Deactivate
    public void deactivate() {
        stopListener();
    }

    private void startListener() {
        running = true;
        listenerThread = new Thread(this::listen, "ESPHome Raw mDNS Listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void stopListener() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
    }

    private void listen() {
        try {
            socket = new MulticastSocket(MDNS_PORT);
            socket.setReuseAddress(true);
            InetAddress group = InetAddress.getByName(MDNS_GROUP);

            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                try {
                    if (ni.isUp() && ni.supportsMulticast() && !ni.isLoopback()) {
                        socket.joinGroup(new InetSocketAddress(group, MDNS_PORT), ni);
                        logger.debug("Joined mDNS group on interface: {}", ni.getName());
                    }
                } catch (Exception e) {
                    // Ignore interfaces we can't join
                }
            }

            byte[] buffer = new byte[9000];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            logger.info("Started raw mDNS listener on port {}", MDNS_PORT);

            while (running) {
                try {
                    socket.receive(packet);
                    processPacket(packet);
                } catch (IOException e) {
                    if (running) {
                        logger.warn("Error receiving mDNS packet: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to start raw mDNS listener", e);
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    private void processPacket(DatagramPacket packet) {
        List<String> deviceIds = findDeviceIdsInPacket(packet);
        if (!deviceIds.isEmpty()) {
            logger.debug("Detected ESPHome mDNS activity from {}.", deviceIds);
            espHomeHandlerFactory.onDeviceReappeared(deviceIds);
        }
    }

    public static List<String> findDeviceIdsInPacket(DatagramPacket packet) {
        List<String> deviceIds = new ArrayList<>();
        byte[] data = packet.getData();
        int packetOffset = packet.getOffset();
        int packetLength = packet.getLength();
        int packetEnd = packetOffset + packetLength;

        if (packetLength < 12) {
            return deviceIds;
        }

        // Check QR bit in header. 1 for response, 0 for query. We only care about responses.
        boolean isResponse = (data[packetOffset + 2] & 0x80) != 0;
        if (!isResponse) {
            return deviceIds;
        }

        // Header
        int qdCount = ((data[packetOffset + 4] & 0xFF) << 8) | (data[packetOffset + 5] & 0xFF);
        int anCount = ((data[packetOffset + 6] & 0xFF) << 8) | (data[packetOffset + 7] & 0xFF);
        int nsCount = ((data[packetOffset + 8] & 0xFF) << 8) | (data[packetOffset + 9] & 0xFF);
        int arCount = ((data[packetOffset + 10] & 0xFF) << 8) | (data[packetOffset + 11] & 0xFF);

        int currentPos = packetOffset + 12;

        // Skip Questions
        for (int i = 0; i < qdCount; i++) {
            ParsedName pn = parseName(data, currentPos, packetOffset, packetEnd);
            if (pn == null) {
                return deviceIds;
            }
            currentPos = pn.nextOffset;
            currentPos += 4; // Skip QTYPE and QCLASS
        }

        int totalRecords = anCount + nsCount + arCount;
        for (int i = 0; i < totalRecords; i++) {
            if (currentPos >= packetEnd) {
                break;
            }

            ParsedName pn = parseName(data, currentPos, packetOffset, packetEnd);
            if (pn == null) {
                return deviceIds;
            }
            currentPos = pn.nextOffset;

            if (currentPos + 10 > packetEnd) {
                return deviceIds;
            }

            int type = ((data[currentPos] & 0xFF) << 8) | (data[currentPos + 1] & 0xFF);
            long ttl = ((data[currentPos + 4] & 0xFF) << 24) | ((data[currentPos + 5] & 0xFF) << 16)
                    | ((data[currentPos + 6] & 0xFF) << 8) | (data[currentPos + 7] & 0xFF);
            int dataLen = ((data[currentPos + 8] & 0xFF) << 8) | (data[currentPos + 9] & 0xFF);

            String name = pn.name;

            // Check for PTR record pointing to our service
            if (type == 12 && ESPHOME_SERVICE_TYPE.equals(name)) {
                if (ttl == 0) {
                    logger.info("Ignoring mDNS goodbye packet for {}", name);
                } else {
                    int rdataPos = currentPos + 10;
                    ParsedName target = parseName(data, rdataPos, packetOffset, packetEnd);
                    if (target != null) {
                        String targetName = target.name;
                        if (targetName.endsWith("." + ESPHOME_SERVICE_TYPE)) {
                            String deviceId = targetName.substring(0,
                                    targetName.length() - ESPHOME_SERVICE_TYPE.length() - 1);
                            if (!deviceId.isEmpty()) {
                                deviceIds.add(deviceId);
                                logger.debug("Found PTR record pointing to: {} - added deviceId {}", targetName,
                                        deviceId);
                            }
                        }
                    }
                }
            }

            currentPos += 10 + dataLen;
        }

        return deviceIds.stream().distinct().toList();
    }

    private static class ParsedName {
        final String name;
        final int nextOffset;

        ParsedName(String name, int nextOffset) {
            this.name = name;
            this.nextOffset = nextOffset;
        }
    }

    private static @Nullable ParsedName parseName(byte[] data, int offset, int packetStart, int packetEnd) {
        StringBuilder sb = new StringBuilder();
        int current = offset;
        boolean jumped = false;
        int nextOffset = -1;
        int jumps = 0;

        while (true) {
            if (current >= packetEnd) {
                return null;
            }
            int len = data[current] & 0xFF;

            if (len == 0) {
                if (!jumped)
                    nextOffset = current + 1;
                break;
            } else if ((len & 0xC0) == 0xC0) {
                if (current + 1 >= packetEnd) {
                    return null;
                }
                int pointer = ((len & 0x3F) << 8) | (data[current + 1] & 0xFF);
                if (!jumped) {
                    nextOffset = current + 2;
                    jumped = true;
                }
                current = packetStart + pointer;
                jumps++;
                if (jumps > 20) {
                    return null; // Loop protection
                }
            } else {
                current++;
                if (current + len > packetEnd) {
                    return null;
                }
                sb.append(new String(data, current, len, StandardCharsets.UTF_8)).append(".");
                current += len;
            }
        }
        if (!sb.isEmpty())
            sb.setLength(sb.length() - 1);
        return new ParsedName(sb.toString(), nextOffset);
    }
}
