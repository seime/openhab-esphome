package no.seime.openhab.binding.esphome.devicetest;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.internal.ThingImpl;

import no.seime.openhab.binding.esphome.deviceutil.ESPHomeLogReadingEmulator;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.ESPHomeConfiguration;
import no.seime.openhab.binding.esphome.internal.comm.ConnectionSelector;
import no.seime.openhab.binding.esphome.internal.comm.LogReadingCommunicationListener;
import no.seime.openhab.binding.esphome.internal.comm.PlainTextFrameHelper;
import no.seime.openhab.binding.esphome.internal.handler.ESPChannelTypeProvider;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;
import no.seime.openhab.binding.esphome.internal.handler.MonitoredScheduledThreadPoolExecutor;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.ESPHomeEventSubscriber;

/**
 *
 * @author Arne Seime - Initial contribution
 */

@ExtendWith(MockitoExtension.class)
class EverythingOnePresesenceTest {

    ESPHomeConfiguration deviceConfiguration;
    ConnectionSelector selector;
    MonitoredScheduledThreadPoolExecutor executor;
    private @Mock Configuration configuration;
    private @Mock ESPChannelTypeProvider channelTypeProvider;
    private @Mock ESPHomeEventSubscriber eventSubscriber;
    private Thing thing;
    private ESPHomeHandler deviceHandler;
    private ThingHandlerCallback thingHandlerCallback;

    @BeforeEach
    public void setUp() throws Exception {
        executor = new MonitoredScheduledThreadPoolExecutor(1, r -> new Thread(r), 1000);
        deviceConfiguration = new ESPHomeConfiguration();
        deviceConfiguration.hostname = "localhost";
        deviceConfiguration.port = 10000;
        when(configuration.as(ESPHomeConfiguration.class)).thenReturn(deviceConfiguration);

        selector = new ConnectionSelector();
        selector.start();

        thing = createThing();
        deviceHandler = Mockito
                .spy(new ESPHomeHandler(thing, selector, channelTypeProvider, eventSubscriber, executor));
        thingHandlerCallback = Mockito.mock(ThingHandlerCallback.class);
        deviceHandler.setCallback(thingHandlerCallback);
    }

    @AfterEach
    public void shutdown() {
        selector.stop();
        deviceHandler.dispose();
        executor.shutdownNow();
    }

    @Test
    void testInitializeEverythingPresenceSensor()
            throws IOException, InvocationTargetException, IllegalAccessException {

        ESPHomeLogReadingEmulator emulator = new ESPHomeLogReadingEmulator(new InetSocketAddress("localhost", 10000),
                new PlainTextFrameHelper(null, null, "emulator"));

        emulator.setPacketListener(new LogReadingCommunicationListener(emulator,
                new File("src/test/resources/logfiles/presence_sensor.log")));
        emulator.start();

        deviceHandler.initialize();

        await().until(() -> deviceHandler.isInterrogated());
        assertEquals(18, deviceHandler.getDynamicChannels().size());
    }

    private ThingImpl createThing() {
        ThingImpl thing = new ThingImpl(BindingConstants.THING_TYPE_DEVICE, "device");

        thing.setConfiguration(configuration);
        return thing;
    }
}
