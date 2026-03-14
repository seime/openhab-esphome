package no.seime.openhab.binding.esphome.devicetest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openhab.binding.bluetooth.BluetoothAddress;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingUID;

import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessage;

import io.esphome.api.*;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.bluetooth.ESPHomeBluetoothDevice;
import no.seime.openhab.binding.esphome.internal.bluetooth.ESPHomeBluetoothProxyHandler;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;
import no.seime.openhab.binding.esphome.internal.handler.MonitoredScheduledThreadPoolExecutor;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class BluetoothConnectionRaceTest {

    private ESPHomeBluetoothProxyHandler proxyHandler;

    @Mock
    private Bridge bridge;

    @Mock
    private ThingRegistry thingRegistry;

    @Mock
    private ESPHomeHandler thingHandler;

    @Mock
    private Thing thing;

    private final MonitoredScheduledThreadPoolExecutor scheduler = new MonitoredScheduledThreadPoolExecutor(1,
            r -> new Thread(r), 1000);

    @BeforeEach
    public void setUp() throws Exception {
        ThingUID bridgeUID = new ThingUID(BindingConstants.THING_TYPE_BLE_PROXY, "proxy");
        when(bridge.getUID()).thenReturn(bridgeUID);
        when(bridge.getThingTypeUID()).thenReturn(BindingConstants.THING_TYPE_BLE_PROXY);

        when(thingHandler.getThing()).thenReturn(thing);
        ThingUID thingUID = new ThingUID(BindingConstants.THING_TYPE_DEVICE, "device");
        when(thing.getUID()).thenReturn(thingUID);
        when(thingRegistry.get(thingUID)).thenReturn(thing);
        when(thing.getHandler()).thenReturn(thingHandler);

        proxyHandler = new ESPHomeBluetoothProxyHandler(bridge, thingRegistry, scheduler);
    }

    @Test
    public void testDuplicateConnectRequests() throws Exception {
        long addressLong = 0x112233445566L;
        BluetoothAddress btAddress = new BluetoothAddress("11:22:33:44:55:66");

        // Create the device in proxy
        BluetoothLEAdvertisementResponse advertisement = BluetoothLEAdvertisementResponse.newBuilder()
                .setAddress(addressLong).setName(ByteString.copyFromUtf8("TestDevice")).setRssi(-60).build();
        proxyHandler.handleBluetoothMessage(advertisement, thingHandler);

        ESPHomeBluetoothDevice device = proxyHandler.getDevice(btAddress);
        assertNotNull(device);

        // Connect once
        device.connect();

        // Connect again immediately
        device.connect();

        // Verify that only ONE connect command was sent
        ArgumentCaptor<GeneratedMessage> commandCaptor = ArgumentCaptor.forClass(GeneratedMessage.class);
        verify(thingHandler, times(1)).sendBluetoothCommand(commandCaptor.capture());

        Optional<BluetoothDeviceRequest> connectRequest = commandCaptor.getAllValues().stream()
                .filter(m -> m instanceof BluetoothDeviceRequest).map(m -> (BluetoothDeviceRequest) m)
                .filter(r -> r
                        .getRequestType() == BluetoothDeviceRequestType.BLUETOOTH_DEVICE_REQUEST_TYPE_CONNECT_V3_WITHOUT_CACHE)
                .findFirst();

        assertTrue(connectRequest.isPresent(), "Connect request not found");
    }

    @Test
    public void testMultiThreadedConnect() throws Exception {
        long addressLong = 0x112233445566L;
        BluetoothAddress btAddress = new BluetoothAddress("11:22:33:44:55:66");

        // Create the device in proxy
        BluetoothLEAdvertisementResponse advertisement = BluetoothLEAdvertisementResponse.newBuilder()
                .setAddress(addressLong).setName(ByteString.copyFromUtf8("TestDevice")).setRssi(-60).build();
        proxyHandler.handleBluetoothMessage(advertisement, thingHandler);

        ESPHomeBluetoothDevice device = proxyHandler.getDevice(btAddress);
        assertNotNull(device);

        // Call connect() from multiple threads simultaneously
        int numThreads = 10;
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    device.connect();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await();

        // Verify that only ONE connect command was sent despite multiple threads calling it
        verify(thingHandler, times(1)).sendBluetoothCommand(any());
    }

    @Test
    public void testReconnectInListener() throws Exception {
        long addressLong = 0x112233445566L;
        BluetoothAddress btAddress = new BluetoothAddress("11:22:33:44:55:66");

        // Create the device in proxy
        BluetoothLEAdvertisementResponse advertisement = BluetoothLEAdvertisementResponse.newBuilder()
                .setAddress(addressLong).setName(ByteString.copyFromUtf8("TestDevice")).setRssi(-60).build();
        proxyHandler.handleBluetoothMessage(advertisement, thingHandler);

        ESPHomeBluetoothDevice device = proxyHandler.getDevice(btAddress);
        assertNotNull(device);

        // Add a listener that reconnects on disconnect
        device.addListener(new org.openhab.binding.bluetooth.BluetoothDeviceListener() {
            @Override
            public void onScanRecordReceived(
                    org.openhab.binding.bluetooth.notification.BluetoothScanNotification notification) {
            }

            @Override
            public void onConnectionStateChange(
                    org.openhab.binding.bluetooth.notification.BluetoothConnectionStatusNotification notification) {
                if (notification
                        .getConnectionState() == org.openhab.binding.bluetooth.BluetoothDevice.ConnectionState.DISCONNECTED) {
                    device.connect();
                }
            }

            @Override
            public void onServicesDiscovered() {
            }

            @Override
            public void onCharacteristicUpdate(org.openhab.binding.bluetooth.BluetoothCharacteristic characteristic,
                    byte[] data) {
            }

            @Override
            public void onDescriptorUpdate(org.openhab.binding.bluetooth.BluetoothDescriptor descriptor, byte[] data) {
            }

            @Override
            public void onAdapterChanged(org.openhab.binding.bluetooth.BluetoothAdapter adapter) {
            }
        });

        // 1. First connection attempt
        device.connect();
        verify(thingHandler, times(1)).sendBluetoothCommand(any());

        // Wait a bit to avoid debounce
        Thread.sleep(600);

        // 2. Simulate failure
        BluetoothDeviceConnectionResponse fail = BluetoothDeviceConnectionResponse.newBuilder().setAddress(addressLong)
                .setConnected(false).setError(256).build();
        proxyHandler.handleBluetoothMessage(fail, thingHandler);

        // 3. Verify that the reconnect attempt WORKED
        // If the bug exists, it should still be 1 because the reconnect attempt saw connecting=true
        // If the fix is applied, it should be 2.
        verify(thingHandler, timeout(1000).times(2)).sendBluetoothCommand(any());
    }

    @Test
    public void testRedundantFailureResponses() throws Exception {
        long addressLong = 0x112233445566L;
        BluetoothAddress btAddress = new BluetoothAddress("11:22:33:44:55:66");

        // Create the device in proxy
        BluetoothLEAdvertisementResponse advertisement = BluetoothLEAdvertisementResponse.newBuilder()
                .setAddress(addressLong).setName(ByteString.copyFromUtf8("TestDevice")).setRssi(-60).build();
        proxyHandler.handleBluetoothMessage(advertisement, thingHandler);

        ESPHomeBluetoothDevice device = proxyHandler.getDevice(btAddress);
        assertNotNull(device);

        // 1. Connect once (Attempt A)
        device.connect();
        Thread.sleep(600);

        // 2. Simulate a failure response from Attempt A (Response A1)
        BluetoothDeviceConnectionResponse fail1 = BluetoothDeviceConnectionResponse.newBuilder().setAddress(addressLong)
                .setConnected(false).setError(256).build();
        proxyHandler.handleBluetoothMessage(fail1, thingHandler);

        // Verify that one connect command was sent
        verify(thingHandler, times(1)).sendBluetoothCommand(any());

        // 3. Connect again immediately (Attempt B)
        device.connect();
        verify(thingHandler, times(2)).sendBluetoothCommand(any());

        // 4. Simulate SECOND failure response from Attempt A (Response A2)
        // This response arrives VERY SOON after Attempt B started.
        BluetoothDeviceConnectionResponse fail2 = BluetoothDeviceConnectionResponse.newBuilder().setAddress(addressLong)
                .setConnected(false).setError(133).build();
        proxyHandler.handleBluetoothMessage(fail2, thingHandler);

        // 5. Check if Attempt B's state was cleared by Attempt A's redundant response.
        // If it was cleared, another connect() call will send a THIRD command.
        device.connect();

        // If the fix is correct, this THIRD call should NOT send another command.
        verify(thingHandler, times(2)).sendBluetoothCommand(any());
    }

    @Test
    public void testDisconnectClearsFutures() throws Exception {
        long addressLong = 0x112233445566L;
        BluetoothAddress btAddress = new BluetoothAddress("11:22:33:44:55:66");

        // Create the device in proxy
        BluetoothLEAdvertisementResponse advertisement = BluetoothLEAdvertisementResponse.newBuilder()
                .setAddress(addressLong).setName(ByteString.copyFromUtf8("TestDevice")).setRssi(-60).build();
        proxyHandler.handleBluetoothMessage(advertisement, thingHandler);

        ESPHomeBluetoothDevice device = proxyHandler.getDevice(btAddress);
        assertNotNull(device);

        // 1. Connect
        device.connect();
        BluetoothDeviceConnectionResponse connected = BluetoothDeviceConnectionResponse.newBuilder()
                .setAddress(addressLong).setConnected(true).build();
        proxyHandler.handleBluetoothMessage(connected, thingHandler);

        // 2. Start a read
        UUID charUuid = UUID.randomUUID();
        org.openhab.binding.bluetooth.BluetoothCharacteristic characteristic = new org.openhab.binding.bluetooth.BluetoothCharacteristic(
                charUuid, 1);
        CompletableFuture<byte[]> readFuture = device.readCharacteristic(characteristic);

        // 3. Simulate disconnect
        BluetoothDeviceConnectionResponse disconnected = BluetoothDeviceConnectionResponse.newBuilder()
                .setAddress(addressLong).setConnected(false).build();
        proxyHandler.handleBluetoothMessage(disconnected, thingHandler);

        // 4. Verify future is completed exceptionally
        assertTrue(readFuture.isCompletedExceptionally());
        try {
            readFuture.get();
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Device disconnected"));
        }
    }

    @Test
    public void testConnectAfterDisconnectMessage() throws Exception {

        long addressLong = 0x112233445566L;
        BluetoothAddress btAddress = new BluetoothAddress("11:22:33:44:55:66");

        // Create the device in proxy
        BluetoothLEAdvertisementResponse advertisement = BluetoothLEAdvertisementResponse.newBuilder()
                .setAddress(addressLong).setName(ByteString.copyFromUtf8("TestDevice")).setRssi(-60).build();
        proxyHandler.handleBluetoothMessage(advertisement, thingHandler);

        ESPHomeBluetoothDevice device = proxyHandler.getDevice(btAddress);

        assertNotNull(device);

        // Connect once
        device.connect();
        Thread.sleep(600);

        // Simulate a disconnect response from ESPHome
        BluetoothDeviceConnectionResponse disconnectResponse = BluetoothDeviceConnectionResponse.newBuilder()
                .setAddress(addressLong).setConnected(false).build();
        proxyHandler.handleBluetoothMessage(disconnectResponse, thingHandler);

        // Connect again
        device.connect();

        // Verify that TWO connect commands were sent in total (one before disconnect, one after)
        ArgumentCaptor<GeneratedMessage> commandCaptor = ArgumentCaptor.forClass(GeneratedMessage.class);
        verify(thingHandler, times(2)).sendBluetoothCommand(commandCaptor.capture());
    }

    @Test
    public void testAutomaticServiceDiscovery() throws Exception {
        long addressLong = 0x112233445566L;
        BluetoothAddress btAddress = new BluetoothAddress("11:22:33:44:55:66");

        // Create the device in proxy
        BluetoothLEAdvertisementResponse advertisement = BluetoothLEAdvertisementResponse.newBuilder()
                .setAddress(addressLong).setName(ByteString.copyFromUtf8("TestDevice")).setRssi(-60).build();
        proxyHandler.handleBluetoothMessage(advertisement, thingHandler);

        ESPHomeBluetoothDevice device = proxyHandler.getDevice(btAddress);
        assertNotNull(device);

        // Connect
        device.connect();

        // Simulate successful connection response
        BluetoothDeviceConnectionResponse response = BluetoothDeviceConnectionResponse.newBuilder()
                .setAddress(addressLong).setConnected(true).setMtu(23).build();
        proxyHandler.handleBluetoothMessage(response, thingHandler);

        // Verify that discoverServices() was triggered automatically
        ArgumentCaptor<GeneratedMessage> commandCaptor = ArgumentCaptor.forClass(GeneratedMessage.class);
        verify(thingHandler, atLeastOnce()).sendBluetoothCommand(commandCaptor.capture());

        boolean discoveryRequested = commandCaptor.getAllValues().stream()
                .anyMatch(m -> m instanceof io.esphome.api.BluetoothGATTGetServicesRequest);
        assertTrue(discoveryRequested, "Service discovery not requested automatically after connection");

        // Simulate services response
        UUID serviceUuid = UUID.randomUUID();
        UUID charUuid = UUID.randomUUID();
        BluetoothGATTGetServicesResponse servicesResponse = BluetoothGATTGetServicesResponse
                .newBuilder().setAddress(
                        addressLong)
                .addServices(
                        BluetoothGATTService.newBuilder().setHandle(1).setShortUuid(0)
                                .addUuid(serviceUuid.getMostSignificantBits())
                                .addUuid(serviceUuid.getLeastSignificantBits())
                                .addCharacteristics(
                                        io.esphome.api.BluetoothGATTCharacteristic.newBuilder().setHandle(2)
                                                .setShortUuid(0).addUuid(charUuid.getMostSignificantBits())
                                                .addUuid(charUuid.getLeastSignificantBits()).setProperties(0x12) // Read
                                                                                                                 // |
                                                                                                                 // Notify
                                                .addDescriptors(io.esphome.api.BluetoothGATTDescriptor.newBuilder()
                                                        .setHandle(3).setShortUuid(0x2902).build())
                                                .build())
                                .build())
                .build();
        proxyHandler.handleBluetoothMessage(servicesResponse, thingHandler);

        // Simulate discovery done
        BluetoothGATTGetServicesDoneResponse doneResponse = BluetoothGATTGetServicesDoneResponse.newBuilder()
                .setAddress(addressLong).build();
        proxyHandler.handleBluetoothMessage(doneResponse, thingHandler);

        // Verify services are discovered and populated
        long startTime = System.currentTimeMillis();
        while (!device.isServicesDiscovered() && System.currentTimeMillis() - startTime < 2000) {
            Thread.sleep(10);
        }
        assertTrue(device.isServicesDiscovered(), "Services should be marked as discovered");
        assertNotNull(device.getServices(serviceUuid), "Service should be found by UUID");
        org.openhab.binding.bluetooth.BluetoothCharacteristic characteristic = device.getServices().stream()
                .flatMap(s -> s.getCharacteristics().stream()).filter(c -> c.getHandle() == 2).findFirst().orElse(null);
        assertNotNull(characteristic);
        assertTrue(characteristic.canRead());
        assertTrue(characteristic.canNotify());
        assertFalse(characteristic.canWrite());
        assertNotNull(characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")));
    }
}
