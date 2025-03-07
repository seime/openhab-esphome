package no.seime.openhab.binding.esphome.devicetest;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;

import org.junit.jupiter.api.Test;

public class LightEsphomeDeviceTest extends AbstractESPHomeDeviceTest {

    protected File getEspDeviceConfigurationYamlFileName() {
        return new File("src/test/resources/device_configurations/light.yaml");
    }

    @Test
    public void testLightComponent() {

        // Things

        thingHandler.initialize();
        await().until(() -> thingHandler.isInterrogated());

        // Only brightness channel should be created
        assertEquals(1, thingHandler.getDynamicChannels().size());

        thingHandler.dispose();
    }
}
