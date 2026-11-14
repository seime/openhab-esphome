package no.seime.openhab.binding.esphome.internal.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.core.audio.AudioHTTPServer;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.net.NetworkAddressService;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.internal.ThingImpl;
import org.osgi.framework.BundleContext;

import com.jano7.executor.KeySequentialExecutor;

import no.seime.openhab.binding.esphome.internal.*;
import no.seime.openhab.binding.esphome.internal.comm.ConnectionSelector;
import no.seime.openhab.binding.esphome.internal.comm.EncryptedFrameHelper;
import no.seime.openhab.binding.esphome.internal.discovery.ESPHomeMDNSHostnameResolver;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.ESPHomeEventSubscriber;

@ExtendWith(MockitoExtension.class)
abstract class AbstractESPHomeHandlerTest {

    @Mock
    protected ESPChannelTypeProvider channelTypeProvider;
    @Mock
    protected ESPStateDescriptionProvider stateDescriptionProvider;
    @Mock
    protected ESPHomeEventSubscriber eventSubscriber;
    @Mock
    protected EventPublisher eventPublisher;
    @Mock
    protected BundleContext bundleContext;
    @Mock
    protected ThingHandlerCallback callback;
    @Mock
    protected ESPHomeMDNSHostnameResolver mdnsHostnameResolver;
    @Mock
    protected EncryptedFrameHelper frameHelper;
    @Mock
    protected ESPHomeVersionService versionService;
    @Mock
    protected AudioHTTPServer audioHTTPServer;
    @Mock
    protected NetworkAddressService networkAddressService;

    @Mock
    protected ManagedThingProvider managedThingProvider;

    protected ESPHomeHandler handler;
    protected ThingImpl thing;
    protected MonitoredCompositeExecutorService executor;
    protected ExecutorService packetProcessorExecutor;

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

    protected Object getField(String name) throws Exception {
        Field field = ESPHomeHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(handler);
    }

    protected void setConnected() throws Exception {
        Field field = ESPHomeHandler.class.getDeclaredField("connectionState");
        for (Object state : field.getType().getEnumConstants()) {
            if ("CONNECTED".equals(state.toString())) {
                setField("connectionState", state);
                return;
            }
        }
        fail("CONNECTED state missing");
    }

    protected Object invokeMethod(String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = ESPHomeHandler.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(handler, args);
    }

    protected Object invokeRecordAccessor(Object target, String accessorName) throws Exception {
        Method accessor = target.getClass().getDeclaredMethod(accessorName);
        accessor.setAccessible(true);
        return accessor.invoke(target);
    }

    protected void setField(String name, Object value) throws Exception {
        Field field = ESPHomeHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(handler, value);
    }
}
