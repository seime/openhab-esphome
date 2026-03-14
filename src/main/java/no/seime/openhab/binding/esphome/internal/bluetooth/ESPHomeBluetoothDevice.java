package no.seime.openhab.binding.esphome.internal.bluetooth;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.*;
import org.openhab.binding.bluetooth.notification.BluetoothConnectionStatusNotification;
import org.openhab.binding.bluetooth.notification.BluetoothScanNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.neovisionaries.bluetooth.ble.advertising.ADManufacturerSpecific;
import com.neovisionaries.bluetooth.ble.advertising.ADStructure;
import com.neovisionaries.bluetooth.ble.advertising.ServiceData;

import io.esphome.api.*;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

@NonNullByDefault
public class ESPHomeBluetoothDevice extends BaseBluetoothDevice {
    /**
     * Construct a Bluetooth device taking the Bluetooth address
     *
     * @param adapter
     * @param address
     */

    private final Logger logger = LoggerFactory.getLogger(ESPHomeBluetoothDevice.class);

    private final Object stateLock = new Object();

    @Nullable
    private volatile ESPHomeHandler espHomeHandler;

    private final ESPHomeBluetoothProxyHandler proxyHandler;

    // Client Characteristic Configuration
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private volatile boolean connecting;

    private volatile long lastConnectTime;

    public void setAddressType(int addressType) {
        this.addressType = addressType;
    }

    private volatile int addressType;

    public ESPHomeBluetoothDevice(BluetoothAdapter adapter, BluetoothAddress address) {
        super(adapter, address);
        proxyHandler = (ESPHomeBluetoothProxyHandler) adapter;
        connectionState = ConnectionState.DISCONNECTED;
    }

    public void handleAdvertisementPacket(BluetoothLEAdvertisementResponse packet) {
        synchronized (stateLock) {
            if (connectionState == ConnectionState.DISCONNECTED) {
                connectionState = ConnectionState.DISCOVERED;
                notifyListeners(BluetoothEventType.CONNECTION_STATE,
                        new BluetoothConnectionStatusNotification(ConnectionState.DISCOVERED));
            }
        }

        BluetoothScanNotification notification = new BluetoothScanNotification();
        packet.getServiceDataList().stream().forEach(serviceData -> {

            notification.getServiceData().put(to128BitUUID(serviceData.getUuid()), serviceData.getData().toByteArray());
        });
        notification.setBeaconType(BluetoothScanNotification.BluetoothBeaconType.BEACON_ADVERTISEMENT);
        packet.getManufacturerDataList().stream().findFirst().ifPresent(manufacturerData -> {
            notification.setManufacturerData(manufacturerData.getData().toByteArray());
        });
        notification.setRssi(packet.getRssi());
        notification.setDeviceName(packet.getName().toStringUtf8());

        notifyListeners(BluetoothEventType.SCAN_RECORD, notification);
    }

