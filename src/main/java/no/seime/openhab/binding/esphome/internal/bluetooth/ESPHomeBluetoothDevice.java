package no.seime.openhab.binding.esphome.internal.bluetooth;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.*;
import org.openhab.binding.bluetooth.notification.BluetoothConnectionStatusNotification;
import org.openhab.binding.bluetooth.notification.BluetoothScanNotification;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.util.HexUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;
import com.neovisionaries.bluetooth.ble.advertising.ADManufacturerSpecific;
import com.neovisionaries.bluetooth.ble.advertising.ADStructure;
import com.neovisionaries.bluetooth.ble.advertising.ServiceData;

import io.esphome.api.*;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

@NonNullByDefault
public class ESPHomeBluetoothDevice extends BaseBluetoothDevice {
    private static final long BLUETOOTH_BASE_UUID_MSB = 0x0000000000001000L;
    private static final long BLUETOOTH_BASE_UUID_LSB = 0x800000805F9B34FBL;

    private final Logger logger = LoggerFactory.getLogger(ESPHomeBluetoothDevice.class);

    @Nullable
    private ESPHomeHandler lockToHandler;

    private final ESPHomeBluetoothProxyHandler proxyHandler;

    private int addressType;

    // Pending GATT operations keyed by handle. Only a single in-flight read/write per handle is
    // supported by the OpenHAB abstraction, so a plain map is enough.
    private final Map<Integer, CompletableFuture<byte[]>> pendingReads = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<@Nullable Void>> pendingWrites = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<@Nullable Void>> pendingNotifyToggles = new ConcurrentHashMap<>();
    private static final UUID CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = UUID
            .fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final java.util.Set<Integer> notifyingHandles = ConcurrentHashMap.newKeySet();

    /**
     * How long to wait for the ESP to confirm a connect or disconnect. ESPHome gives up on a BLE connect itself after
     * ~20 s (reporting e.g. error 133), so this only fires when the ESP does not answer at all.
     */
    static final long DEFAULT_STATE_TIMEOUT_MILLIS = 30_000;
    private @Nullable ScheduledFuture<?> stateTimeout;
    // Bumped on every state transition so a timeout that raced with a response becomes a no-op.
    private final AtomicInteger stateGeneration = new AtomicInteger();

    public ESPHomeBluetoothDevice(BluetoothAdapter adapter, BluetoothAddress address) {
        super(adapter, address);
        proxyHandler = (ESPHomeBluetoothProxyHandler) adapter;
    }

    public void setAddressType(int addressType) {
        this.addressType = addressType;
    }

    public int getAddressType() {
        return addressType;
    }

    public long getAddressAsLong() {
        return BluetoothAddressUtil.convertAddressToLong(address);
    }

    public void handleAdvertisementPacket(BluetoothLEAdvertisementResponse packet) {

        BluetoothScanNotification notification = new BluetoothScanNotification();
        packet.getServiceDataList().forEach(serviceData -> notification.getServiceData()
                .put(to128BitUUID(serviceData.getUuid()), serviceData.getData().toByteArray()));
        notification.setBeaconType(BluetoothScanNotification.BluetoothBeaconType.BEACON_ADVERTISEMENT);
        packet.getManufacturerDataList().stream().findFirst().ifPresent(
                manufacturerData -> notification.setManufacturerData(manufacturerData.getData().toByteArray()));
        notification.setRssi(packet.getRssi());
        notification.setDeviceName(packet.getName().toStringUtf8());

        notifyListeners(BluetoothEventType.SCAN_RECORD, notification);
    }

    public void handleAdvertisementPacket(BluetoothLERawAdvertisement advertisement,
            List<ADStructure> advertisementStructures) {

        BluetoothScanNotification notification = new BluetoothScanNotification();
        advertisementStructures.forEach(structure -> {
            if (structure instanceof ADManufacturerSpecific manufacturerSpecific) {
                notification.setManufacturerData(manufacturerSpecific.getData());
            } else if (structure instanceof ServiceData serviceData) {
                // UUID 2 bytes included in serviceData.getData(), trim away
                byte[] dataIncludingUUID = serviceData.getData();
                byte[] dataExcludingUUID = Arrays.copyOfRange(dataIncludingUUID, 2, dataIncludingUUID.length);
                notification.getServiceData().put(serviceData.getServiceUUID().toString(), dataExcludingUUID);
            }
        });

        notification.setData(advertisement.getData().toByteArray());
        notification.setRssi(advertisement.getRssi());
        notification.setBeaconType(BluetoothScanNotification.BluetoothBeaconType.BEACON_ADVERTISEMENT);
        notifyListeners(BluetoothEventType.SCAN_RECORD, notification);
    }

