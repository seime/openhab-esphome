package no.seime.openhab.binding.esphome.internal.bluetooth;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.*;
import org.openhab.binding.bluetooth.notification.BluetoothConnectionStatusNotification;
import org.openhab.binding.bluetooth.notification.BluetoothScanNotification;

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

    @Nullable
    private ESPHomeHandler lockToHandler;

    private final ESPHomeBluetoothProxyHandler proxyHandler;

    public void setAddressType(int addressType) {
        this.addressType = addressType;
    }

    private int addressType;

    public ESPHomeBluetoothDevice(BluetoothAdapter adapter, BluetoothAddress address) {
        super(adapter, address);
        proxyHandler = (ESPHomeBluetoothProxyHandler) adapter;
    }

    public void handleAdvertisementPacket(BluetoothLEAdvertisementResponse packet) {

        BluetoothScanNotification notification = new BluetoothScanNotification();
        packet.getServiceDataList().stream().forEach(serviceData -> {

            notification.getServiceData().put(to128BitUUID(serviceData.getUuid()), serviceData.getData().toByteArray());
        });
        notification.setBeaconType(BluetoothScanNotification.BluetoothBeaconType.BEACON_ADVERTISEMENT);
        packet.getManufacturerDataList().stream().findFirst().ifPresent(manufacturerData -> {
            notification.setManufacturerData(manufacturerData.getData().toByteArray());
        });
        notification.setRssi(packet.getRssi());
        notification.setDeviceName(packet.getName());

        notifyListeners(BluetoothEventType.SCAN_RECORD, notification);
    }

    public void handleConnectionsMessage(BluetoothDeviceConnectionResponse rsp) {
        notifyListeners(BluetoothEventType.CONNECTION_STATE, new BluetoothConnectionStatusNotification(
                rsp.getConnected() ? ConnectionState.CONNECTED : ConnectionState.DISCONNECTED));

        if (!rsp.getConnected()) {
            proxyHandler.unlinkDevice(this);
            lockToHandler = null;
        }
    }

    public void handleGattServicesMessage(BluetoothGATTGetServicesResponse rsp) {

        for (BluetoothGATTService service : rsp.getServicesList()) {
            int handle = service.getHandle();
            for (int i = 0; i < service.getUuidCount(); i++) {
                long uuid = service.getUuid(i);
                BluetoothGATTCharacteristic characteristics = service.getCharacteristics(i);

                BluetoothService ohService = new BluetoothService(new UUID(uuid, 0), false, handle, handle); // TODO?
                for (int j = 0; j < characteristics.getUuidCount(); j++) {
                    long charUuid = characteristics.getUuid(j);
                    BluetoothCharacteristic ohCharacteristic = new BluetoothCharacteristic(new UUID(charUuid, 0),
                            handle); // TODO?
                    ohService.addCharacteristic(ohCharacteristic);
                }

                notifyListeners(BluetoothEventType.SERVICES_DISCOVERED, ohService);
            }
        }
    }

    public void handleGattServicesDoneMessage(BluetoothGATTGetServicesDoneResponse rsp) {
        notifyListeners(BluetoothEventType.CONNECTION_STATE,
                new BluetoothConnectionStatusNotification(ConnectionState.DISCOVERED));
    }

    private String to128BitUUID(String UUID16bit) {
        String uuid = "0000" + UUID16bit.substring(2) + "-0000-1000-8000-00805F9B34FB"; // Trim 0x
        return uuid.toLowerCase();
    }

    @Override
    public boolean connect() {
        ESPHomeHandler nearestESPHomeDevice = proxyHandler
                .getNearestESPHomeDevice(BluetoothAddressUtil.convertAddressToLong(address));
        if (nearestESPHomeDevice != null) {
            lockToHandler = nearestESPHomeDevice;
            proxyHandler.linkDevice(this, nearestESPHomeDevice);

            // Connect to the device
            // notifyListeners(BluetoothEventType.CONNECTION_STATE,
            // new BluetoothConnectionStatusNotification(ConnectionState.CONNECTING));
            lockToHandler.sendBluetoothCommand(BluetoothDeviceRequest.newBuilder()
                    .setAddress(BluetoothAddressUtil.convertAddressToLong(address)).setAddressType(addressType)
                    .setRequestType(BluetoothDeviceRequestType.BLUETOOTH_DEVICE_REQUEST_TYPE_CONNECT_V3_WITHOUT_CACHE)
                    .build());

            return true;
        }

        return true;
    }

    @Override
    public boolean disconnect() {
        if (lockToHandler != null) {
            // Disconnect from the device

            // notifyListeners(BluetoothEventType.CONNECTION_STATE,
            // new BluetoothConnectionStatusNotification(ConnectionState.DISCONNECTING));
            lockToHandler.sendBluetoothCommand(BluetoothDeviceRequest.newBuilder()
                    .setAddress(BluetoothAddressUtil.convertAddressToLong(address))
                    .setRequestType(BluetoothDeviceRequestType.BLUETOOTH_DEVICE_REQUEST_TYPE_DISCONNECT).build());

            return true;

        }
        return false;
    }

    @Override
    public boolean discoverServices() {
        notifyListeners(BluetoothEventType.CONNECTION_STATE,
                new BluetoothConnectionStatusNotification(ConnectionState.DISCOVERING));
        lockToHandler.sendBluetoothCommand(BluetoothGATTGetServicesRequest.newBuilder()
                .setAddress(BluetoothAddressUtil.convertAddressToLong(address)).build());
        return true;
    }

    @Override
    public CompletableFuture<byte[]> readCharacteristic(BluetoothCharacteristic characteristic) {

        lockToHandler.sendBluetoothCommand(
                BluetoothGATTReadRequest.newBuilder().setAddress(BluetoothAddressUtil.convertAddressToLong(address))
                        .setHandle(characteristic.getHandle()).build());

        return CompletableFuture.failedFuture(new RuntimeException("Not implemented"));
    }

    @Override
    public CompletableFuture<Void> writeCharacteristic(BluetoothCharacteristic characteristic, byte[] value) {
        return CompletableFuture.failedFuture(new RuntimeException("Not implemented"));
    }

    @Override
    public boolean isNotifying(BluetoothCharacteristic characteristic) {
        return false;
    }

    @Override
    public CompletableFuture<Void> enableNotifications(BluetoothCharacteristic characteristic) {
        return CompletableFuture.failedFuture(new RuntimeException("Not implemented"));
    }

    @Override
    public CompletableFuture<Void> disableNotifications(BluetoothCharacteristic characteristic) {
        return CompletableFuture.failedFuture(new RuntimeException("Not implemented"));
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
