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
package no.seime.openhab.binding.esphome.internal.serial;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.io.transport.serial.ProtocolType;
import org.openhab.core.io.transport.serial.ProtocolType.PathType;
import org.openhab.core.io.transport.serial.SerialPortIdentifier;
import org.openhab.core.io.transport.serial.SerialPortProvider;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;

import com.google.common.net.InetAddresses;

import io.esphome.api.SerialProxyDataReceived;
import io.esphome.api.SerialProxyGetModemPinsResponse;
import io.esphome.api.SerialProxyInfo;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

@NonNullByDefault
@Component(service = { SerialPortProvider.class, ESPHomeSerialPortProvider.class })
public class ESPHomeSerialPortProvider implements SerialPortProvider {
    static final String SCHEME = "esphome";
    private static final int DEFAULT_PORT = 6053;
    private static final ProtocolType PROTOCOL = new ProtocolType(PathType.NET, SCHEME);

    private final Map<ThingUID, ESPHomeSerialProxyDevice> devices = new ConcurrentHashMap<>();

    @Override
    public @Nullable SerialPortIdentifier getPortIdentifier(URI portName) {
        if (!SCHEME.equalsIgnoreCase(portName.getScheme())) {
            return null;
        }

        String deviceName = StringUtils.trimToNull(portName.getHost());
        if (deviceName == null) {
            return null;
        }

        int port = portName.getPort() > 0 ? portName.getPort() : DEFAULT_PORT;
        String proxyNameOrInstance = proxyNameOrInstance(portName);

        return devices.values().stream().filter(device -> device.matchesDevice(deviceName, port))
                .map(device -> device.identifier(proxyNameOrInstance)).filter(Objects::nonNull).findFirst()
                .orElse(null);
    }

    @Override
    public Stream<ProtocolType> getAcceptedProtocols() {
        return Stream.of(PROTOCOL);
    }

    @Override
    public Stream<SerialPortIdentifier> getSerialPortIdentifiers() {
        return devices.values().stream().flatMap(ESPHomeSerialProxyDevice::identifiers);
    }

    public void register(ESPHomeHandler handler, List<SerialProxyInfo> serialProxies) {
        if (serialProxies.isEmpty()) {
            unregister(handler);
            return;
        }

        devices.put(handler.getThing().getUID(), new ESPHomeSerialProxyDevice(handler, serialProxies));
    }

    public void unregister(ESPHomeHandler handler) {
        ESPHomeSerialProxyDevice device = devices.remove(handler.getThing().getUID());
        if (device != null) {
            device.closePorts();
        }
    }

    public void handleData(ESPHomeHandler handler, SerialProxyDataReceived message) {
        ESPHomeSerialProxyDevice device = devices.get(handler.getThing().getUID());
        if (device != null) {
            device.handleData(message);
        }
    }

    public void handleModemPins(ESPHomeHandler handler, SerialProxyGetModemPinsResponse message) {
        ESPHomeSerialProxyDevice device = devices.get(handler.getThing().getUID());
        if (device != null) {
            device.handleModemPins(message);
        }
    }