    public void handleConnectionsMessage(BluetoothDeviceConnectionResponse rsp) {
        boolean connected = rsp.getConnected();
        int error = rsp.getError();
        logger.debug("[{}] Connection state changed: connected={}, error={}, mtu={}", address, connected, error,
                rsp.getMtu());

        cancelStateTimeout();
        String espDevice = describeESPHomeDevice();
        if (connected) {
            logger.info("Bluetooth thing {} connected to {} via ESPHome device {} (mtu={})", describeThings(), address,
                    espDevice, rsp.getMtu());
            connectionState = ConnectionState.CONNECTED;
            notifyListeners(BluetoothEventType.CONNECTION_STATE,
                    new BluetoothConnectionStatusNotification(ConnectionState.CONNECTED));
        } else {
            logger.info("Bluetooth thing {} disconnected from {} via ESPHome device {}{}", describeThings(), address,
                    espDevice, error != 0 ? " (esphome error=" + error + ")" : "");
            releaseConnection(new BluetoothException(
                    "Device disconnected" + (error != 0 ? " (esphome error=" + error + ")" : "")));
        }
    }

    /**
     * Drops the binding to the current ESP and reports DISCONNECTED, so a later connect() may pick any ESP.
     */
    private void releaseConnection(Throwable cause) {
        connectionState = ConnectionState.DISCONNECTED;
        failAllPending(cause);
        // Keep supportedServices populated across reconnects — the peripheral's GATT structure is
        // stable and BaseBluetoothDevice.servicesDiscovered is a private flag we cannot reset, so
        // clearing services here would make the child handler unable to look up characteristics on
        // subsequent reads. Notification subscriptions on the other hand are lost with the BLE
        // connection and must be re-enabled after reconnect.
        notifyingHandles.clear();
        proxyHandler.unlinkDevice(this);
        lockToHandler = null;
        notifyListeners(BluetoothEventType.CONNECTION_STATE,
                new BluetoothConnectionStatusNotification(ConnectionState.DISCONNECTED));
    }

