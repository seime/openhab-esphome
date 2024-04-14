package no.seime.openhab.binding.esphome.internal;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.internal.ThingImpl;

import no.seime.openhab.binding.esphome.internal.comm.ConnectionSelector;
import no.seime.openhab.binding.esphome.internal.handler.ESPChannelTypeProvider;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.ESPHomeEventSubscriber;

/**
 *
 * @author Arne Seime - Initial contribution
 */

@ExtendWith(MockitoExtension.class)
class ESPHomeHandlerLiveTest {

    private @Mock Configuration configuration;
    private @Mock ESPChannelTypeProvider channelTypeProvider;

    private @Mock ESPHomeEventSubscriber eventSubscriber;

    private Thing thing;

    private ESPHomeHandler deviceHandler;

    private ThingHandlerCallback thingHandlerCallback;

    ESPHomeConfiguration deviceConfiguration;

    ConnectionSelector selector;

    @BeforeEach
    public void setUp() throws Exception {

        deviceConfiguration = new ESPHomeConfiguration();
        deviceConfiguration.hostname = "localhost";
        deviceConfiguration.port = 6053;
        // deviceConfiguration.password = "MyPassword";
        deviceConfiguration.encryptionKey = "MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA=";
        deviceConfiguration.server = "virtual";
        when(configuration.as(ESPHomeConfiguration.class)).thenReturn(deviceConfiguration);

        selector = new ConnectionSelector();
        selector.start();

        thing = createThing();
        deviceHandler = Mockito.spy(new ESPHomeHandler(thing, selector, channelTypeProvider, eventSubscriber));
        thingHandlerCallback = Mockito.mock(ThingHandlerCallback.class);
        deviceHandler.setCallback(thingHandlerCallback);
    }

    @AfterEach
    public void shutdown() {
        selector.stop();
        deviceHandler.dispose();
    }

    // @Test
    public void testConnectToDeviceOnLocalhost() {
        deviceHandler.initialize();
        await().until(() -> deviceHandler.isInterrogated());
        assertEquals(1, deviceHandler.getDynamicChannels().size());
        deviceHandler.dispose();
    }

    private ThingImpl createThing() {
        ThingImpl thing = new ThingImpl(BindingConstants.THING_TYPE_DEVICE, "device");

        thing.setConfiguration(configuration);
        return thing;
    }
}
