package no.seime.openhab.binding.esphome.internal.bluetooth;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.*;
import org.openhab.binding.bluetooth.notification.BluetoothConnectionStatusNotification;
import org.openhab.binding.bluetooth.notification.BluetoothScanNotification;

import com.neovisionaries.bluetooth.ble.advertising.ADManufacturerSpecific;
import com.neovisionaries.bluetooth.ble.advertising.ADStructure;
import com.neovisionaries.bluetooth.ble.advertising.ServiceData;

import io.esphome.api.*;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

@NonNullByDefault
public class ESPHomeBluetoothDevice extends BaseBluetoothDevice {
    private static final long BLUETOOTH_BASE_UUID_MSB = 0x0000000000001000L;
    private static final long BLUETOOTH_BASE_UUID_LSB = 0x800000805F9B34FBL;

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
        notification.setDeviceName(packet.getName().toStringUtf8());

        notifyListeners(BluetoothEventType.SCAN_RECORD, notification);
    }

    public void handleAdvertisementPacket(BluetoothLERawAdvertisement advertisement,
            List<ADStructure> advertisementStructures) {

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
            UUID uuid;
            if (service.getShortUuid() != 0) {
                uuid = to128BitUUID(service.getShortUuid());
            } else {
                uuid = new UUID(service.getUuid(0), service.getUuid(1));
            }
            for (int i = 0; i < service.getCharacteristicsCount(); i++) {
                BluetoothGATTCharacteristic characteristics = service.getCharacteristics(i);

                BluetoothService ohService = new BluetoothService(uuid, false, handle, handle); // TODO?
                UUID charUuid;
                if (characteristics.getShortUuid() != 0) {
                    charUuid = to128BitUUID(characteristics.getShortUuid());
                } else {
                    charUuid = new UUID(characteristics.getUuid(0), characteristics.getUuid(1));
                }

                BluetoothCharacteristic ohCharacteristic = new BluetoothCharacteristic(charUuid, handle); // TODO?
                ohService.addCharacteristic(ohCharacteristic);

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

    static UUID to128BitUUID(long shortUuid) {
        long mostSigBits = ((shortUuid & 0xFFFFFFFFL) << 32) | BLUETOOTH_BASE_UUID_MSB;
        return new UUID(mostSigBits, BLUETOOTH_BASE_UUID_LSB);
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
