package no.seime.openhab.binding.esphome.devicetest;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;

import org.junit.jupiter.api.Test;

public class ThingActionEsphomeDeviceTest extends AbstractESPHomeDeviceTest {

    protected File getEspDeviceConfigurationYamlFileName() {
        return new File("src/test/resources/device_configurations/thing_action.yaml");
    }

    @Test
    public void testThingAction() {

        // Things

        thingHandler.initialize();
        await().until(() -> thingHandler.isInterrogated());

        assertEquals(0, thingHandler.getDynamicChannels().size());

        // TODO check for thing action created

        thingHandler.dispose();
    }
}
