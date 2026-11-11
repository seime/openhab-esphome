package no.seime.openhab.binding.esphome.internal.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.core.audio.AudioHTTPServer;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.net.NetworkAddressService;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.internal.ThingImpl;
import org.osgi.framework.BundleContext;

import com.jano7.executor.KeySequentialExecutor;

import io.esphome.api.DeviceInfoResponse;
import io.esphome.api.ListEntitiesDoneResponse;
import io.esphome.api.ListEntitiesRequest;
import io.esphome.api.NoiseEncryptionSetKeyRequest;
import io.esphome.api.NoiseEncryptionSetKeyResponse;
import io.esphome.api.SubscribeStatesRequest;
import no.seime.openhab.binding.esphome.internal.*;
import no.seime.openhab.binding.esphome.internal.comm.ConnectionSelector;
import no.seime.openhab.binding.esphome.internal.comm.EncryptedFrameHelper;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.discovery.ESPHomeMDNSHostnameResolver;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.ESPHomeEventSubscriber;

@ExtendWith(MockitoExtension.class)
class ESPHomeHandlerLastKnownIpAddressTest {

    @Mock
    private ESPChannelTypeProvider channelTypeProvider;
    @Mock
    private ESPStateDescriptionProvider stateDescriptionProvider;
    @Mock
    private ESPHomeEventSubscriber eventSubscriber;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private BundleContext bundleContext;
    @Mock
    private ThingHandlerCallback callback;
    @Mock
    private ESPHomeMDNSHostnameResolver mdnsHostnameResolver;
    @Mock
    private EncryptedFrameHelper frameHelper;
    @Mock
    private ESPHomeVersionService versionService;
    @Mock
    private AudioHTTPServer audioHTTPServer;
    @Mock
    private NetworkAddressService networkAddressService;

    @Mock
    private ManagedThingProvider managedThingProvider;

    private ESPHomeHandler handler;
    private ThingImpl thing;
    private MonitoredCompositeExecutorService executor;
    private ExecutorService packetProcessorExecutor;

    @BeforeEach
    void setUp() throws Exception {
        thing = new ThingImpl(BindingConstants.THING_TYPE_DEVICE, "device");

        executor = new MonitoredCompositeExecutorService(Executors.newScheduledThreadPool(1),
                (ThreadPoolExecutor) Executors.newCachedThreadPool(), 1000);
        packetProcessorExecutor = Executors.newSingleThreadExecutor();

        FirmwareUpgradeService firmwareUpgradeService = Mockito.mock(FirmwareUpgradeService.class);

        handler = new ESPHomeHandler(thing, new ConnectionSelector(), channelTypeProvider, stateDescriptionProvider,
                eventSubscriber, executor, new KeySequentialExecutor(packetProcessorExecutor), eventPublisher, null,
                null, bundleContext, versionService, firmwareUpgradeService, mdnsHostnameResolver, audioHTTPServer,
                networkAddressService, managedThingProvider);

        setField("config", new ESPHomeConfiguration());
        setField("frameHelper", frameHelper);
        handler.setCallback(callback);
    }

    @AfterEach
    void tearDown() {
        handler.dispose();
        executor.shutdownNow();
        packetProcessorExecutor.shutdownNow();
    }

    @Test
    void fallsBackToCachedIpAddressWhenHostnameResolutionFails() throws Exception {
        thing.setProperties(Map.of(ESPHomeHandler.PROPERTY_LAST_KNOWN_IP_ADDRESS, "127.0.0.1"));

        Object target = invokeMethod("resolveConnectionTarget", new Class<?>[] { String.class }, "device.invalid");

        assertEquals("127.0.0.1", invokeRecordAccessor(target, "connectHost"));
        assertEquals("127.0.0.1", invokeRecordAccessor(target, "ipAddress"));
    }

    @Test
    void fallsBackToTrimmedCachedIpAddressWhenHostnameResolutionFails() throws Exception {
        thing.setProperties(Map.of(ESPHomeHandler.PROPERTY_LAST_KNOWN_IP_ADDRESS, " 127.0.0.1 "));

        Object target = invokeMethod("resolveConnectionTarget", new Class<?>[] { String.class }, "device.invalid");

        assertEquals("127.0.0.1", invokeRecordAccessor(target, "connectHost"));
        assertEquals("127.0.0.1", invokeRecordAccessor(target, "ipAddress"));
    }

