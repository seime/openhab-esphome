package no.seime.openhab.binding.esphome.devicetest;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.types.UpDownType;
import org.openhab.core.thing.ChannelUID;

public class ValveDeviceTest extends AbstractESPHomeDeviceTest {

    protected File getEspDeviceConfigurationYamlFileName() {
        return new File("src/test/resources/device_configurations/valve.yaml");
    }

    @Test
    public void testValve() {

        thingHandler.initialize();
        await().until(() -> thingHandler.isInterrogated());
        // Valve 2, Switch 1
        assertEquals(3, thingHandler.getDynamicChannels().size());

        // Valve will stay in IDLE all the time
        verify(thingHandlerCallback, timeout(2000)).stateUpdated(
                eq(new ChannelUID(thing.getUID(), "valve#current_operation")), eq(StringType.valueOf("IDLE")));

        ChannelUID positionChannelUID = new ChannelUID(thing.getUID(), "valve#position");

        // Start by opening the valuve
        thingHandler.handleCommand(positionChannelUID, UpDownType.UP);
        verify(thingHandlerCallback, timeout(2000)).stateUpdated(eq(positionChannelUID), eq(PercentType.ZERO));

        // Then closing the valve
        thingHandler.handleCommand(positionChannelUID, UpDownType.DOWN);
        verify(thingHandlerCallback, timeout(2000)).stateUpdated(eq(positionChannelUID), eq(PercentType.HUNDRED));

        thingHandler.dispose();
    }
}
