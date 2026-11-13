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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.core.audio.AudioHTTPServer;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.net.NetworkAddressService;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.internal.ThingImpl;
import org.openhab.core.types.RefreshType;
import org.osgi.framework.BundleContext;

import com.jano7.executor.KeySequentialExecutor;

import io.esphome.api.BluetoothDeviceRequest;
import io.esphome.api.DeviceInfoRequest;
import io.esphome.api.DeviceInfoResponse;
import io.esphome.api.ExecuteServiceRequest;
import io.esphome.api.HelloResponse;
import io.esphome.api.HomeassistantActionRequest;
import io.esphome.api.ListEntitiesDoneResponse;
import io.esphome.api.ListEntitiesRequest;
import io.esphome.api.NoiseEncryptionSetKeyRequest;
import io.esphome.api.NoiseEncryptionSetKeyResponse;
import io.esphome.api.PingRequest;
import io.esphome.api.PingResponse;
import io.esphome.api.SubscribeHomeAssistantStateResponse;
import io.esphome.api.SubscribeHomeassistantServicesRequest;
import io.esphome.api.SubscribeLogsRequest;
import io.esphome.api.SubscribeStatesRequest;
import no.seime.openhab.binding.esphome.internal.*;
import no.seime.openhab.binding.esphome.internal.bluetooth.ESPHomeBluetoothProxyHandler;
import no.seime.openhab.binding.esphome.internal.comm.CommunicationError;
import no.seime.openhab.binding.esphome.internal.comm.ConnectionSelector;
import no.seime.openhab.binding.esphome.internal.comm.EncryptedFrameHelper;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.discovery.ESPHomeMDNSHostnameResolver;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.ESPHomeEventSubscriber;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.EventSubscription;

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

    @Test
    void bootstrapRejectsActionsStateSubscriptionsAndEntityMessages() throws Exception {
        setConnected();
        setField("provisioningConnection", true);
        handler.onPacket(HomeassistantActionRequest.newBuilder().setService("test.action").build());
        handler.onPacket(HomeassistantActionRequest.newBuilder().setService("test.event").setIsEvent(true).build());
        handler.onPacket(SubscribeHomeAssistantStateResponse.getDefaultInstance());
        handler.onPacket(ListEntitiesDoneResponse.getDefaultInstance());
        verifyNoInteractions(eventPublisher, eventSubscriber, bundleContext, frameHelper);
    }

    @Test
    void bootstrapRejectsChannelCommandsAndDirectMessages() throws Exception {
        setConnected();
        setField("provisioningConnection", true);
        handler.handleCommand(new ChannelUID(thing.getUID(), "test"), RefreshType.REFRESH);
        assertThrows(ProtocolAPIError.class, () -> handler.sendMessage(SubscribeStatesRequest.getDefaultInstance()));
        verifyNoInteractions(frameHelper);
    }

    @Test
    void bootstrapAllowsControlMessages() throws Exception {
        setConnected();
        setField("provisioningConnection", true);
        handler.onPacket(PingRequest.getDefaultInstance());
        verify(frameHelper).send(PingResponse.getDefaultInstance());
    }

    @Test
    void bootstrapKeepsTimeoutThroughHelloAndRequestsOnlyDeviceInfo() throws Exception {
        ESPHomeConfiguration configuration = new ESPHomeConfiguration();
        configuration.allowActions = true;
        configuration.deviceLogLevel = LogLevel.DEBUG;
        setField("config", configuration);
        setField("provisioningConnection", true);
        ScheduledFuture<?> timeout = executor.schedule(() -> {
        }, 60, TimeUnit.SECONDS);
        setField("connectionTimeoutFuture", timeout);

        handler.onConnect();
        assertSame(timeout, getField("connectionTimeoutFuture"));
        assertFalse(timeout.isCancelled());
        handler.onPacket(HelloResponse.getDefaultInstance());
        // A peer answering pings without sending device info cannot extend this deadline.
        handler.onPacket(PingResponse.getDefaultInstance());
        assertSame(timeout, getField("connectionTimeoutFuture"));
        assertFalse(timeout.isCancelled());
        verify(frameHelper).send(DeviceInfoRequest.getDefaultInstance());
        verify(frameHelper, never()).send(any(SubscribeHomeassistantServicesRequest.class));
        verify(frameHelper, never()).send(any(SubscribeLogsRequest.class));
        assertNull(getField("pingWatchdogFuture"));
        assertNull(getField("deepSleepWatchdogFuture"));
    }

    @Test
    void authenticatedSessionCancelsConnectionTimeoutAndSubscribesToActions() throws Exception {
        ESPHomeConfiguration configuration = new ESPHomeConfiguration();
        configuration.allowActions = true;
        setField("config", configuration);
        ScheduledFuture<?> timeout = executor.schedule(() -> {
        }, 60, TimeUnit.SECONDS);
        setField("connectionTimeoutFuture", timeout);
        handler.onConnect();
        assertTrue(timeout.isCancelled());
        handler.onPacket(HelloResponse.getDefaultInstance());
        verify(frameHelper).send(SubscribeHomeassistantServicesRequest.getDefaultInstance());
    }

    @Test
    void deepSleepAdoptionSchedulesReconnectAfterConfirmation() throws Exception {
        prepareDeepSleepAdoption();
        handler.onPacket(NoiseEncryptionSetKeyResponse.newBuilder().setSuccess(true).build());
        verify(frameHelper).close();
        assertNotNull(getField("connectFuture"));
    }

    @Test
    void deepSleepAdoptionSchedulesReconnectAfterConfirmationTimeout() throws Exception {
        MonitoredCompositeExecutorService monitoredExecutor = spy(executor);
        setField("executorService", monitoredExecutor);
        prepareDeepSleepAdoption();
        ArgumentCaptor<Runnable> watchdog = ArgumentCaptor.forClass(Runnable.class);
        verify(monitoredExecutor).schedule(watchdog.capture(), eq(60L), eq(TimeUnit.SECONDS),
                contains("Adoption watchdog"));
        watchdog.getValue().run();
        verify(frameHelper).close();
        assertNotNull(getField("connectFuture"));
    }

    @Test
    void bootstrapBlocksEveryNormalOutboundEntryPoint() throws Exception {
        setConnected();
        setField("provisioningConnection", true);
        ESPHomeConfiguration configuration = new ESPHomeConfiguration();
        configuration.enableBluetoothProxy = true;
        setField("config", configuration);
        EventSubscription subscription = mock(EventSubscription.class);
        when(subscription.getEntityId()).thenReturn("item.test");

        handler.executeAPIAction(ExecuteServiceRequest.getDefaultInstance());
        handler.sendBluetoothCommand(BluetoothDeviceRequest.getDefaultInstance());
        handler.listenForBLEAdvertisements(mock(ESPHomeBluetoothProxyHandler.class));
        handler.stopListeningForBLEAdvertisements();
        handler.handleOpenHABEvent(subscription, "private-state");

        verifyNoInteractions(frameHelper);
    }

    @Test
    void authenticatedSessionAllowsNormalOutboundEntryPoints() throws Exception {
        setConnected();
        ESPHomeConfiguration configuration = new ESPHomeConfiguration();
        configuration.enableBluetoothProxy = true;
        setField("config", configuration);
        EventSubscription subscription = mock(EventSubscription.class);
        when(subscription.getEntityId()).thenReturn("item.test");
        when(subscription.getAttribute()).thenReturn("");

        handler.executeAPIAction(ExecuteServiceRequest.getDefaultInstance());
        handler.sendBluetoothCommand(BluetoothDeviceRequest.getDefaultInstance());
        handler.listenForBLEAdvertisements(mock(ESPHomeBluetoothProxyHandler.class));
        handler.stopListeningForBLEAdvertisements();
        handler.handleOpenHABEvent(subscription, "private-state");

        verify(frameHelper).send(ExecuteServiceRequest.getDefaultInstance());
        verify(frameHelper).send(BluetoothDeviceRequest.getDefaultInstance());
        verify(frameHelper).send(io.esphome.api.SubscribeBluetoothLEAdvertisementsRequest.getDefaultInstance());
        verify(frameHelper).send(io.esphome.api.UnsubscribeBluetoothLEAdvertisementsRequest.getDefaultInstance());
        verify(frameHelper).send(io.esphome.api.HomeAssistantStateResponse.newBuilder().setEntityId("item.test")
                .setAttribute("").setState("private-state").build());
    }

    @Test
    void deepSleepAdoptionFailurePublishesConfigurationErrorWithoutReconnect() throws Exception {
        setConnected();
        setField("provisioningConnection", true);
        ESPHomeConfiguration configuration = new ESPHomeConfiguration();
        configuration.deepSleep = true;
        setField("config", configuration);

        handler.onPacket(DeviceInfoResponse.getDefaultInstance());

        verify(frameHelper).close();
        verify(callback).statusUpdated(eq(thing), argThat(status -> status.getStatus() == ThingStatus.OFFLINE
                && status.getStatusDetail() == ThingStatusDetail.CONFIGURATION_ERROR));
        assertNull(getField("connectFuture"));
    }

    @Test
    void closedBootstrapCallbacksCannotAffectReplacementConnection() throws Exception {
        setConnected();
        setField("provisioningConnection", true);
        CommunicationListener stale = (CommunicationListener) invokeMethod("createConnectionListener",
                new Class<?>[] { long.class }, getField("connectionGeneration"));
        handler.onEndOfStream("bootstrap closed");
        invokeMethod("cancelConnectFuture", new Class<?>[0]);
        setConnected();
        setField("provisioningConnection", false);
        setField("frameHelper", frameHelper);
        clearInvocations(frameHelper, callback, eventSubscriber);

        stale.onConnect();
        stale.onPacket(HomeassistantActionRequest.newBuilder().setService("test.event").setIsEvent(true).build());
        stale.onPacket(SubscribeHomeAssistantStateResponse.getDefaultInstance());
        stale.onPacket(PingRequest.getDefaultInstance());
        stale.onEndOfStream("stale EOF");
        stale.onParseError(CommunicationError.PACKET_ERROR);

        verifyNoInteractions(frameHelper, callback, eventSubscriber, eventPublisher);
        CommunicationListener current = (CommunicationListener) invokeMethod("createConnectionListener",
                new Class<?>[] { long.class }, getField("connectionGeneration"));
        current.onPacket(PingRequest.getDefaultInstance());
        verify(frameHelper).send(PingResponse.getDefaultInstance());
    }

    @Test
    void replacedConnectionCallbacksAreIgnoredEvenWithoutDisconnect() throws Exception {
        CommunicationListener stale = (CommunicationListener) invokeMethod("createConnectionListener",
                new Class<?>[] { long.class }, getField("connectionGeneration"));
        setField("connectionGeneration", (long) getField("connectionGeneration") + 1);
        setConnected();
        stale.onConnect();
        stale.onPacket(PingRequest.getDefaultInstance());
        stale.onEndOfStream("stale EOF");
        stale.onParseError(CommunicationError.PACKET_ERROR);
        verifyNoInteractions(frameHelper, callback);
    }

    @Test
    void disconnectResponseInvalidatesConnectionCallbacks() throws Exception {
        setConnected();
        CommunicationListener listener = (CommunicationListener) invokeMethod("createConnectionListener",
                new Class<?>[] { long.class }, getField("connectionGeneration"));
        listener.onPacket(io.esphome.api.DisconnectResponse.getDefaultInstance());
        verify(frameHelper).close();
        clearInvocations(frameHelper);
        listener.onConnect();
        listener.onPacket(PingRequest.getDefaultInstance());
        listener.onParseError(CommunicationError.PACKET_ERROR);
        listener.onEndOfStream("closed");
        verifyNoInteractions(frameHelper, callback);
    }

    @Test
    void rejectsConfiguredBootstrapKeysFromThingAndBinding() throws Exception {
        for (boolean bindingDefault : new boolean[] { false, true }) {
            for (boolean padded : new boolean[] { false, true }) {
                String key = Base64.getEncoder().encodeToString(new byte[32]);
                if (!padded) {
                    key = key.replace("=", "");
                }
                ESPHomeConfiguration configuration = new ESPHomeConfiguration();
                configuration.hostname = "127.0.0.1";
                configuration.encryptionKey = bindingDefault ? "" : key;
                configuration.deepSleep = true;
                setField("config", configuration);
                setField("bindingPropertyDefaultEncryptionKey", bindingDefault ? key : "");
                clearInvocations(callback);

                invokeMethod("connect", new Class<?>[0]);

                verify(callback).statusUpdated(eq(thing), argThat(status -> status.getStatus() == ThingStatus.OFFLINE
                        && status.getDescription().contains("reserved bootstrap encryption key")));
                assertSame(frameHelper, getField("frameHelper"), "No new helper should be created for a public key");
                assertFalse((boolean) getField("provisioningConnection"));
                assertNull(getField("connectFuture"));
            }
        }
        verifyNoInteractions(frameHelper);
    }

    private void prepareDeepSleepAdoption() throws Exception {
        ESPHomeConfiguration configuration = new ESPHomeConfiguration();
        configuration.deepSleep = true;
        setField("config", configuration);
        when(managedThingProvider.get(thing.getUID())).thenReturn(thing);
        setConnected();
        setField("provisioningConnection", true);
        handler.onPacket(DeviceInfoResponse.newBuilder().setApiEncryptionSupported(true)
                .setApiEncryptionProvisionable(true).build());
        assertNotNull(thing.getConfiguration().get("encryptionKey"));
    }

    private Object getField(String name) throws Exception {
        Field field = ESPHomeHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(handler);
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
