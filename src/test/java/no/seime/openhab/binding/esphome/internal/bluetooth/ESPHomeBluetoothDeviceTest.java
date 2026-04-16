package no.seime.openhab.binding.esphome.internal.bluetooth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

public class ESPHomeBluetoothDeviceTest {

    @Test
    void expands16BitShortUuidIntoBluetoothBaseUuid() {
        assertEquals(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"),
                ESPHomeBluetoothDevice.to128BitUUID(0x180D));
    }

    @Test
    void expands32BitShortUuidIntoBluetoothBaseUuid() {
        assertEquals(UUID.fromString("12345678-0000-1000-8000-00805f9b34fb"),
                ESPHomeBluetoothDevice.to128BitUUID(0x12345678L));
    }
}
