package no.seime.openhab.binding.esphome.devicetest;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.osgi.framework.ServiceRegistration;

public class ThingActionEsphomeDeviceTest extends AbstractESPHomeDeviceTest {

    protected File getEspDeviceConfigurationYamlFileName() {
        return new File("src/test/resources/device_configurations/thing_action.yaml");
    }

    @Test
    public void testThingAction() {

        doReturn(mock(ServiceRegistration.class)).when(bundleContext).registerService(anyString(),
                ArgumentMatchers.any(), ArgumentMatchers.any());

        thingHandler.initialize();
        await().until(() -> thingHandler.isInterrogated());

        assertEquals(0, thingHandler.getDynamicChannels().size());
        assertEquals(1, thingHandler.countServiceRegistrations());

        thingHandler.dispose();
    }
}
