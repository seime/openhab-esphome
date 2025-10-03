package no.seime.openhab.binding.esphome.devicetest;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.items.GenericItem;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.internal.ThingImpl;
import org.openhab.core.types.State;

import com.jano7.executor.KeySequentialExecutor;

import no.seime.openhab.binding.esphome.deviceutil.ESPHomeDeviceRunner;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.ESPHomeConfiguration;
import no.seime.openhab.binding.esphome.internal.LogLevel;
import no.seime.openhab.binding.esphome.internal.comm.ConnectionSelector;
import no.seime.openhab.binding.esphome.internal.handler.ESPChannelTypeProvider;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;
import no.seime.openhab.binding.esphome.internal.handler.ESPStateDescriptionProvider;
import no.seime.openhab.binding.esphome.internal.handler.MonitoredScheduledThreadPoolExecutor;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.ESPHomeEventSubscriber;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public abstract class AbstractESPHomeDeviceTest {

    private final MonitoredScheduledThreadPoolExecutor executor = new MonitoredScheduledThreadPoolExecutor(2,
            r -> new Thread(r), 1000);
    private final MonitoredScheduledThreadPoolExecutor packetProcessor = new MonitoredScheduledThreadPoolExecutor(1,
            r -> new Thread(r), 1000);
    private final List<Item> registryItems = new ArrayList<>();
    protected ESPHomeHandler thingHandler;
    protected ThingHandlerCallback thingHandlerCallback;
    protected ThingImpl thing;
    protected @Mock ItemRegistry itemRegistry;
    protected @Mock ThingRegistry thingRegistry;
    protected @Mock EventPublisher eventPublisher;
    protected ESPHomeEventSubscriber eventSubscriber;
    private ESPHomeConfiguration deviceConfiguration;
    private ConnectionSelector selector;
    private @Mock Configuration configuration;
    private @Mock ESPChannelTypeProvider channelTypeProvider;
    private @Mock ESPStateDescriptionProvider stateDescriptionProvider;
    private ESPHomeDeviceRunner emulator;

    @BeforeEach
    public void setUp() throws Exception {
        registryItems.clear();

        emulator = new ESPHomeDeviceRunner(getEspDeviceConfigurationYamlFileName());
        emulator.compileAndRun();

        deviceConfiguration = new ESPHomeConfiguration();
        deviceConfiguration.hostname = "localhost";
        deviceConfiguration.port = 6053;
        deviceConfiguration.encryptionKey = "TiFvlzL9tNB29cys/ZR4o+YYHvwawrTF8csI13hZaPw=";
        deviceConfiguration.deviceId = "virtual";
        deviceConfiguration.deviceLogLevel = LogLevel.VERY_VERBOSE;
        when(configuration.as(ESPHomeConfiguration.class)).thenReturn(deviceConfiguration);

        selector = new ConnectionSelector();
        selector.start();

        thing = new ThingImpl(BindingConstants.THING_TYPE_DEVICE, "device");
        thing.setConfiguration(configuration);

        when(itemRegistry.getItems()).thenReturn(registryItems);
        eventSubscriber = new ESPHomeEventSubscriber(thingRegistry, itemRegistry);

        thingHandler = new ESPHomeHandler(thing, selector, channelTypeProvider, stateDescriptionProvider,
                eventSubscriber, executor, new KeySequentialExecutor(executor), eventPublisher, null);
        thingHandlerCallback = Mockito.mock(ThingHandlerCallback.class);
        thingHandler.setCallback(thingHandlerCallback);
    }

    protected abstract File getEspDeviceConfigurationYamlFileName();

    @AfterEach
    public void shutdown() throws InterruptedException {
        if (thingHandler != null)
            thingHandler.dispose();
        if (selector != null)
            selector.stop();
        if (emulator != null)
            emulator.shutdown();
        if (executor != null)
            executor.shutdownNow();
    }

    protected void registerItem(GenericItem item, State initialState) throws ItemNotFoundException {
        doReturn(item).when(itemRegistry).getItem(eq(item.getName()));
        item.setState(initialState);
        registryItems.add(item);
    }

    protected void registerThing(ThingUID thingUID, ThingStatusInfo thingExistingStatusInfo) {
        Thing thing = new ThingImpl(new ThingTypeUID(thingUID.toString()), thingUID);
        thing.setStatusInfo(thingExistingStatusInfo);
        doReturn(thing).when(thingRegistry).get(eq(thingUID));
    }
}
