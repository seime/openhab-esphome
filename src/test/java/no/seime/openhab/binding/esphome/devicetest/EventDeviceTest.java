package no.seime.openhab.binding.esphome.devicetest;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.ChannelUID;

public class EventDeviceTest extends AbstractESPHomeDeviceTest {

    @Override
    protected File getEspDeviceConfigurationYamlFileName() {
        return new File("src/test/resources/device_configurations/events.yaml");
    }

    @Test
    public void testEvents() {
        thingHandler.initialize();
        await().until(() -> thingHandler.isInterrogated());

        // 2 Events, 1 Switch
        assertEquals(3, thingHandler.getDynamicChannels().size());

        ChannelUID switchChannelUID = new ChannelUID(thing.getUID(), "trigger_scene_dag");
        ChannelUID eventChannelUID = new ChannelUID(thing.getUID(), "scene_dag");

        // Trigger the switch
        thingHandler.handleCommand(switchChannelUID, OnOffType.ON);

        // Expect the event channel to be triggered with "dag"
        verify(thingHandlerCallback, timeout(5000)).channelTriggered(eq(thing), eq(eventChannelUID), eq("dag"));

        thingHandler.dispose();
    }
}
