package no.seime.openhab.binding.esphome.internal.bluetooth;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.AbstractBluetoothBridgeHandler;
import org.openhab.binding.bluetooth.BluetoothAddress;
import org.openhab.core.thing.*;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.protobuf.GeneratedMessage;
import com.neovisionaries.bluetooth.ble.advertising.ADManufacturerSpecific;
import com.neovisionaries.bluetooth.ble.advertising.ADPayloadParser;
import com.neovisionaries.bluetooth.ble.advertising.ADStructure;
import com.neovisionaries.bluetooth.ble.advertising.LocalName;

import io.esphome.api.*;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;
import no.seime.openhab.binding.esphome.internal.handler.MonitoredScheduledThreadPoolExecutor;

@NonNullByDefault
public class ESPHomeBluetoothProxyHandler extends AbstractBluetoothBridgeHandler<ESPHomeBluetoothDevice> {

    private final ThingRegistry thingRegistry;

    @Nullable
    private ScheduledFuture<?> registrationFuture;
    @Nullable
    private ScheduledFuture<?> dumpFuture;

    private final Logger logger = LoggerFactory.getLogger(ESPHomeBluetoothProxyHandler.class);

    private final List<ESPHomeHandler> espHomeHandlers = new ArrayList<>();

    // Deprecated, used by older firmware
    private final LoadingCache<Long, Optional<BluetoothLEAdvertisementResponse>> singleAdvertisementPerPacketCache;

    // Deprecated, used by older firmware
    private final LoadingCache<Long, Optional<BluetoothLERawAdvertisement>> multipleAdvertisementPerPacketCache;

    private final Map<Long, SortedSet<DeviceAndRSSI>> knownDevices = new ConcurrentHashMap<>();

    private final Map<BluetoothAddress, ESPHomeBluetoothDevice> connectionMap = new ConcurrentHashMap<>();
    private final Map<ThingUID, Integer> freeConnectionsMap = new ConcurrentHashMap<>();

    private final MonitoredScheduledThreadPoolExecutor executor;

    /**
     * Creates a new instance of this class for the {@link Thing}.
     *
     * @param bridge the thing that should be handled, not null
     * @param thingRegistry
     * @param executor
     */
    public ESPHomeBluetoothProxyHandler(Bridge bridge, ThingRegistry thingRegistry,
            MonitoredScheduledThreadPoolExecutor executor) {
        super(bridge);
        this.thingRegistry = thingRegistry;
        this.executor = executor;

        // Deprecated
        CacheLoader<Long, Optional<BluetoothLEAdvertisementResponse>> deprecatedLoader = new CacheLoader<>() {
            @Override
            public Optional<BluetoothLEAdvertisementResponse> load(Long key) {
                return Optional.empty();
            }
        };

        // Deprecated
        singleAdvertisementPerPacketCache = CacheBuilder.newBuilder().expireAfterAccess(5, TimeUnit.SECONDS)
                .maximumSize(1000).build(deprecatedLoader);

        CacheLoader<Long, Optional<BluetoothLERawAdvertisement>> loader = new CacheLoader<>() {
            @Override
            public Optional<BluetoothLERawAdvertisement> load(Long key) {
                return Optional.empty();
            }
        };

        // Deprecated
        multipleAdvertisementPerPacketCache = CacheBuilder.newBuilder().expireAfterAccess(5, TimeUnit.SECONDS)
                .maximumSize(1000).build(loader);
    }

