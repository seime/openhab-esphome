package no.seime.openhab.binding.esphome.devicetest;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.io.File;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.items.SwitchItem;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.ChannelUID;

public class JeffJamesOpenhabStateToEsphomeDeviceTest extends AbstractESPHomeDeviceTest {

    protected File getEspDeviceConfigurationYamlFileName() {
        return new File("src/test/resources/device_configurations/jeff_james_binary_sensor_homeassistant.yaml");
    }

    @Test
    @Disabled // Copy sensor does not seem to update
    public void testJeffJamesBinarySensor() throws ItemNotFoundException {

        registerItem(new SwitchItem("Pool_Zero_Flow_Sensor"), OnOffType.ON);

        thingHandler.initialize();
        await().until(() -> thingHandler.isInterrogated());
        assertEquals(1, thingHandler.getDynamicChannels().size());

        // Verify that initial state is copied via the copy sensor and transferred back
        verify(thingHandlerCallback, timeout(2000))
                .stateUpdated(eq(new ChannelUID(thing.getUID(), "set_sensor_zero_readback")), eq(OnOffType.ON));

        // Send the new state to the event subscriber, verify that the copy sensor sends the new state back
        eventSubscriber
                .receive(ItemEventFactory.createStateChangedEvent("ItemStateChanged", OnOffType.OFF, OnOffType.ON));
        verify(thingHandlerCallback, timeout(2000))
                .stateUpdated(eq(new ChannelUID(thing.getUID(), "set_sensor_zero_readback")), eq(OnOffType.OFF));

        thingHandler.dispose();
    }
}