    @Test
    void throwsWhenHostnameResolutionFailsWithoutCachedIpAddress() {
        Exception error = assertThrows(Exception.class,
                () -> invokeMethod("resolveConnectionTarget", new Class<?>[] { String.class }, "device.invalid"));

        Throwable cause = error.getCause();
        assertNotNull(cause);
        assertInstanceOf(ProtocolAPIError.class, cause);
        assertEquals("Failed to resolve hostname 'device.invalid'", cause.getMessage());
    }

    @Test
    void throwsWhenHostnameResolutionFailsWithInvalidCachedIpAddress() {
        thing.setProperties(Map.of(ESPHomeHandler.PROPERTY_LAST_KNOWN_IP_ADDRESS, " not-an-ip "));

        Exception error = assertThrows(Exception.class,
                () -> invokeMethod("resolveConnectionTarget", new Class<?>[] { String.class }, "device.invalid"));

        Throwable cause = error.getCause();
        assertNotNull(cause);
        assertInstanceOf(ProtocolAPIError.class, cause);
        assertEquals("Failed to resolve hostname 'device.invalid'", cause.getMessage());
    }

    @Test
    void doesNotUseCachedIpAddressWhenConfiguredHostnameIsLiteralIp() throws Exception {
        thing.setProperties(Map.of(ESPHomeHandler.PROPERTY_LAST_KNOWN_IP_ADDRESS, "127.0.0.1"));

        Object target = invokeMethod("resolveConnectionTarget", new Class<?>[] { String.class }, "192.0.2.55");

        assertEquals("192.0.2.55", invokeRecordAccessor(target, "connectHost"));
        assertEquals("192.0.2.55", invokeRecordAccessor(target, "ipAddress"));
        assertEquals(false, invokeRecordAccessor(target, "cacheLastKnownIpAddress"));

        invokeMethod("applyLastKnownIpAddressPolicy", new Class<?>[] { target.getClass() }, target);

        verify(callback).thingUpdated(argThat(updatedThing -> !updatedThing.getProperties()
                .containsKey(ESPHomeHandler.PROPERTY_LAST_KNOWN_IP_ADDRESS)));
    }

    @Test
    void persistsResolvedIpAddressOnThingUpdate() throws Exception {
        setField("resolvedIpAddressForCurrentConnection", "127.0.0.1");

        invokeMethod("persistLastKnownIpAddress", new Class<?>[0]);

        verify(callback).thingUpdated(argThat(updatedThing -> "127.0.0.1"
                .equals(updatedThing.getProperties().get(ESPHomeHandler.PROPERTY_LAST_KNOWN_IP_ADDRESS))));
    }

    @Test
    void doesNotPersistResolvedIpAddressWhenLiteralIpModeIsActive() throws Exception {
        thing.setProperties(Map.of(ESPHomeHandler.PROPERTY_LAST_KNOWN_IP_ADDRESS, "127.0.0.1"));
        setField("resolvedIpAddressForCurrentConnection", null);

        invokeMethod("persistLastKnownIpAddress", new Class<?>[0]);

        verifyNoMoreInteractions(callback);
    }

    @Test
    void preservesCachedIpAddressWhenDeviceInfoUpdatesProperties() throws Exception {
        thing.setProperties(Map.of(ESPHomeHandler.PROPERTY_LAST_KNOWN_IP_ADDRESS, "127.0.0.1"));

        invokeMethod("handleConnected", new Class<?>[] { com.google.protobuf.GeneratedMessage.class },
                DeviceInfoResponse.newBuilder().setEsphomeVersion("2026.1.0").setMacAddress("AA:BB:CC:DD:EE:FF")
                        .setModel("ESP32").setName("virtual").setManufacturer("Espressif")
                        .setCompilationTime("2026-04-14T00:00:00Z").build());

        verify(callback).thingUpdated(argThat(updatedThing -> "127.0.0.1"
                .equals(updatedThing.getProperties().get(ESPHomeHandler.PROPERTY_LAST_KNOWN_IP_ADDRESS))
                && "virtual".equals(updatedThing.getProperties().get("name"))));
    }

