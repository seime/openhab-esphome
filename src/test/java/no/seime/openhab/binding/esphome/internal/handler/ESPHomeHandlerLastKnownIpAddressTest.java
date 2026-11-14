package no.seime.openhab.binding.esphome.internal.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.esphome.api.DeviceInfoResponse;
import no.seime.openhab.binding.esphome.internal.*;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;

class ESPHomeHandlerLastKnownIpAddressTest extends AbstractESPHomeHandlerTest {

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
}
