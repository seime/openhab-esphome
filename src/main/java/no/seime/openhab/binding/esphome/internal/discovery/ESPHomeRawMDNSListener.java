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
 * This bypasses mDNS caching by inspecting packets directly.
 */
@Component(immediate = true)
@NonNullByDefault
public class ESPHomeRawMDNSListener {

    private static final Logger logger = LoggerFactory.getLogger(ESPHomeRawMDNSListener.class);
    private static final int MDNS_PORT = 5353;
    private static final String MDNS_GROUP = "224.0.0.251";
    private static final String ESPHOME_SERVICE_TYPE = "_esphomelib._tcp.local";
    private static final int MDNS_HEADER_SIZE = 12;
    private static final int MDNS_MAX_DATAGRAM_SIZE = 9000;
    private static final int QR_BIT_MASK = 0x80;
    private static final int OFFSET_QDCOUNT = 4;
    private static final int OFFSET_ANCOUNT = 6;
    private static final int OFFSET_NSCOUNT = 8;
    private static final int OFFSET_ARCOUNT = 10;
    private static final int QTYPE_QCLASS_SIZE = 4;
    private static final int RR_FIXED_SIZE = 10;
    private static final int TYPE_PTR = 12;
    private static final int PTR_OFFSET_TYPE = 0;
    private static final int PTR_OFFSET_TTL = 4;
    private static final int PTR_OFFSET_DATALEN = 8;
    private static final int POINTER_MASK = 0xC0;
    private static final int POINTER_VALUE = 0xC0;
    private static final int OFFSET_MASK = 0x3F;
    private static final int MAX_JUMPS = 20;

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

            NetworkInterface.networkInterfaces().forEach(ni -> {
                try {
                    if (ni.isUp() && ni.supportsMulticast() && !ni.isLoopback()) {
                        socket.joinGroup(new InetSocketAddress(group, MDNS_PORT), ni);
                        logger.debug("Joined mDNS group on interface: {}", ni.getName());
                    }
                } catch (Exception e) {
                    logger.debug("Failed to join mDNS group on interface: {} got {} - ignoring the interface.",
                            ni.getName(), e.getMessage(), e);
                }
            });

            byte[] buffer = new byte[MDNS_MAX_DATAGRAM_SIZE];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            logger.info("Started raw mDNS listener on port {}", MDNS_PORT);

            while (running) {
                try {
                    socket.receive(packet);
                    processPacket(packet);
                } catch (IOException e) {
                    if (running) {
                        logger.warn("Error receiving mDNS packet: {}", e.getMessage(), e);
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
        List<String> deviceIds = new ArrayList<String>();
        byte[] data = packet.getData();
        int packetOffset = packet.getOffset();
        int packetLength = packet.getLength();
        int packetEnd = packetOffset + packetLength;

        if (packetLength < MDNS_HEADER_SIZE) {
            return deviceIds;
        }

        // Check QR bit in header. 1 for response, 0 for query. We only care about responses.
        if (!checkBit(data[packetOffset + 2], QR_BIT_MASK)) {
            return deviceIds;
        }

        // Header
        int qdCount = getWord(data, packetOffset + OFFSET_QDCOUNT);
        int anCount = getWord(data, packetOffset + OFFSET_ANCOUNT);
        int nsCount = getWord(data, packetOffset + OFFSET_NSCOUNT);
        int arCount = getWord(data, packetOffset + OFFSET_ARCOUNT);

        int currentPos = packetOffset + MDNS_HEADER_SIZE;

        // Skip Questions
        for (int i = 0; i < qdCount; i++) {
            ParsedName pn = parseName(data, currentPos, packetOffset, packetEnd);
            if (pn == null) {
                return deviceIds;
            }
            currentPos = pn.nextOffset();
            currentPos += QTYPE_QCLASS_SIZE; // Skip QTYPE and QCLASS
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
            currentPos = pn.nextOffset();

            if (currentPos + RR_FIXED_SIZE > packetEnd) {
                return deviceIds;
            }

            int type = getWord(data, currentPos + PTR_OFFSET_TYPE);
            long ttl = getUnsignedInt(data, currentPos + PTR_OFFSET_TTL);
            int dataLen = getWord(data, currentPos + PTR_OFFSET_DATALEN);

            String name = pn.name();

            // Check for PTR record pointing to our service
            if (type == TYPE_PTR && ESPHOME_SERVICE_TYPE.equals(name)) {
                if (ttl == 0) {
                    logger.debug("Ignoring mDNS goodbye packet for {}", name);
                } else {
                    int rdataPos = currentPos + RR_FIXED_SIZE;
                    ParsedName target = parseName(data, rdataPos, packetOffset, packetEnd);
                    if (target != null) {
                        String targetName = target.name();
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

            currentPos += RR_FIXED_SIZE + dataLen;
        }

        return deviceIds.stream().distinct().toList();
    }

    private record ParsedName(String name, int nextOffset) {
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
                if (!jumped) {
                    nextOffset = current + 1;
                }
                break;
            } else if (checkBit(data[current], POINTER_MASK)) {
                if (current + 1 >= packetEnd) {
                    return null;
                }
                int pointer = ((len & OFFSET_MASK) << 8) | (data[current + 1] & 0xFF);
                if (!jumped) {
                    nextOffset = current + 2;
                    jumped = true;
                }
                current = packetStart + pointer;
                jumps++;
                if (jumps > MAX_JUMPS) {
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
        if (!sb.isEmpty()) {
            sb.setLength(sb.length() - 1);
        }
        return new ParsedName(sb.toString(), nextOffset);
    }

    public static int getWord(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    public static long getUnsignedInt(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    public static boolean checkBit(byte value, int mask) {
        return (value & mask) != 0;
    }
}