    @Test
    void skipsInterrogationWhenCompilationTimeUnchanged() throws Exception {
        stubFirmwareChannels();
        handleDeviceInfo("2026-04-14T00:00:00Z");
        invokeMethod("handleConnected", new Class<?>[] { com.google.protobuf.GeneratedMessage.class },
                ListEntitiesDoneResponse.getDefaultInstance());
        verify(frameHelper, times(1)).send(ListEntitiesRequest.getDefaultInstance());

        handleDeviceInfo("2026-04-14T00:00:00Z");

        verify(frameHelper, times(1)).send(ListEntitiesRequest.getDefaultInstance());
        verify(frameHelper, times(2)).send(SubscribeStatesRequest.getDefaultInstance());
    }

    @Test
    void interrogatesWhenCompilationTimeChanged() throws Exception {
        stubFirmwareChannels();
        handleDeviceInfo("2026-04-14T00:00:00Z");
        invokeMethod("handleConnected", new Class<?>[] { com.google.protobuf.GeneratedMessage.class },
                ListEntitiesDoneResponse.getDefaultInstance());

        handleDeviceInfo("2026-04-15T00:00:00Z");

        verify(frameHelper, times(2)).send(ListEntitiesRequest.getDefaultInstance());
    }

    @Test
    void interrogatesAgainWhenPreviousInterrogationDidNotComplete() throws Exception {
        handleDeviceInfo("2026-04-14T00:00:00Z");
        handleDeviceInfo("2026-04-14T00:00:00Z");

        verify(frameHelper, times(2)).send(ListEntitiesRequest.getDefaultInstance());
    }

    private void stubFirmwareChannels() {
        when(versionService.createFirmwareUpdateAvailableChannel(thing.getUID())).thenAnswer(
                i -> ChannelBuilder.create(new ChannelUID(thing.getUID(), "firmwareUpdateAvailable")).build());
        when(versionService.createLatestFirmwareVersionChannel(thing.getUID())).thenAnswer(
                i -> ChannelBuilder.create(new ChannelUID(thing.getUID(), "latestFirmwareVersion")).build());
    }

    private void handleDeviceInfo(String compilationTime) throws Exception {
        invokeMethod("handleConnected", new Class<?>[] { com.google.protobuf.GeneratedMessage.class },
                DeviceInfoResponse.newBuilder().setCompilationTime(compilationTime).build());
    }

    @Test
    void adoptionPersistsKeyBeforeSendingItToDevice() throws Exception {
        when(managedThingProvider.get(thing.getUID())).thenReturn(thing);
        thing.getConfiguration().put("hostname", "device.local");
        doAnswer(invocation -> {
            NoiseEncryptionSetKeyRequest request = invocation.getArgument(0);
            String key = request.getKey().toStringUtf8();
            assertEquals(32, Base64.getDecoder().decode(key).length);
            assertEquals(key, thing.getConfiguration().get("encryptionKey"));
            assertEquals("device.local", thing.getConfiguration().get("hostname"));
            verify(callback).thingUpdated(thing);
            return null;
        }).when(frameHelper).send(any(NoiseEncryptionSetKeyRequest.class));

        invokeMethod("provisionEncryptionKey", new Class<?>[] { DeviceInfoResponse.class }, DeviceInfoResponse
                .newBuilder().setApiEncryptionSupported(true).setApiEncryptionProvisionable(true).build());

        verify(frameHelper).send(any(NoiseEncryptionSetKeyRequest.class));
    }

    @Test
    void adoptionDoesNotReplaceExistingKey() throws Exception {
        when(managedThingProvider.get(thing.getUID())).thenReturn(thing);
        thing.getConfiguration().put("encryptionKey", "existing-key");
        invokeMethod("provisionEncryptionKey", new Class<?>[] { DeviceInfoResponse.class }, DeviceInfoResponse
                .newBuilder().setApiEncryptionSupported(true).setApiEncryptionProvisionable(true).build());
        verify(frameHelper, never()).send(any(NoiseEncryptionSetKeyRequest.class));
        assertEquals("existing-key", thing.getConfiguration().get("encryptionKey"));
    }

