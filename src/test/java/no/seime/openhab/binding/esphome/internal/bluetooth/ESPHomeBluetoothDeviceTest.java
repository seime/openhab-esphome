package no.seime.openhab.binding.esphome.internal.bluetooth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
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

    @Test
    void uuidFromProtoPrefersShortUuidWhenNonZero() {
        assertEquals(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb"),
                ESPHomeBluetoothDevice.uuidFromProto(0x180D, List.of(0L, 0L)));
    }

    @Test
    void uuidFromProtoFallsBackToTwoLongsForFull128BitUuid() {
        long msb = 0x0011223344556677L;
        long lsb = 0x8899AABBCCDDEEFFL;
        assertEquals(new UUID(msb, lsb), ESPHomeBluetoothDevice.uuidFromProto(0, List.of(msb, lsb)));
    }
}
