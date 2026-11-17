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

import java.net.URI;
import java.net.URISyntaxException;
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
import org.openhab.core.io.transport.serial.PortInUseException;
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
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
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
        int requestedPort = portName.getPort();
        // Thing IDs may contain underscores, which URI does not accept as a DNS host.
        if (deviceName == null && portName.getRawAuthority() != null
                && portName.getRawAuthority().matches("[a-zA-Z0-9_.-]+(?::[0-9]{1,5})?")) {
            String[] authority = portName.getRawAuthority().split(":", 2);
            deviceName = authority[0];
            requestedPort = authority.length == 2 ? Integer.parseInt(authority[1]) : -1;
        }
        if (deviceName == null) {
            return null;
        }

        int port = requestedPort > 0 ? requestedPort : DEFAULT_PORT;
        String requestedDevice = deviceName;
        String proxyNameOrInstance = proxyNameOrInstance(portName);

        return devices.values().stream().filter(device -> device.matchesDevice(requestedDevice, port))
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

        devices.compute(handler.getThing().getUID(), (ThingUID uid, @Nullable ESPHomeSerialProxyDevice previous) -> {
            if (previous != null) {
                previous.closePorts(true);
            }
            return new ESPHomeSerialProxyDevice(handler, serialProxies);
        });
    }

    public void unregister(ESPHomeHandler handler) {
        ESPHomeSerialProxyDevice device = devices.remove(handler.getThing().getUID());
        if (device != null) {
            device.closePorts(false);
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
        String path = StringUtils.defaultString(portName.getPath());
        if (path.isEmpty() || "/".equals(path)) {
            return "0";
        }
        String value = path.startsWith("/") ? path.substring(1) : path;
        return value;
    }

    static @Nullable String resolvedAddress(@Nullable String hostname) {
        String trimmed = StringUtils.trimToNull(hostname);
        if (trimmed == null) {
            return null;
        }
        return InetAddresses.isInetAddress(trimmed) ? InetAddresses.forString(trimmed).getHostAddress() : null;
    }

    static class ESPHomeSerialProxyDevice {
        private final ESPHomeHandler handler;
        private final List<SerialProxyInfo> serialProxies;
        private final Map<Integer, ESPHomeSerialPort> openPorts = new ConcurrentHashMap<>();
        private final @Nullable String configuredHostnameAddress;
        private boolean retired;

        ESPHomeSerialProxyDevice(ESPHomeHandler handler, List<SerialProxyInfo> serialProxies) {
            this.handler = handler;
            this.serialProxies = List.copyOf(serialProxies);
            this.configuredHostnameAddress = resolvedAddress(
                    StringUtils.firstNonBlank(handler.getResolvedIpAddress(), handler.getHostname()));
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

        ESPHomeSerialPort open(int instance, String owner) throws PortInUseException {
            synchronized (handler.getSerialProxyLock()) {
                if (retired || openPorts.containsKey(instance)) {
                    throw new PortInUseException(
                            new IllegalStateException("ESPHome serial proxy unavailable or already open"));
                }
                ESPHomeSerialPort port = new ESPHomeSerialPort(this, instance, owner);
                openPorts.put(instance, port);
                try {
                    port.subscribe();
                } catch (ProtocolAPIError e) {
                    openPorts.remove(instance, port);
                    port.closeFromProvider();
                    throw new PortInUseException(e);
                }
                return port;
            }
        }

        void close(int instance, ESPHomeSerialPort port) {
            synchronized (handler.getSerialProxyLock()) {
                port.closeFromProvider();
                if (openPorts.remove(instance, port)) {
                    port.unsubscribe();
                }
            }
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

        void closePorts(boolean unsubscribe) {
            synchronized (handler.getSerialProxyLock()) {
                retired = true;
                openPorts.values().forEach(port -> {
                    port.closeFromProvider();
                    if (unsubscribe) {
                        port.unsubscribe();
                    }
                });
                openPorts.clear();
            }
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
            String authority = deviceName + (handler.getPort() == DEFAULT_PORT ? "" : ":" + handler.getPort());
            // Numeric names would otherwise select an instance rather than the named proxy.
            String path = "/" + (proxyName.isBlank() || parseInstance(proxyName) != null ? Integer.toString(instance)
                    : proxyName);
            try {
                return new URI(SCHEME, authority, path, null, null).toASCIIString();
            } catch (URISyntaxException e) {
                throw new IllegalStateException("Invalid ESPHome serial proxy identifier", e);
            }
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