    private void startStateTimeout(ConnectionState pendingState, ESPHomeHandler handler) {
        cancelStateTimeout();
        int generation = stateGeneration.get();
        stateTimeout = proxyHandler.getScheduler().schedule(() -> onStateTimeout(generation, pendingState, handler),
                DEFAULT_STATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void cancelStateTimeout() {
        stateGeneration.incrementAndGet();
        ScheduledFuture<?> timeout = stateTimeout;
        stateTimeout = null;
        if (timeout != null) {
            timeout.cancel(false);
        }
    }

    private void onStateTimeout(int generation, ConnectionState pendingState, ESPHomeHandler handler) {
        if (generation != stateGeneration.get() || connectionState != pendingState || lockToHandler != handler) {
            return; // The ESP answered, or the device moved on, before the timeout fired
        }
        logger.warn(
                "Bluetooth thing {}: ESPHome device {} did not confirm {} {} within {} ms, releasing the connection",
                describeThings(), handler.getThing().getUID(),
                pendingState == ConnectionState.CONNECTING ? "connect to" : "disconnect from", address,
                DEFAULT_STATE_TIMEOUT_MILLIS);
        stateGeneration.incrementAndGet();
        stateTimeout = null;
        // Best effort: ask the ESP to drop whatever it may still hold, as we stop tracking its answers below.
        handler.sendBluetoothCommand(BluetoothDeviceRequest.newBuilder().setAddress(getAddressAsLong())
                .setRequestType(BluetoothDeviceRequestType.BLUETOOTH_DEVICE_REQUEST_TYPE_DISCONNECT).build());
        releaseConnection(new BluetoothException("ESPHome device did not respond to " + pendingState));
    }

    public void handleGattServicesMessage(BluetoothGATTGetServicesResponse rsp) {
        for (BluetoothGATTService serviceProto : rsp.getServicesList()) {
            UUID serviceUuid = uuidFromProto(serviceProto.getShortUuid(), serviceProto.getUuidList());
            int handleStart = serviceProto.getHandle();
            int handleEnd = handleStart;
            for (int i = 0; i < serviceProto.getCharacteristicsCount(); i++) {
                BluetoothGATTCharacteristic characteristicProto = serviceProto.getCharacteristics(i);
                handleEnd = Math.max(handleEnd, characteristicProto.getHandle());
                for (int j = 0; j < characteristicProto.getDescriptorsCount(); j++) {
                    handleEnd = Math.max(handleEnd, characteristicProto.getDescriptors(j).getHandle());
                }
            }

            BluetoothService ohService = new BluetoothService(serviceUuid, true, handleStart, handleEnd);
            for (int i = 0; i < serviceProto.getCharacteristicsCount(); i++) {
                BluetoothGATTCharacteristic characteristicProto = serviceProto.getCharacteristics(i);
                UUID charUuid = uuidFromProto(characteristicProto.getShortUuid(), characteristicProto.getUuidList());
                BluetoothCharacteristic ohCharacteristic = new BluetoothCharacteristic(charUuid,
                        characteristicProto.getHandle());
                ohCharacteristic.setProperties(characteristicProto.getProperties());

                for (int j = 0; j < characteristicProto.getDescriptorsCount(); j++) {
                    BluetoothGATTDescriptor descriptorProto = characteristicProto.getDescriptors(j);
                    UUID descUuid = uuidFromProto(descriptorProto.getShortUuid(), descriptorProto.getUuidList());
                    ohCharacteristic.addDescriptor(
                            new BluetoothDescriptor(ohCharacteristic, descUuid, descriptorProto.getHandle()));
                }

                ohService.addCharacteristic(ohCharacteristic);
            }
            addService(ohService);
        }
    }

    public void handleGattServicesDoneMessage(BluetoothGATTGetServicesDoneResponse rsp) {
        notifyListeners(BluetoothEventType.SERVICES_DISCOVERED);
    }

    public void handleReadResponse(BluetoothGATTReadResponse rsp) {
        CompletableFuture<byte[]> future = pendingReads.remove(rsp.getHandle());
        if (future != null) {
            future.complete(rsp.getData().toByteArray());
        } else {
            logger.debug("[{}] Received read response for unknown handle {}", address, rsp.getHandle());
        }
    }

    public void handleWriteResponse(BluetoothGATTWriteResponse rsp) {
        CompletableFuture<@Nullable Void> future = pendingWrites.remove(rsp.getHandle());
        if (future != null) {
            future.complete(null);
        } else {
            logger.debug("[{}] Received write response for unknown handle {}", address, rsp.getHandle());
        }
    }

    public void handleNotifyResponse(BluetoothGATTNotifyResponse rsp) {
        CompletableFuture<@Nullable Void> future = pendingNotifyToggles.remove(rsp.getHandle());
        if (future != null) {
            future.complete(null);
        } else {
            logger.debug("[{}] Received notify response for unknown handle {}", address, rsp.getHandle());
        }
    }

    public void handleNotifyDataResponse(BluetoothGATTNotifyDataResponse rsp) {
        BluetoothCharacteristic characteristic = getCharacteristicByHandle(rsp.getHandle());
        if (characteristic != null) {
            notifyListeners(BluetoothEventType.CHARACTERISTIC_UPDATED, characteristic, rsp.getData().toByteArray());
        } else {
            logger.debug("[{}] Received notify data for unknown handle {}", address, rsp.getHandle());
        }
    }

    public void handleErrorResponse(BluetoothGATTErrorResponse rsp) {
        int handle = rsp.getHandle();
        BluetoothException error = new BluetoothException("GATT error " + rsp.getError() + " on handle " + handle);
        CompletableFuture<byte[]> read = pendingReads.remove(handle);
        if (read != null) {
            read.completeExceptionally(error);
            return;
        }
        CompletableFuture<@Nullable Void> write = pendingWrites.remove(handle);
        if (write != null) {
            write.completeExceptionally(error);
            return;
        }
        CompletableFuture<@Nullable Void> notify = pendingNotifyToggles.remove(handle);
        if (notify != null) {
            notify.completeExceptionally(error);
            return;
        }
        logger.debug("[{}] Received GATT error {} on handle {} but no pending operation matched", address,
                rsp.getError(), handle);
    }

    /**
     * Called by the proxy handler when the ESP device this BLE device is currently bound to becomes
     * unavailable. Fail pending futures and notify listeners so the OpenHAB device handler can react.
     */
    public void handleESPHomeHandlerLost() {
        if (lockToHandler == null) {
            return;
        }
        logger.info("[{}] ESPHome proxy handler went offline while device was connected", address);
        cancelStateTimeout();
        releaseConnection(new BluetoothException("ESPHome proxy device went offline"));
    }

    /**
     * UIDs of the openHAB things listening to this BLE device, for logging.
     */
    private String describeThings() {
        String things = getListeners().stream().filter(ThingHandler.class::isInstance)
                .map(l -> ((ThingHandler) l).getThing().getUID().toString()).collect(Collectors.joining(", "));
        return things.isEmpty() ? "unknown thing" : things;
    }

    private String describeESPHomeDevice() {
        ESPHomeHandler handler = lockToHandler;
        return handler != null ? handler.getThing().getUID().toString() : "unknown";
    }

    private void failAllPending(Throwable throwable) {
        pendingReads.values().forEach(f -> f.completeExceptionally(throwable));
        pendingReads.clear();
        pendingWrites.values().forEach(f -> f.completeExceptionally(throwable));
        pendingWrites.clear();
        pendingNotifyToggles.values().forEach(f -> f.completeExceptionally(throwable));
        pendingNotifyToggles.clear();
    }

    private String to128BitUUID(String UUID16bit) {
        String uuid = "0000" + UUID16bit.substring(2) + "-0000-1000-8000-00805F9B34FB"; // Trim 0x
        return uuid.toLowerCase();
    }

    static UUID to128BitUUID(long shortUuid) {
        long mostSigBits = ((shortUuid & 0xFFFFFFFFL) << 32) | BLUETOOTH_BASE_UUID_MSB;
        return new UUID(mostSigBits, BLUETOOTH_BASE_UUID_LSB);
    }

    static UUID uuidFromProto(int shortUuid, List<Long> uuidParts) {
        if (shortUuid != 0) {
            return to128BitUUID(shortUuid & 0xFFFFFFFFL);
        }
        if (uuidParts.size() >= 2) {
            return new UUID(uuidParts.get(0), uuidParts.get(1));
        }
        // Fallback - shouldn't happen but avoid NPE
        return to128BitUUID(0L);
    }

    @Override
    public boolean connect() {
        // Refuse while still bound to an ESP (connecting, connected or disconnecting). Starting a new connection
        // via another ESP would leave the old BLE link open, and the peripheral rejects the second connection.
        // The binding is released when the ESP reports the disconnect or the ESP itself goes offline.
        ESPHomeHandler currentHandler = lockToHandler;
        if (currentHandler != null) {
            logger.debug("[{}] connect() called while {} via ESPHome proxy {}", address, connectionState,
                    currentHandler.getThing().getUID());
            return false;
        }

        // Select the nearest ESP once, at connect time, and lock the connection to it so that
        // subsequent RSSI changes on other ESPs do not switch the proxy mid-session.
        ESPHomeHandler nearestESPHomeDevice = proxyHandler.getNearestESPHomeDevice(getAddressAsLong());
        if (nearestESPHomeDevice == null) {
            logger.warn("[{}] No ESPHome proxy device available for connect", address);
            return false;
        }

        lockToHandler = nearestESPHomeDevice;
        proxyHandler.linkDevice(this, nearestESPHomeDevice);

        logger.info("Bluetooth thing {} connecting to {} via ESPHome device {}", describeThings(), address,
                nearestESPHomeDevice.getThing().getUID());
        connectionState = ConnectionState.CONNECTING;
        // Armed before sending, so an immediate answer from the ESP cancels it.
        startStateTimeout(ConnectionState.CONNECTING, nearestESPHomeDevice);
        notifyListeners(BluetoothEventType.CONNECTION_STATE,
                new BluetoothConnectionStatusNotification(ConnectionState.CONNECTING));

        nearestESPHomeDevice.sendBluetoothCommand(BluetoothDeviceRequest.newBuilder().setAddress(getAddressAsLong())
                .setAddressType(addressType).setHasAddressType(true)
                .setRequestType(BluetoothDeviceRequestType.BLUETOOTH_DEVICE_REQUEST_TYPE_CONNECT_V3_WITHOUT_CACHE)
                .build());

        return true;
    }

    @Override
    public boolean disconnect() {
        ESPHomeHandler handler = lockToHandler;
        if (handler == null) {
            return false;
        }
        logger.debug("[{}] Disconnecting via ESPHome proxy {}", address, handler.getThing().getUID());
        connectionState = ConnectionState.DISCONNECTING;
        startStateTimeout(ConnectionState.DISCONNECTING, handler);
        handler.sendBluetoothCommand(BluetoothDeviceRequest.newBuilder().setAddress(getAddressAsLong())
                .setRequestType(BluetoothDeviceRequestType.BLUETOOTH_DEVICE_REQUEST_TYPE_DISCONNECT).build());
        return true;
    }

    @Override
    public boolean discoverServices() {
        ESPHomeHandler handler = lockToHandler;
        if (handler == null) {
            logger.debug("[{}] discoverServices() called with no proxy handler", address);
            return false;
        }
        // Service discovery happens on an established connection, so the connection state stays CONNECTED.
        // ConnectionState.DISCOVERING means "device not yet discovered / not available" in the openHAB API;
        // reporting it here made handlers (e.g. Airthings) treat the device as disconnected and reconnect.
        handler.sendBluetoothCommand(
                BluetoothGATTGetServicesRequest.newBuilder().setAddress(getAddressAsLong()).build());
        return true;
    }

    @Override
    public CompletableFuture<byte[]> readCharacteristic(BluetoothCharacteristic characteristic) {
        ESPHomeHandler handler = lockToHandler;
        if (handler == null) {
            return CompletableFuture.failedFuture(new BluetoothException("Not connected"));
        }
        int handle = characteristic.getHandle();
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        CompletableFuture<byte[]> existing = pendingReads.putIfAbsent(handle, future);
        if (existing != null) {
            return CompletableFuture
                    .failedFuture(new BluetoothException("Read already in progress for handle " + handle));
        }
        handler.sendBluetoothCommand(
                BluetoothGATTReadRequest.newBuilder().setAddress(getAddressAsLong()).setHandle(handle).build());
        return future;
    }

    @Override
    public CompletableFuture<@Nullable Void> writeCharacteristic(BluetoothCharacteristic characteristic, byte[] value) {
        ESPHomeHandler handler = lockToHandler;
        if (handler == null) {
            return CompletableFuture.failedFuture(new BluetoothException("Not connected"));
        }
        int handle = characteristic.getHandle();
        // Prefer write-with-response when the characteristic supports it (more reliable). Only fall back
        // to write-without-response when that is the only supported mode.
        boolean supportsWithResponse = characteristic.hasPropertyEnabled(BluetoothCharacteristic.PROPERTY_WRITE);
        boolean withResponse = supportsWithResponse
                || !characteristic.hasPropertyEnabled(BluetoothCharacteristic.PROPERTY_WRITE_NO_RESPONSE);
        BluetoothGATTWriteRequest request = BluetoothGATTWriteRequest.newBuilder().setAddress(getAddressAsLong())
                .setHandle(handle).setResponse(withResponse).setData(ByteString.copyFrom(value)).build();

        if (!withResponse) {
            handler.sendBluetoothCommand(request);
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<@Nullable Void> future = new CompletableFuture<>();
        CompletableFuture<@Nullable Void> existing = pendingWrites.putIfAbsent(handle, future);
        if (existing != null) {
            return CompletableFuture
                    .failedFuture(new BluetoothException("Write already in progress for handle " + handle));
        }
        handler.sendBluetoothCommand(request);
        return future;
    }

    @Override
    public boolean isNotifying(BluetoothCharacteristic characteristic) {
        return notifyingHandles.contains(characteristic.getHandle());
    }

    @Override
    public CompletableFuture<@Nullable Void> enableNotifications(BluetoothCharacteristic characteristic) {
        return toggleNotifications(characteristic, true);
    }

    @Override
    public CompletableFuture<@Nullable Void> disableNotifications(BluetoothCharacteristic characteristic) {
        return toggleNotifications(characteristic, false);
    }

    private CompletableFuture<@Nullable Void> toggleNotifications(BluetoothCharacteristic characteristic,
            boolean enable) {
        ESPHomeHandler handler = lockToHandler;
        if (handler == null) {
            return CompletableFuture.failedFuture(new BluetoothException("Not connected"));
        }
        int handle = characteristic.getHandle();
        if (enable && notifyingHandles.contains(handle)) {
            return CompletableFuture.completedFuture(null);
        }
        if (!enable && !notifyingHandles.contains(handle)) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<@Nullable Void> notifyRegistered = new CompletableFuture<>();
        CompletableFuture<@Nullable Void> existing = pendingNotifyToggles.putIfAbsent(handle, notifyRegistered);
        if (existing != null) {
            return CompletableFuture.failedFuture(
                    new BluetoothException("Notification toggle already in progress for handle " + handle));
        }
        // With v3 proxy connections the proxy only registers for notifications locally; the client must write the
        // Client Characteristic Configuration Descriptor itself (as bleak-esphome does), otherwise the peripheral
        // never sends notifications.
        CompletableFuture<@Nullable Void> future = notifyRegistered
                .thenCompose(v -> writeClientCharacteristicConfiguration(handler, characteristic, enable));
        future.whenComplete((v, ex) -> {
            if (ex == null) {
                if (enable) {
                    notifyingHandles.add(handle);
                } else {
                    notifyingHandles.remove(handle);
                }
            }
        });
        handler.sendBluetoothCommand(BluetoothGATTNotifyRequest.newBuilder().setAddress(getAddressAsLong())
                .setHandle(handle).setEnable(enable).build());
        return future;
    }

    private CompletableFuture<@Nullable Void> writeClientCharacteristicConfiguration(ESPHomeHandler handler,
            BluetoothCharacteristic characteristic, boolean enable) {
        BluetoothDescriptor cccd = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID);
        if (cccd == null) {
            logger.debug("[{}] No CCCD on characteristic {}, relying on the proxy", address, characteristic.getUuid());
            return CompletableFuture.completedFuture(null);
        }
        byte[] value;
        if (!enable) {
            value = new byte[] { 0x00, 0x00 };
        } else if (characteristic.hasPropertyEnabled(BluetoothCharacteristic.PROPERTY_NOTIFY)) {
            value = new byte[] { 0x01, 0x00 };
        } else {
            value = new byte[] { 0x02, 0x00 };
        }
        int descriptorHandle = cccd.getHandle();
        CompletableFuture<@Nullable Void> future = new CompletableFuture<>();
        CompletableFuture<@Nullable Void> existing = pendingWrites.putIfAbsent(descriptorHandle, future);
        if (existing != null) {
            return CompletableFuture.failedFuture(
                    new BluetoothException("Write already in progress for descriptor handle " + descriptorHandle));
        }
        logger.debug("[{}] Writing CCCD {} on handle {} for characteristic {}", address, HexUtils.bytesToHex(value),
                descriptorHandle, characteristic.getUuid());
        handler.sendBluetoothCommand(BluetoothGATTWriteDescriptorRequest.newBuilder().setAddress(getAddressAsLong())
                .setHandle(descriptorHandle).setData(ByteString.copyFrom(value)).build());
        return future;
    }

    @Override
    public boolean enableNotifications(BluetoothDescriptor descriptor) {
        return false;
    }

    @Override
    public boolean disableNotifications(BluetoothDescriptor descriptor) {
        return false;
    }
}
