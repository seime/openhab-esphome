package no.seime.openhab.binding.esphome.internal.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import java.util.Base64;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.types.RefreshType;

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
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.EventSubscription;

class ESPHomeHandlerProvisioningTest extends AbstractESPHomeHandlerTest {

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
}