    public void handleAdvertisementPacket(BluetoothLERawAdvertisement advertisement,
            List<ADStructure> advertisementStructures) {
        synchronized (stateLock) {
            if (connectionState == ConnectionState.DISCONNECTED) {
                connectionState = ConnectionState.DISCOVERED;
                notifyListeners(BluetoothEventType.CONNECTION_STATE,
                        new BluetoothConnectionStatusNotification(ConnectionState.DISCOVERED));
            }
        }

        BluetoothScanNotification notification = new BluetoothScanNotification();
        advertisementStructures.stream().forEach(structure -> {
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
        if (!rsp.getConnected()) {
            synchronized (stateLock) {
                if (!connecting && espHomeHandler == null) {
                    logger.debug("Ignoring redundant disconnect message for {}", address);
                    return;
                }
                if (connecting && System.currentTimeMillis() - lastConnectTime < 500) {
                    logger.debug("Ignoring redundant disconnect message for {} received {}ms after connect", address,
                            System.currentTimeMillis() - lastConnectTime);
                    return;
                }
            }
        }

        synchronized (stateLock) {
            connecting = false;
            connectionState = rsp.getConnected() ? ConnectionState.CONNECTED : ConnectionState.DISCONNECTED;
            if (!rsp.getConnected()) {
                logger.debug("Device {} disconnected. Error code: {}", address, rsp.getError());
                proxyHandler.unlinkDevice(this);
                espHomeHandler = null;
            }
        }

        notifyListeners(BluetoothEventType.CONNECTION_STATE, new BluetoothConnectionStatusNotification(
                rsp.getConnected() ? ConnectionState.CONNECTED : ConnectionState.DISCONNECTED));

        if (rsp.getConnected()) {
            discoverServices();
        }

        // Complete any outstanding futures if we disconnected
        if (!rsp.getConnected()) {
            characteristicsByHandle.clear();
            notifyingCharacteristics.clear();
            supportedServices.clear();
            String errorMessage = "Device disconnected";
            readFutures.values().forEach(f -> f.completeExceptionally(new RuntimeException(errorMessage)));
            readFutures.clear();
            writeFutures.values().forEach(f -> f.completeExceptionally(new RuntimeException(errorMessage)));
            writeFutures.clear();
            notifyFutures.values().forEach(f -> f.completeExceptionally(new RuntimeException(errorMessage)));
            notifyFutures.clear();
        }
    }

    @Override
    public ConnectionState getConnectionState() {
        synchronized (stateLock) {
            return connectionState;
        }
    }

    private final ConcurrentHashMap<Integer, CompletableFuture<byte[]>> readFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CompletableFuture<@Nullable Void>> writeFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, CompletableFuture<@Nullable Void>> notifyFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Boolean> requestedNotifyState = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, BluetoothCharacteristic> characteristicsByHandle = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Boolean> notifyingCharacteristics = new ConcurrentHashMap<>();

    public void handleGattServicesMessage(BluetoothGATTGetServicesResponse rsp) {

        for (BluetoothGATTService service : rsp.getServicesList()) {
            int handle = service.getHandle();
            UUID serviceUuid = getUuid(service.getUuidList(), service.getShortUuid());

            BluetoothService ohService = new BluetoothService(serviceUuid, false, handle, handle);
            for (BluetoothGATTCharacteristic characteristic : service.getCharacteristicsList()) {
                int charHandle = characteristic.getHandle();
                UUID charUuid = getUuid(characteristic.getUuidList(), characteristic.getShortUuid());
                BluetoothCharacteristic ohCharacteristic = new BluetoothCharacteristic(charUuid, charHandle);
                ohCharacteristic.setProperties(characteristic.getProperties());
                for (BluetoothGATTDescriptor descriptor : characteristic.getDescriptorsList()) {
                    int descHandle = descriptor.getHandle();
                    UUID descUuid = getUuid(descriptor.getUuidList(), descriptor.getShortUuid());
                    BluetoothDescriptor ohDescriptor = new BluetoothDescriptor(ohCharacteristic, descUuid, descHandle);
                    ohCharacteristic.addDescriptor(ohDescriptor);
                }
                ohService.addCharacteristic(ohCharacteristic);
                characteristicsByHandle.put(charHandle, ohCharacteristic);
            }

            addService(ohService);
        }
    }

    private UUID getUuid(List<Long> uuidList, int shortUuid) {
        if (shortUuid != 0) {
            return UUID.fromString(to128BitUUID(String.format("0x%04X", shortUuid)));
        } else if (uuidList.size() >= 2) {
            return new UUID(uuidList.get(0), uuidList.get(1));
        } else if (uuidList.size() == 1) {
            return new UUID(uuidList.get(0), 0);
        }
        return new UUID(0, 0);
    }

    public void handleGattServicesDoneMessage(BluetoothGATTGetServicesDoneResponse rsp) {
        notifyListeners(BluetoothEventType.SERVICES_DISCOVERED);
    }

    private String to128BitUUID(String UUID16bit) {
        String uuid = "0000" + UUID16bit.substring(2) + "-0000-1000-8000-00805F9B34FB"; // Trim 0x
        return uuid.toLowerCase();
    }

    @Override
    public boolean connect() {
        ESPHomeHandler handlerToSendTo = null;
        BluetoothDeviceRequest request = null;
        synchronized (stateLock) {
            if (connecting || espHomeHandler != null) {
                return true;
            }
            ESPHomeHandler nearestESPHomeDevice = proxyHandler
                    .getNearestESPHomeDevice(BluetoothAddressUtil.convertAddressToLong(address));
            if (nearestESPHomeDevice != null) {
                logger.info("Connecting to ESPHome device with address: {} via ESPHomeDevice {}", address,
                        nearestESPHomeDevice.getDeviceId());
                connecting = true;
                connectionState = ConnectionState.CONNECTING;
                lastConnectTime = System.currentTimeMillis();
                espHomeHandler = nearestESPHomeDevice;
                proxyHandler.linkDevice(this);
                handlerToSendTo = nearestESPHomeDevice;
                request = BluetoothDeviceRequest.newBuilder()
                        .setAddress(BluetoothAddressUtil.convertAddressToLong(address)).setAddressType(addressType)
                        .setHasAddressType(true)
                        .setRequestType(
                                BluetoothDeviceRequestType.BLUETOOTH_DEVICE_REQUEST_TYPE_CONNECT_V3_WITHOUT_CACHE)
                        .build();
            } else {
                return false;
            }
        }

        handlerToSendTo.sendBluetoothCommand(request);
        return true;
    }

    @Override
    public boolean disconnect() {
        ESPHomeHandler handler = espHomeHandler;
        if (handler != null && connectionState == ConnectionState.CONNECTED) {
            // Disconnect from the device

            handler.sendBluetoothCommand(BluetoothDeviceRequest.newBuilder()
                    .setAddress(BluetoothAddressUtil.convertAddressToLong(address))
                    .setRequestType(BluetoothDeviceRequestType.BLUETOOTH_DEVICE_REQUEST_TYPE_DISCONNECT).build());

            notifyListeners(BluetoothEventType.CONNECTION_STATE,
                    new BluetoothConnectionStatusNotification(ConnectionState.DISCONNECTED));
            return true;

        }
        return false;
    }

    @Override
    public boolean discoverServices() {
        ESPHomeHandler handler = espHomeHandler;
        if (handler != null) {
            notifyListeners(BluetoothEventType.CONNECTION_STATE,
                    new BluetoothConnectionStatusNotification(ConnectionState.DISCOVERING));
            handler.sendBluetoothCommand(BluetoothGATTGetServicesRequest.newBuilder()
                    .setAddress(BluetoothAddressUtil.convertAddressToLong(address)).build());
            return true;
        }
        return false;
    }

    @Override
    public CompletableFuture<byte[]> readCharacteristic(BluetoothCharacteristic characteristic) {
        ESPHomeHandler handler = espHomeHandler;
        if (handler == null) {
            return CompletableFuture.failedFuture(new RuntimeException("Not connected to any proxy"));
        }
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        readFutures.put(characteristic.getHandle(), future);

        handler.sendBluetoothCommand(
                BluetoothGATTReadRequest.newBuilder().setAddress(BluetoothAddressUtil.convertAddressToLong(address))
                        .setHandle(characteristic.getHandle()).build());

        return future;
    }

    @Override
    public CompletableFuture<@Nullable Void> writeCharacteristic(BluetoothCharacteristic characteristic, byte[] value) {
        ESPHomeHandler handler = espHomeHandler;
        if (handler == null) {
            return CompletableFuture.failedFuture(new RuntimeException("Not connected to any proxy"));
        }
        CompletableFuture<@Nullable Void> future = new CompletableFuture<>();
        writeFutures.put(characteristic.getHandle(), future);

        handler.sendBluetoothCommand(BluetoothGATTWriteRequest.newBuilder()
                .setAddress(BluetoothAddressUtil.convertAddressToLong(address)).setHandle(characteristic.getHandle())
                .setResponse(true).setData(com.google.protobuf.ByteString.copyFrom(value)).build());

        return future;
    }

    @Override
    public boolean isNotifying(BluetoothCharacteristic characteristic) {
        return notifyingCharacteristics.getOrDefault(characteristic.getHandle(), false);
    }

    public CompletableFuture<byte[]> readDescriptor(BluetoothDescriptor descriptor) {
        ESPHomeHandler handler = espHomeHandler;
        if (handler == null) {
            return CompletableFuture.failedFuture(new RuntimeException("Not connected to any proxy"));
        }
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        readFutures.put(descriptor.getHandle(), future);

        handler.sendBluetoothCommand(BluetoothGATTReadDescriptorRequest.newBuilder()
                .setAddress(BluetoothAddressUtil.convertAddressToLong(address)).setHandle(descriptor.getHandle())
                .build());

        return future;
    }

    public CompletableFuture<@Nullable Void> writeDescriptor(BluetoothDescriptor descriptor, byte[] value) {
        ESPHomeHandler handler = espHomeHandler;
        if (handler == null) {
            return CompletableFuture.failedFuture(new RuntimeException("Not connected to any proxy"));
        }
        CompletableFuture<@Nullable Void> future = new CompletableFuture<>();
        writeFutures.put(descriptor.getHandle(), future);

        handler.sendBluetoothCommand(BluetoothGATTWriteDescriptorRequest.newBuilder()
                .setAddress(BluetoothAddressUtil.convertAddressToLong(address)).setHandle(descriptor.getHandle())
                .setData(com.google.protobuf.ByteString.copyFrom(value)).build());

        return future;
    }

    @Override
    public CompletableFuture<@Nullable Void> enableNotifications(BluetoothCharacteristic characteristic) {
        ESPHomeHandler handler = espHomeHandler;
        if (handler == null) {
            return CompletableFuture.failedFuture(new RuntimeException("Not connected to any proxy"));
        }
        CompletableFuture<@Nullable Void> future = new CompletableFuture<>();
        notifyFutures.put(characteristic.getHandle(), future);
        requestedNotifyState.put(characteristic.getHandle(), true);

        handler.sendBluetoothCommand(
                BluetoothGATTNotifyRequest.newBuilder().setAddress(BluetoothAddressUtil.convertAddressToLong(address))
                        .setHandle(characteristic.getHandle()).setEnable(true).build());

        return future.thenCompose(v -> {
            BluetoothDescriptor cccd = characteristic.getDescriptor(CCCD_UUID);
            if (cccd != null) {
                return writeDescriptor(cccd, new byte[] { 0x01, 0x00 });
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    @Override
    public CompletableFuture<@Nullable Void> disableNotifications(BluetoothCharacteristic characteristic) {
        ESPHomeHandler handler = espHomeHandler;
        if (handler == null) {
            return CompletableFuture.failedFuture(new RuntimeException("Not connected to any proxy"));
        }
        CompletableFuture<@Nullable Void> future = new CompletableFuture<>();
        notifyFutures.put(characteristic.getHandle(), future);
        requestedNotifyState.put(characteristic.getHandle(), false);

        handler.sendBluetoothCommand(
                BluetoothGATTNotifyRequest.newBuilder().setAddress(BluetoothAddressUtil.convertAddressToLong(address))
                        .setHandle(characteristic.getHandle()).setEnable(false).build());

        return future.thenCompose(v -> {
            BluetoothDescriptor cccd = characteristic.getDescriptor(CCCD_UUID);
            if (cccd != null) {
                return writeDescriptor(cccd, new byte[] { 0x00, 0x00 });
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    public void handleGattReadResponse(BluetoothGATTReadResponse rsp) {
        CompletableFuture<byte[]> future = readFutures.remove(rsp.getHandle());
        byte[] data = rsp.getData().toByteArray();
        if (future != null) {
            future.complete(data);
        }
        BluetoothCharacteristic characteristic = characteristicsByHandle.get(rsp.getHandle());
        if (characteristic != null) {
            notifyListeners(BluetoothEventType.CHARACTERISTIC_UPDATED, characteristic, data);
        }
    }

    public void handleGattWriteResponse(BluetoothGATTWriteResponse rsp) {
        CompletableFuture<@Nullable Void> future = writeFutures.remove(rsp.getHandle());
        if (future != null) {
            future.complete(null);
        }
    }

    public void handleGattNotifyResponse(BluetoothGATTNotifyResponse rsp) {
        CompletableFuture<@Nullable Void> future = notifyFutures.remove(rsp.getHandle());
        if (future != null) {
            Boolean enabled = requestedNotifyState.remove(rsp.getHandle());
            if (enabled != null) {
                notifyingCharacteristics.put(rsp.getHandle(), enabled);
            }
            future.complete(null);
        }
    }

    public void handleGattNotifyDataResponse(BluetoothGATTNotifyDataResponse rsp) {
        // This is an unsolicited notification/indication from the device
        // We should notify listeners
        BluetoothCharacteristic characteristic = characteristicsByHandle.get(rsp.getHandle());
        if (characteristic != null) {
            notifyListeners(BluetoothEventType.CHARACTERISTIC_UPDATED, characteristic, rsp.getData().toByteArray());
        }
    }

    public void handleGattErrorResponse(BluetoothGATTErrorResponse rsp) {
        int handle = rsp.getHandle();
        int error = rsp.getError();
        String errorMessage = "GATT Error: " + error;

        CompletableFuture<byte[]> readFuture = readFutures.remove(handle);
        if (readFuture != null) {
            readFuture.completeExceptionally(new RuntimeException(errorMessage));
        }
        CompletableFuture<@Nullable Void> writeFuture = writeFutures.remove(handle);
        if (writeFuture != null) {
            writeFuture.completeExceptionally(new RuntimeException(errorMessage));
        }
        CompletableFuture<@Nullable Void> notifyFuture = notifyFutures.remove(handle);
        if (notifyFuture != null) {
            notifyFuture.completeExceptionally(new RuntimeException(errorMessage));
        }
    }

    @Override
    public boolean enableNotifications(BluetoothDescriptor descriptor) {
        return false;
    }

    @Override
    public boolean disableNotifications(BluetoothDescriptor descriptor) {
        return false;
    }

    public @Nullable ESPHomeHandler getEspHomeHandler() {
        return espHomeHandler;
    }
}