    @Test
    void adoptionRequiresEditableThing() throws Exception {
        invokeMethod("provisionEncryptionKey", new Class<?>[] { DeviceInfoResponse.class }, DeviceInfoResponse
                .newBuilder().setApiEncryptionSupported(true).setApiEncryptionProvisionable(true).build());
        verify(frameHelper, never()).send(any(NoiseEncryptionSetKeyRequest.class));
        assertNull(thing.getConfiguration().get("encryptionKey"));
    }

    @Test
    void adoptionRequiresProvisionableDevice() throws Exception {
        invokeMethod("provisionEncryptionKey", new Class<?>[] { DeviceInfoResponse.class },
                DeviceInfoResponse.newBuilder().setApiEncryptionSupported(true).build());
        verify(frameHelper, never()).send(any(NoiseEncryptionSetKeyRequest.class));
        assertNull(thing.getConfiguration().get("encryptionKey"));
    }

    @Test
    void adoptionDoesNotSendKeyWhenSavingFails() throws Exception {
        when(managedThingProvider.get(thing.getUID())).thenReturn(thing);
        doThrow(new IllegalStateException("Storage unavailable")).when(callback).thingUpdated(thing);
        invokeMethod("provisionEncryptionKey", new Class<?>[] { DeviceInfoResponse.class }, DeviceInfoResponse
                .newBuilder().setApiEncryptionSupported(true).setApiEncryptionProvisionable(true).build());
        verify(frameHelper, never()).send(any(NoiseEncryptionSetKeyRequest.class));
        assertNull(thing.getConfiguration().get("encryptionKey"));
    }

    @Test
    void adoptionReconnectsAfterDeviceAcknowledgesKey() throws Exception {
        when(managedThingProvider.get(thing.getUID())).thenReturn(thing);
        setConnected();
        setField("provisioningConnection", true);
        invokeMethod("handleConnected", new Class<?>[] { com.google.protobuf.GeneratedMessage.class },
                DeviceInfoResponse.newBuilder().setApiEncryptionSupported(true).setApiEncryptionProvisionable(true)
                        .build());
        String savedKey = (String) thing.getConfiguration().get("encryptionKey");

        handler.onPacket(NoiseEncryptionSetKeyResponse.newBuilder().setSuccess(true).build());

        verify(frameHelper).close();
        verify(frameHelper, never()).send(any(ListEntitiesRequest.class));
        assertEquals(savedKey, thing.getConfiguration().get("encryptionKey"));
    }

    @Test
    void adoptionRetainsKeyWhenDeviceRejectsIt() throws Exception {
        when(managedThingProvider.get(thing.getUID())).thenReturn(thing);
        setConnected();
        setField("provisioningConnection", true);
        invokeMethod("handleConnected", new Class<?>[] { com.google.protobuf.GeneratedMessage.class },
                DeviceInfoResponse.newBuilder().setApiEncryptionSupported(true).setApiEncryptionProvisionable(true)
                        .build());
        String savedKey = (String) thing.getConfiguration().get("encryptionKey");

        handler.onPacket(NoiseEncryptionSetKeyResponse.newBuilder().setSuccess(false).build());

        verify(frameHelper).close();
        assertEquals(savedKey, thing.getConfiguration().get("encryptionKey"));
    }

    private void setConnected() throws Exception {
        Field field = ESPHomeHandler.class.getDeclaredField("connectionState");
        for (Object state : field.getType().getEnumConstants()) {
            if ("CONNECTED".equals(state.toString())) {
                setField("connectionState", state);
                return;
            }
        }
        fail("CONNECTED state missing");
    }

    private Object invokeMethod(String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = ESPHomeHandler.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(handler, args);
    }

    private Object invokeRecordAccessor(Object target, String accessorName) throws Exception {
        Method accessor = target.getClass().getDeclaredMethod(accessorName);
        accessor.setAccessible(true);
        return accessor.invoke(target);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = ESPHomeHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(handler, value);
    }
}
