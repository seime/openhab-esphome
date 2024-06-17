package no.seime.openhab.binding.esphome.internal.bluetooth;

import org.openhab.binding.bluetooth.*;

import java.util.concurrent.CompletableFuture;

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
        return null;
    }

    @Override
    public CompletableFuture<Void> writeCharacteristic(BluetoothCharacteristic characteristic, byte[] value) {
        return null;
    }

    @Override
    public boolean isNotifying(BluetoothCharacteristic characteristic) {
        return false;
    }

    @Override
    public CompletableFuture<Void> enableNotifications(BluetoothCharacteristic characteristic) {
        return null;
    }

    @Override
    public CompletableFuture<Void> disableNotifications(BluetoothCharacteristic characteristic) {
        return null;
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
