package no.seime.openhab.binding.esphome.devicetest;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;

import org.junit.jupiter.api.Test;

public class DuplicateEntityNamesEsphomeDeviceTest extends AbstractESPHomeDeviceTest {

    protected File getEspDeviceConfigurationYamlFileName() {
        return new File("src/test/resources/device_configurations/duplicate_entity_names.yaml");
    }

    @Test
    public void testCreateChannelUID() {

        // Things

        thingHandler.initialize();
        await().until(() -> thingHandler.isInterrogated());

        // Only brightness channel should be created
        assertEquals(4, thingHandler.getDynamicChannels().size());
        assertEquals("latestFirmwareVersion", thingHandler.getDynamicChannels().get(0).getUID().getId());
        assertEquals("firmwareUpdateAvailable", thingHandler.getDynamicChannels().get(1).getUID().getId());
        assertEquals("climate", thingHandler.getDynamicChannels().get(2).getUID().getId());
        assertEquals("climate_sensor", thingHandler.getDynamicChannels().get(3).getUID().getId());

        thingHandler.dispose();
    }
}
