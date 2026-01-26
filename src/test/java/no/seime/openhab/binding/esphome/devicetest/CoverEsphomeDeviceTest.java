package no.seime.openhab.binding.esphome.devicetest;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.UpDownType;
import org.openhab.core.thing.ChannelUID;

import tech.units.indriya.unit.Units;

public class CoverEsphomeDeviceTest extends AbstractESPHomeDeviceTest {

    public static final String POSITION_CHANNEL = "time-based_cover#position";

    protected File getEspDeviceConfigurationYamlFileName() {
        return new File("src/test/resources/device_configurations/cover.yaml");
    }

    @Test
    public void testCoverBehaviour() {

        thingHandler.initialize();
        await().until(() -> thingHandler.isInterrogated());

        // Open switch
        // Close switch
        // Cover position
        // Cover state

        assertEquals(4, thingHandler.getDynamicChannels().size());

        // Send using PercentType
        // Remove all other invocations for log readability
        reset(thingHandlerCallback);
        thingHandler.handleCommand(new ChannelUID(thing.getUID(), POSITION_CHANNEL), new PercentType(20));

        verify(thingHandlerCallback, timeout(3000)).stateUpdated(eq(new ChannelUID(thing.getUID(), POSITION_CHANNEL)),
                percentCloseTo(20, 2f));

        // Send using PercentType
        reset(thingHandlerCallback);
        thingHandler.handleCommand(new ChannelUID(thing.getUID(), POSITION_CHANNEL), new PercentType(80));

        verify(thingHandlerCallback, timeout(3000)).stateUpdated(eq(new ChannelUID(thing.getUID(), POSITION_CHANNEL)),
                percentCloseTo(80, 2f));

        // Send using DecimalType
        reset(thingHandlerCallback);
        thingHandler.handleCommand(new ChannelUID(thing.getUID(), POSITION_CHANNEL), new DecimalType(20));

        verify(thingHandlerCallback, timeout(3000)).stateUpdated(eq(new ChannelUID(thing.getUID(), POSITION_CHANNEL)),
                percentCloseTo(20, 2f));

        // Send using QuantityType
        reset(thingHandlerCallback);
        thingHandler.handleCommand(new ChannelUID(thing.getUID(), POSITION_CHANNEL),
                new QuantityType<>(80, Units.PERCENT));

        verify(thingHandlerCallback, timeout(3000)).stateUpdated(eq(new ChannelUID(thing.getUID(), POSITION_CHANNEL)),
                percentCloseTo(80, 2f));

        // Send down
        reset(thingHandlerCallback);
        thingHandler.handleCommand(new ChannelUID(thing.getUID(), POSITION_CHANNEL), UpDownType.DOWN);

        verify(thingHandlerCallback, timeout(3000)).stateUpdated(eq(new ChannelUID(thing.getUID(), POSITION_CHANNEL)),
                eq(new PercentType(100)));

        // Send up
        reset(thingHandlerCallback);
        thingHandler.handleCommand(new ChannelUID(thing.getUID(), POSITION_CHANNEL), UpDownType.UP);

        verify(thingHandlerCallback, timeout(3000)).stateUpdated(eq(new ChannelUID(thing.getUID(), POSITION_CHANNEL)),
                eq(new PercentType(0)));

        thingHandler.dispose();
    }

    public static PercentType percentCloseTo(float expected, float tolerance) {
        // Since the cover is time based with short opening/closing time, expect some inaccuracy
        return argThat(p -> p != null && Math.abs(p.floatValue() - expected) <= tolerance);
    }
}