    private static String proxyNameOrInstance(URI portName) {
        String path = StringUtils.trimToEmpty(portName.getPath());
        if (path.isEmpty() || "/".equals(path)) {
            return "0";
        }
        String value = path.startsWith("/") ? path.substring(1) : path;
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    static @Nullable String resolvedAddress(@Nullable String hostname) {
        String trimmed = StringUtils.trimToNull(hostname);
        if (trimmed == null) {
            return null;
        }
        try {
            InetAddress address = InetAddresses.isInetAddress(trimmed) ? InetAddresses.forString(trimmed)
                    : InetAddress.getByName(trimmed);
            return address.getHostAddress();
        } catch (UnknownHostException | IllegalArgumentException e) {
            return null;
        }
    }

    static class ESPHomeSerialProxyDevice {
        private final ESPHomeHandler handler;
        private final List<SerialProxyInfo> serialProxies;
        private final Map<Integer, ESPHomeSerialPort> openPorts = new ConcurrentHashMap<>();
        private final @Nullable String configuredHostnameAddress;

        ESPHomeSerialProxyDevice(ESPHomeHandler handler, List<SerialProxyInfo> serialProxies) {
            this.handler = handler;
            this.serialProxies = List.copyOf(serialProxies);
            this.configuredHostnameAddress = resolvedAddress(handler.getHostname());
        }

        boolean matchesDevice(String requestedDeviceName, int requestedPort) {
            if (requestedPort != handler.getPort()) {
                return false;
            }

            List<@Nullable String> candidates = new ArrayList<>();
            candidates.add(handler.getThing().getUID().getId());
            candidates.add(handler.getDeviceId());
            candidates.add(handler.getHostname());
            candidates.add(handler.getEspHomeDeviceName());
            if (candidates.stream().filter(Objects::nonNull)
                    .anyMatch(candidate -> candidate.equalsIgnoreCase(requestedDeviceName))) {
                return true;
            }

            String requestedAddress = resolvedAddress(requestedDeviceName);
            return requestedAddress != null && requestedAddress.equals(configuredHostnameAddress);
        }

        @Nullable
        SerialPortIdentifier identifier(String proxyNameOrInstance) {
            Integer instance = parseInstance(proxyNameOrInstance);
            if (instance == null) {
                instance = IntStream.range(0, serialProxies.size())
                        .filter(i -> serialProxies.get(i).getName().equalsIgnoreCase(proxyNameOrInstance)).boxed()
                        .findFirst().orElse(null);
            }
            if (instance == null || instance < 0 || instance >= serialProxies.size()) {
                return null;
            }
            return new ESPHomeSerialPortIdentifier(this, instance);
        }

        Stream<SerialPortIdentifier> identifiers() {
            return IntStream.range(0, serialProxies.size()).mapToObj(i -> new ESPHomeSerialPortIdentifier(this, i));
        }

        ESPHomeSerialPort open(int instance, String owner) {
            ESPHomeSerialPort port = new ESPHomeSerialPort(this, instance, owner);
            openPorts.put(instance, port);
            return port;
        }

        void close(int instance, ESPHomeSerialPort port) {
            openPorts.remove(instance, port);
        }

        boolean isOpen(int instance) {
            return openPorts.containsKey(instance);
        }

        @Nullable
        String owner(int instance) {
            ESPHomeSerialPort port = openPorts.get(instance);
            return port != null ? port.getOwner() : null;
        }

        void handleData(SerialProxyDataReceived message) {
            ESPHomeSerialPort port = openPorts.get(message.getInstance());
            if (port != null) {
                port.handleData(message.getData().toByteArray());
            }
        }

        void handleModemPins(SerialProxyGetModemPinsResponse message) {
            ESPHomeSerialPort port = openPorts.get(message.getInstance());
            if (port != null) {
                port.handleModemPins(message.getLineStates());
            }
        }

        void closePorts() {
            openPorts.values().forEach(ESPHomeSerialPort::closeFromProvider);
            openPorts.clear();
        }

        ESPHomeHandler handler() {
            return handler;
        }

        SerialProxyInfo proxyInfo(int instance) {
            return serialProxies.get(instance);
        }

        String uri(int instance) {
            String deviceName = StringUtils.defaultIfBlank(StringUtils.firstNonBlank(handler.getDeviceId(),
                    handler.getThing().getUID().getId(), handler.getHostname()), handler.getThing().getUID().getId());
            String proxyName = serialProxies.get(instance).getName();
            return SCHEME + "://" + deviceName + "/" + (proxyName.isBlank() ? Integer.toString(instance) : proxyName);
        }

        private static @Nullable Integer parseInstance(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private static class ESPHomeSerialPortIdentifier implements SerialPortIdentifier {
        private final ESPHomeSerialProxyDevice device;
        private final int instance;

        ESPHomeSerialPortIdentifier(ESPHomeSerialProxyDevice device, int instance) {
            this.device = device;
            this.instance = instance;
        }

        @Override
        public String getName() {
            return device.uri(instance);
        }

        @Override
        public ESPHomeSerialPort open(String owner, int timeout)
                throws org.openhab.core.io.transport.serial.PortInUseException {
            if (device.isOpen(instance)) {
                throw new org.openhab.core.io.transport.serial.PortInUseException(
                        new IllegalStateException("ESPHome serial proxy is already open"));
            }
            return device.open(instance, owner);
        }

        @Override
        public boolean isCurrentlyOwned() {
            return device.isOpen(instance);
        }

        @Override
        public @Nullable String getCurrentOwner() {
            return device.owner(instance);
        }
    }
}
