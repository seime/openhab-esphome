package no.seime.openhab.binding.esphome.internal.bluetooth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.bluetooth.BluetoothAdapter;
import org.openhab.binding.bluetooth.BluetoothAddress;
import org.openhab.binding.bluetooth.BluetoothCharacteristic;
import org.openhab.binding.bluetooth.BluetoothDescriptor;
import org.openhab.binding.bluetooth.BluetoothDevice.ConnectionState;
import org.openhab.binding.bluetooth.BluetoothDeviceListener;
import org.openhab.binding.bluetooth.notification.BluetoothConnectionStatusNotification;
import org.openhab.binding.bluetooth.notification.BluetoothScanNotification;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingUID;

import com.google.protobuf.GeneratedMessage;

import io.esphome.api.BluetoothDeviceConnectionResponse;
import io.esphome.api.BluetoothDeviceRequest;
import io.esphome.api.BluetoothDeviceRequestType;
import io.esphome.api.BluetoothGATTGetServicesDoneResponse;
import io.esphome.api.BluetoothGATTGetServicesRequest;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class ESPHomeBluetoothDeviceTest {

    private ESPHomeBluetoothProxyHandler proxyHandler;
    private ESPHomeHandler espA;
    private ESPHomeHandler espB;
    private ESPHomeBluetoothDevice device;
    private final List<ConnectionState> notifiedStates = new ArrayList<>();
    private boolean servicesDiscovered;

    @BeforeEach
    void setUp() {
        proxyHandler = mock(ESPHomeBluetoothProxyHandler.class);
        espA = mockEspHandler("esphome:device:a");
        espB = mockEspHandler("esphome:device:b");
        device = new ESPHomeBluetoothDevice(proxyHandler, new BluetoothAddress("F4:60:77:6D:66:1A"));
        device.addListener(new RecordingListener());
    }

    private static ESPHomeHandler mockEspHandler(String uid) {
        ESPHomeHandler handler = mock(ESPHomeHandler.class);
        Thing thing = mock(Thing.class);
        when(thing.getUID()).thenReturn(new ThingUID(uid));
        when(handler.getThing()).thenReturn(thing);
        return handler;
    }

    private void connectVia(ESPHomeHandler esp) {
        when(proxyHandler.getNearestESPHomeDevice(anyLong())).thenReturn(esp);
        assertTrue(device.connect());
        device.handleConnectionsMessage(BluetoothDeviceConnectionResponse.newBuilder()
                .setAddress(device.getAddressAsLong()).setConnected(true).setMtu(247).build());
    }

    @Test
    void staysConnectedDuringServiceDiscovery() {
        connectVia(espA);
        assertEquals(ConnectionState.CONNECTED, device.getConnectionState());

        assertTrue(device.discoverServices());
        verify(espA).sendBluetoothCommand(any(BluetoothGATTGetServicesRequest.class));
        assertEquals(ConnectionState.CONNECTED, device.getConnectionState());

        device.handleGattServicesDoneMessage(BluetoothGATTGetServicesDoneResponse.getDefaultInstance());
        assertTrue(servicesDiscovered);
        assertEquals(ConnectionState.CONNECTED, device.getConnectionState());
        assertFalse(notifiedStates.contains(ConnectionState.DISCOVERING),
                "DISCOVERING must not be reported for GATT service discovery: " + notifiedStates);
    }

    @Test
    void refusesConnectWhileBoundToAnotherEsp() {
        connectVia(espA);
        device.discoverServices();

        // A different ESP is now nearest; a second connect must not open a parallel link via it.
        when(proxyHandler.getNearestESPHomeDevice(anyLong())).thenReturn(espB);
        assertFalse(device.connect());
        verify(espB, never()).sendBluetoothCommand(any(GeneratedMessage.class));
    }

    @Test
    void refusesConnectWhileDisconnectIsPending() {
        connectVia(espA);
        assertTrue(device.disconnect());
        assertEquals(ConnectionState.DISCONNECTING, device.getConnectionState());

        when(proxyHandler.getNearestESPHomeDevice(anyLong())).thenReturn(espB);
        assertFalse(device.connect());
        verify(espB, never()).sendBluetoothCommand(any(GeneratedMessage.class));
    }

    @Test
    void allowsReconnectViaAnotherEspAfterDisconnectConfirmed() {
        connectVia(espA);
        device.disconnect();
        device.handleConnectionsMessage(BluetoothDeviceConnectionResponse.newBuilder()
                .setAddress(device.getAddressAsLong()).setConnected(false).build());
        assertEquals(ConnectionState.DISCONNECTED, device.getConnectionState());

        when(proxyHandler.getNearestESPHomeDevice(anyLong())).thenReturn(espB);
        assertTrue(device.connect());
        verify(espB).sendBluetoothCommand(BluetoothDeviceRequest.newBuilder().setAddress(device.getAddressAsLong())
                .setAddressType(0).setHasAddressType(true)
                .setRequestType(BluetoothDeviceRequestType.BLUETOOTH_DEVICE_REQUEST_TYPE_CONNECT_V3_WITHOUT_CACHE)
                .build());
    }

    @Test
    void allowsReconnectAfterEspWentOffline() {
        connectVia(espA);
        device.handleESPHomeHandlerLost();
        assertEquals(ConnectionState.DISCONNECTED, device.getConnectionState());

        when(proxyHandler.getNearestESPHomeDevice(anyLong())).thenReturn(espB);
        assertTrue(device.connect());
    }

    private class RecordingListener implements BluetoothDeviceListener {
        @Override
        public void onConnectionStateChange(BluetoothConnectionStatusNotification notification) {
            notifiedStates.add(notification.getConnectionState());
        }

        @Override
        public void onServicesDiscovered() {
            servicesDiscovered = true;
        }

        @Override
        public void onScanRecordReceived(BluetoothScanNotification notification) {
        }

        @Override
        public void onCharacteristicUpdate(BluetoothCharacteristic characteristic, byte[] value) {
        }

        @Override
        public void onDescriptorUpdate(BluetoothDescriptor descriptor, byte[] value) {
        }

        @Override
        public void onAdapterChanged(BluetoothAdapter adapter) {
        }
    }

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
