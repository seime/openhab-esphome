package no.seime.openhab.binding.esphome.devicetest;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;

import org.junit.jupiter.api.Test;

public class EmptyEntityNamesEsphomeDeviceTest extends AbstractESPHomeDeviceTest {

    protected File getEspDeviceConfigurationYamlFileName() {
        return new File("src/test/resources/device_configurations/empty_entity_names.yaml");
    }

    @Test
    public void testCreateChannelLabels() {

        // Things

        thingHandler.initialize();
        await().until(() -> thingHandler.isInterrogated());

        // Only brightness channel should be created
        assertEquals(5, thingHandler.getDynamicChannels().size());
        assertEquals("Latest Firmware Version", thingHandler.getDynamicChannels().get(0).getLabel());
        assertEquals("Firmware Update Available", thingHandler.getDynamicChannels().get(1).getLabel());
        assertEquals("None", thingHandler.getDynamicChannels().get(2).getLabel());
        assertEquals("None", thingHandler.getDynamicChannels().get(3).getLabel());
        assertEquals("Temperature", thingHandler.getDynamicChannels().get(4).getLabel());

        thingHandler.dispose();
    }
}
