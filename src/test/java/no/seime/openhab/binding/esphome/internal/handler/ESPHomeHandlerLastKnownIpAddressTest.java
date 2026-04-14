package no.seime.openhab.binding.esphome.internal.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.internal.ThingImpl;
import org.osgi.framework.BundleContext;

import com.jano7.executor.KeySequentialExecutor;

import io.esphome.api.DeviceInfoResponse;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.comm.ConnectionSelector;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
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

    private ESPHomeHandler handler;
    private ThingImpl thing;
    private MonitoredScheduledThreadPoolExecutor executor;
    private ExecutorService packetProcessorExecutor;

    @BeforeEach
    void setUp() throws Exception {
        thing = new ThingImpl(BindingConstants.THING_TYPE_DEVICE, "device");
        executor = new MonitoredScheduledThreadPoolExecutor(1, Executors.defaultThreadFactory(), 1000);
        packetProcessorExecutor = Executors.newSingleThreadExecutor();
        handler = new ESPHomeHandler(thing, new ConnectionSelector(), channelTypeProvider, stateDescriptionProvider,
                eventSubscriber, executor, new KeySequentialExecutor(packetProcessorExecutor), eventPublisher, null,
                bundleContext);
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
    void throwsWhenHostnameResolutionFailsWithoutCachedIpAddress() {
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
