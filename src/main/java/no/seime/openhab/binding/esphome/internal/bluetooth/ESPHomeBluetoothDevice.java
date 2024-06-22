package no.seime.openhab.binding.esphome.internal.bluetooth;

import java.util.concurrent.CompletableFuture;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bluetooth.*;
import org.openhab.binding.bluetooth.notification.BluetoothScanNotification;

import io.esphome.api.BluetoothLEAdvertisementResponse;

@NonNullByDefault
public class ESPHomeBluetoothDevice extends BaseBluetoothDevice {
    /**
     * Construct a Bluetooth device taking the Bluetooth address
     *
     * @param adapter
     * @param address
     */

    public ESPHomeBluetoothDevice(BluetoothAdapter adapter, BluetoothAddress address) {
        super(adapter, address);
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

    private String to128BitUUID(String UUID16bit) {
        String uuid = "0000" + UUID16bit.substring(2) + "-0000-1000-8000-00805F9B34FB"; // Trim 0x
        return uuid.toLowerCase();
    }

    @Override
    public boolean connect() {
        return false;
    }

    @Override
    public boolean disconnect() {
        return false;
    }

    @Override
    public boolean discoverServices() {
        return false;
    }

    @Override
    public CompletableFuture<byte[]> readCharacteristic(BluetoothCharacteristic characteristic) {
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