    @Override
    public void initialize() {
        super.initialize();
        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Looking for BLE enabled ESPHome devices");

        registrationFuture = executor.scheduleWithFixedDelay(this::updateESPHomeDeviceList, 0, 5, TimeUnit.SECONDS);
        dumpFuture = scheduler.scheduleWithFixedDelay(this::dumpDeviceList, 5, 60, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        registrationFuture.cancel(true);
        dumpFuture.cancel(true);
        espHomeHandlers.forEach(ESPHomeHandler::unsubscribeBLEAdvertisements);
        espHomeHandlers.clear();
        super.dispose();
    }

    private synchronized void updateESPHomeDeviceList() {

        // Get all ESPHome devices
        // For each device, check if it has BLE enabled, is enabled and ONLINE
        // If so, enable registration of BLE advertisements
        // If not, remove from list of devices

        // First clean up any disposed handlers or non-ONLINE handlers
        List<ESPHomeHandler> inactiveHandlers = espHomeHandlers.stream()
                .filter(handler -> handler.isDisposed() || !handler.getThing().getStatus().equals(ThingStatus.ONLINE))
                .toList();
        logger.debug("Found {} inactive handlers to remove", inactiveHandlers.size());
        espHomeHandlers.removeAll(inactiveHandlers);
        inactiveHandlers.stream().forEach(handler -> {
            try {
                handler.unsubscribeBLEAdvertisements();
            } catch (Exception e) {
                // Swallow
            }
        });

        List<Thing> esphomeThings = thingRegistry.stream()
                .filter(thing -> thing.getThingTypeUID().equals(BindingConstants.THING_TYPE_DEVICE)).toList();
        for (Thing esphomeThing : esphomeThings) {
            if (esphomeThing.isEnabled() && esphomeThing.getStatus().equals(ThingStatus.ONLINE)) {
                if (esphomeThing.getConfiguration().get("enableBluetoothProxy") == Boolean.TRUE) {
                    // Enable registration of BLE advertisements

                    ESPHomeHandler handler = (ESPHomeHandler) esphomeThing.getHandler();
                    if (handler != null) {
                        if (!espHomeHandlers.contains(handler)) {
                            handler.subscribeBLEAdvertisements(this);
                            espHomeHandlers.add(handler);
                        }

                    }

                }
            }
        }

        if (espHomeHandlers.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE,
                    "Found no ESPHome devices configured for Bluetooth proxy support. Make sure your ESPHome things are online and have the 'enableBluetoothProxy' option set to 'true'");

        } else {
            updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE, String
                    .format("Found %d ESPHome devices configured for Bluetooth proxy support", espHomeHandlers.size()));
        }
        logger.debug("List of {} ESPHome devices: {}", espHomeHandlers.size(),
                espHomeHandlers.stream().map(e -> e.getThing().getUID()).toList());
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
    }

    @Override
    protected ESPHomeBluetoothDevice createDevice(BluetoothAddress address) {
        return new ESPHomeBluetoothDevice(this, address);
    }

    public void handleBluetoothMessage(@NonNull GeneratedMessage message, ESPHomeHandler handler) {

        // Update RSSi list

        if (message instanceof BluetoothLEAdvertisementResponse advertisementResponse) {
            updateDeviceLocation(advertisementResponse.getAddress(), advertisementResponse.getRssi(), handler);
            handleAdvertisement(advertisementResponse, handler);
        } else if (message instanceof BluetoothLERawAdvertisementsResponse rawAdvertisementsResponse) {
            rawAdvertisementsResponse.getAdvertisementsList().forEach(advertisement -> {
                updateDeviceLocation(advertisement.getAddress(), advertisement.getRssi(), handler);
            });
            handleRawAdvertisement(rawAdvertisementsResponse, handler);
        } else if (message instanceof BluetoothDeviceConnectionResponse rsp) {
            ESPHomeBluetoothDevice device = connectionMap.get(BluetoothAddressUtil.createAddress(rsp.getAddress()));
            if (device != null) {
                device.handleConnectionsMessage(rsp);
            }
        } else if (message instanceof BluetoothGATTGetServicesResponse rsp) {
            ESPHomeBluetoothDevice device = connectionMap.get(BluetoothAddressUtil.createAddress(rsp.getAddress()));
            if (device != null) {
                device.handleGattServicesMessage(rsp);
            }
        } else if (message instanceof BluetoothGATTGetServicesDoneResponse rsp) {
            ESPHomeBluetoothDevice device = connectionMap.get(BluetoothAddressUtil.createAddress(rsp.getAddress()));
            if (device != null) {
                device.handleGattServicesDoneMessage(rsp);
            }
        } else if (message instanceof BluetoothGATTReadResponse rsp) {
            ESPHomeBluetoothDevice device = connectionMap.get(BluetoothAddressUtil.createAddress(rsp.getAddress()));
            if (device != null) {
                device.handleGattReadResponse(rsp);
            }
        } else if (message instanceof BluetoothGATTWriteResponse rsp) {
            ESPHomeBluetoothDevice device = connectionMap.get(BluetoothAddressUtil.createAddress(rsp.getAddress()));
            if (device != null) {
                device.handleGattWriteResponse(rsp);
            }
        } else if (message instanceof BluetoothGATTNotifyResponse rsp) {
            ESPHomeBluetoothDevice device = connectionMap.get(BluetoothAddressUtil.createAddress(rsp.getAddress()));
            if (device != null) {
                device.handleGattNotifyResponse(rsp);
            }
        } else if (message instanceof BluetoothGATTNotifyDataResponse rsp) {
            ESPHomeBluetoothDevice device = connectionMap.get(BluetoothAddressUtil.createAddress(rsp.getAddress()));
            if (device != null) {
                device.handleGattNotifyDataResponse(rsp);
            }
        } else if (message instanceof BluetoothGATTErrorResponse rsp) {
            ESPHomeBluetoothDevice device = connectionMap.get(BluetoothAddressUtil.createAddress(rsp.getAddress()));
            if (device != null) {
                device.handleGattErrorResponse(rsp);
            }
        } else if (message instanceof BluetoothConnectionsFreeResponse rsp) {
            freeConnectionsMap.put(handler.getThing().getUID(), rsp.getFree());
        } else if (message instanceof BluetoothScannerStateResponse rsp) {
            logger.debug("Received BluetoothScannerStateResponse from {} with status {}, currently ignored",
                    handler.getThing().getUID(), rsp.getState());
        } else {
            logger.warn("Received unhandled Bluetooth packet type: {} from {}", message.getClass().getSimpleName(),
                    handler.getThing().getUID());
        }
    }

    // Now legacy after ESPHome 2025.9 (approx)
    private void handleAdvertisement(BluetoothLEAdvertisementResponse rsp, ESPHomeHandler handler) {
        try {
            Optional<BluetoothLEAdvertisementResponse> cachedAdvertisement = singleAdvertisementPerPacketCache
                    .get(rsp.getAddress());
            if (cachedAdvertisement.isPresent() && equalsExceptRssi(rsp, cachedAdvertisement.get())) {
                logger.debug("Received duplicate BLE advertisement from device {} via {}", rsp.getAddress(),
                        handler.getThing().getUID());
                return;
            } else {
                singleAdvertisementPerPacketCache.put(rsp.getAddress(), Optional.of(rsp));
            }
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }

        try {
            BluetoothAddress address = BluetoothAddressUtil.createAddress(rsp.getAddress());
            ESPHomeBluetoothDevice device = getDevice(address);

            logger.debug("Received BLE advertisement from device {} via {}", address, handler.getThing().getUID());
            device.setAddressType(rsp.getAddressType());
            device.setName(rsp.getName().toStringUtf8());
            device.setRssi(rsp.getRssi());

            rsp.getManufacturerDataList().stream().findFirst().ifPresent(manufacturerData -> {
                String uuid = manufacturerData.getUuid();
                int manufacturerId = parseManufacturerIdToInt(uuid);
                device.setManufacturerId(manufacturerId);
            });

            deviceDiscovered(device);

            device.handleAdvertisementPacket(rsp);

        } catch (Exception e) {
            logger.warn("Error handling BLE advertisement", e);
        }
    }

    private void handleRawAdvertisement(BluetoothLERawAdvertisementsResponse rsp, ESPHomeHandler handler) {
        rsp.getAdvertisementsList().forEach(advertisement -> {
            try {
                Optional<BluetoothLERawAdvertisement> cachedAdvertisement = multipleAdvertisementPerPacketCache
                        .get(advertisement.getAddress());
                if (cachedAdvertisement.isPresent() && equalsExceptRssi(advertisement, cachedAdvertisement.get())) {
                    logger.trace("Received duplicate BLE advertisement from device {} via {}",
                            advertisement.getAddress(), handler.getThing().getUID());
                    return;
                } else {
                    multipleAdvertisementPerPacketCache.put(advertisement.getAddress(), Optional.of(advertisement));
                }
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }

            try {
                BluetoothAddress address = BluetoothAddressUtil.createAddress(advertisement.getAddress());
                ESPHomeBluetoothDevice device = getDevice(address);

                logger.debug("Received BLE advertisement from device {}/{} via {}", address, advertisement.getAddress(),
                        handler.getThing().getUID());
                device.setAddressType(advertisement.getAddressType());
                device.setRssi(advertisement.getRssi());

                List<ADStructure> advertisementStructures = ADPayloadParser.getInstance()
                        .parse(advertisement.getData().toByteArray());

                for (ADStructure structure : advertisementStructures) {
                    if (structure instanceof LocalName part) {
                        device.setName(part.getLocalName());
                    } else if (structure instanceof ADManufacturerSpecific part) {
                        device.setManufacturerId(part.getCompanyId());
                    }
                }

                deviceDiscovered(device);

                device.handleAdvertisementPacket(advertisement, advertisementStructures);

            } catch (Exception e) {
                logger.warn("Error handling BLE advertisement", e);
            }

        });
    }

    @Nullable
    public ESPHomeHandler getNearestESPHomeDevice(long address) {
        SortedSet<DeviceAndRSSI> deviceAndRSSIS = knownDevices.get(address);
        if (deviceAndRSSIS == null || deviceAndRSSIS.isEmpty()) {
            return null;
        }

        for (DeviceAndRSSI deviceAndRSSI : deviceAndRSSIS) {
            ThingUID deviceUid = deviceAndRSSI.device;
            Integer freeConnections = freeConnectionsMap.get(deviceUid);
            if (freeConnections == null || freeConnections > 0) {
                @Nullable
                Thing esphomeThing = thingRegistry.get(deviceUid);
                if (esphomeThing != null) {
                    return (ESPHomeHandler) esphomeThing.getHandler();
                }
            } else {
                logger.debug("Skipping ESPHome device {} because it has no free Bluetooth connection slots", deviceUid);
            }
        }
        return null;
    }

    private void updateDeviceLocation(long address, int rssi, ESPHomeHandler handler) {
        SortedSet<DeviceAndRSSI> deviceAndRSSIS = knownDevices.computeIfAbsent(address,
                k -> new ConcurrentSkipListSet<>());
        deviceAndRSSIS.removeIf(e -> e.device.equals(handler.getThing().getUID())); // Remove previous entry for this
        // esphome device
        deviceAndRSSIS.add(new DeviceAndRSSI(handler.getThing().getUID(), rssi, Instant.now()));
    }

    private boolean equalsExceptRssi(BluetoothLEAdvertisementResponse rsp1, BluetoothLEAdvertisementResponse rsp2) {
        return rsp1.getAddress() == rsp2.getAddress() && rsp1.getName().equals(rsp2.getName())
                && rsp1.getManufacturerDataList().equals(rsp2.getManufacturerDataList())
                && rsp1.getServiceDataList().equals(rsp2.getServiceDataList())
                && rsp1.getServiceUuidsList().equals(rsp2.getServiceUuidsList())
                && rsp1.getAddressType() == rsp2.getAddressType();
    }

    private boolean equalsExceptRssi(BluetoothLERawAdvertisement rsp1, BluetoothLERawAdvertisement rsp2) {
        return rsp1.getAddress() == rsp2.getAddress() && rsp1.getData().equals(rsp2.getData())
                && rsp1.getAddressType() == rsp2.getAddressType();
    }

    private int parseManufacturerIdToInt(String uuid) {
        byte[] bytes = HexFormat.of().parseHex(uuid.substring(2));
        int manufacturerId = (bytes[0] & 0xFF) << 8 | (bytes[1] & 0xFF);
        logger.debug("Manufacturer UUID: {} -> {}", uuid, manufacturerId);
        return manufacturerId;
    }

    @Override
    public @Nullable BluetoothAddress getAddress() {
        return null;
    }

    public void linkDevice(ESPHomeBluetoothDevice espHomeBluetoothDevice) {
        connectionMap.put(espHomeBluetoothDevice.getAddress(), espHomeBluetoothDevice);
    }

    public void unlinkDevice(ESPHomeBluetoothDevice espHomeBluetoothDevice) {
        connectionMap.remove(espHomeBluetoothDevice.getAddress());
    }

    public void disconnect(ESPHomeHandler espHomeHandler) {
        connectionMap.values().stream().filter(e -> e.getEspHomeHandler() == espHomeHandler).forEach(device -> {
            device.disconnect();
            unlinkDevice(device);
        });
        updateESPHomeDeviceList();
    }

    private record DeviceAndRSSI(ThingUID device, int rssi, Instant lastSeen) implements Comparable<DeviceAndRSSI> {

        @Override
        public int compareTo(DeviceAndRSSI o) {
            return Integer.compare(o.rssi, rssi);
        }

        @Override
        public String toString() {
            return "DeviceAndRSSI{" + "thing=" + device + ", rssi=" + rssi + ", lastSeen=" + lastSeen + '}';
        }
    }

    private void dumpDeviceList() {
        if (logger.isDebugEnabled()) {
            knownDevices.forEach((address, deviceAndRSSIS) -> {
                logger.debug("Bluetooth device {} seen by :{}", BluetoothAddressUtil.createAddress(address),
                        deviceAndRSSIS);
            });
        }
    }
}
