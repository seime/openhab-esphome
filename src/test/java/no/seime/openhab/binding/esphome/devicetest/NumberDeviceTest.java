package no.seime.openhab.binding.esphome.devicetest;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.types.StateDescription;

public class NumberDeviceTest extends AbstractESPHomeDeviceTest {

    @Override
    protected File getEspDeviceConfigurationYamlFileName() {
        return new File("src/test/resources/device_configurations/number_unit_gal.yaml");
    }

    @Test
    public void testNumberStateDescriptionForWholeGallons() {
        thingHandler.initialize();
        await().until(() -> thingHandler.isInterrogated());

        assertEquals(3, thingHandler.getDynamicChannels().size());

        verify(stateDescriptionProvider, timeout(2000)).setDescription(
                eq(new ChannelUID(thing.getUID(), "water_total")),
                argThat((StateDescription stateDescription) -> isWholeGallonsStateDescription(stateDescription)));
    }

    private boolean isWholeGallonsStateDescription(StateDescription stateDescription) {
        return stateDescription != null && "%.0f gal".equals(stateDescription.getPattern());
    }
}
