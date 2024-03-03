package no.seime.openhab.binding.esphome.internal;

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

import no.seime.openhab.binding.esphome.internal.comm.ConnectionSelector;
import no.seime.openhab.binding.esphome.internal.comm.LogReadingCommunicationListener;
import no.seime.openhab.binding.esphome.internal.comm.PlainTextFrameHelper;
import no.seime.openhab.binding.esphome.internal.handler.ESPChannelTypeProvider;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

/**
 *
 * @author Arne Seime - Initial contribution
 */

@ExtendWith(MockitoExtension.class)
class ESPHomeHandlerTest {

    private @Mock Configuration configuration;
    private @Mock ESPChannelTypeProvider channelTypeProvider;

    private Thing thing;

    private ESPHomeHandler deviceHandler;

    private ThingHandlerCallback thingHandlerCallback;

    ESPHomeConfiguration deviceConfiguration;

    ConnectionSelector selector;

    @BeforeEach
    public void setUp() throws Exception {

        deviceConfiguration = new ESPHomeConfiguration();
        deviceConfiguration.hostname = "localhost";
        deviceConfiguration.port = 10000;
        when(configuration.as(ESPHomeConfiguration.class)).thenReturn(deviceConfiguration);

        selector = new ConnectionSelector();
        selector.start();

        thing = createThing();
        deviceHandler = Mockito.spy(new ESPHomeHandler(thing, selector, channelTypeProvider));
        thingHandlerCallback = Mockito.mock(ThingHandlerCallback.class);
        deviceHandler.setCallback(thingHandlerCallback);
    }

    @AfterEach
    public void shutdown() {
        selector.stop();
        deviceHandler.dispose();
    }

    @Test
    void testInitializeEverythingPresenceSensor()
            throws IOException, InvocationTargetException, IllegalAccessException {

        ESPHomeEmulator emulator = new ESPHomeEmulator(new InetSocketAddress("localhost", 10000),
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
