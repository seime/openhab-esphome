package no.seime.openhab.binding.esphome.internal.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.esphome.api.DeviceInfoResponse;
import no.seime.openhab.binding.esphome.internal.*;
import no.seime.openhab.binding.esphome.internal.comm.ConnectionTargetResolver.ResolvedConnectionTarget;

class ESPHomeHandlerLastKnownIpAddressTest extends AbstractESPHomeHandlerTest {

    @Test
    void removesCachedIpAddressWhenConfiguredHostnameIsLiteralIp() throws Exception {
        thing.setProperties(Map.of(BindingConstants.PROPERTY_LAST_KNOWN_IP_ADDRESS, "127.0.0.1"));
        ResolvedConnectionTarget target = new ResolvedConnectionTarget("192.0.2.55", "192.0.2.55", "192.0.2.55",
                "192.0.2.55", false);

        invokeMethod("applyLastKnownIpAddressPolicy", new Class<?>[] { ResolvedConnectionTarget.class }, target);

        verify(callback).thingUpdated(argThat(updatedThing -> !updatedThing.getProperties()
                .containsKey(BindingConstants.PROPERTY_LAST_KNOWN_IP_ADDRESS)));
    }

    @Test
    void persistsResolvedIpAddressOnThingUpdate() throws Exception {
        setField("resolvedIpAddressForCurrentConnection", "127.0.0.1");

        invokeMethod("persistLastKnownIpAddress", new Class<?>[0]);

        verify(callback).thingUpdated(argThat(updatedThing -> "127.0.0.1"
                .equals(updatedThing.getProperties().get(BindingConstants.PROPERTY_LAST_KNOWN_IP_ADDRESS))));
    }

    @Test
    void doesNotPersistResolvedIpAddressWhenLiteralIpModeIsActive() throws Exception {
        thing.setProperties(Map.of(BindingConstants.PROPERTY_LAST_KNOWN_IP_ADDRESS, "127.0.0.1"));
        setField("resolvedIpAddressForCurrentConnection", null);

        invokeMethod("persistLastKnownIpAddress", new Class<?>[0]);

        verifyNoMoreInteractions(callback);
    }

    @Test
    void preservesCachedIpAddressWhenDeviceInfoUpdatesProperties() throws Exception {
        thing.setProperties(Map.of(BindingConstants.PROPERTY_LAST_KNOWN_IP_ADDRESS, "127.0.0.1"));

        invokeMethod("handleConnected", new Class<?>[] { com.google.protobuf.GeneratedMessage.class },
                DeviceInfoResponse.newBuilder().setEsphomeVersion("2026.1.0").setMacAddress("AA:BB:CC:DD:EE:FF")
                        .setModel("ESP32").setName("virtual").setManufacturer("Espressif")
                        .setCompilationTime("2026-04-14T00:00:00Z").build());

        verify(callback).thingUpdated(argThat(updatedThing -> "127.0.0.1"
                .equals(updatedThing.getProperties().get(BindingConstants.PROPERTY_LAST_KNOWN_IP_ADDRESS))
                && "virtual".equals(updatedThing.getProperties().get("name"))));
    }
}
