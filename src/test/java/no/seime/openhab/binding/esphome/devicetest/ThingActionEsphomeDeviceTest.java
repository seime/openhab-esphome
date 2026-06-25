package no.seime.openhab.binding.esphome.devicetest;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openhab.core.thing.binding.ThingActions;
import org.osgi.framework.ServiceRegistration;

public class ThingActionEsphomeDeviceTest extends AbstractESPHomeDeviceTest {

    protected File getEspDeviceConfigurationYamlFileName() {
        return new File("src/test/resources/device_configurations/thing_action.yaml");
    }

    @Test
    public void testThingAction() {

        doReturn(mock(ServiceRegistration.class)).when(bundleContext).registerService(eq(ThingActions.class),
                any(ThingActions.class), any());

        thingHandler.initialize();
        await().until(() -> thingHandler.isInterrogated());

        assertEquals(2, thingHandler.getDynamicChannels().size());

        ArgumentCaptor<ThingActions> captor = ArgumentCaptor.forClass(ThingActions.class);
        verify(bundleContext, times(3)).registerService(eq(ThingActions.class), captor.capture(), any());

        List<ThingActions> capturedActions = captor.getAllValues();

        assertTrue(capturedActions.stream().anyMatch(a -> a.getClass().getName()
                .equals("no.seime.openhab.binding.esphome.internal.handler.action.FirmwareUpgradeAction")));
        assertTrue(capturedActions.stream().anyMatch(a -> a.getClass().getName()
                .equals("no.seime.openhab.binding.esphome.internal.handler.action.SnakeCaseAction")));
        assertTrue(capturedActions.stream().anyMatch(a -> a.getClass().getName()
                .equals("no.seime.openhab.binding.esphome.internal.handler.action.SimpleAction")));

        thingHandler.dispose();
    }
}
