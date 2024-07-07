package no.seime.openhab.binding.esphome.internal.bluetooth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.openhab.binding.bluetooth.BluetoothAddress;

public class BluetoothAddressUtilTest {

    @Test
    public void testConvertAddress() {
        long address = 0x1234567890FFL;
        String addressString = "12:34:56:78:90:FF";
        BluetoothAddress bluetoothAddress = BluetoothAddressUtil.createAddress(address);
        assertEquals(addressString, bluetoothAddress.toString());
        assertEquals(address, BluetoothAddressUtil.convertAddressToLong(bluetoothAddress));
    }
}
