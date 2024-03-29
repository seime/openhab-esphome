package no.seime.openhab.binding.esphome.internal.comm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.esphome.api.ClimateMode;
import no.seime.openhab.binding.esphome.internal.message.ClimateMessageHandler;

public class EnumHelperTest {

    @Test
    void stripEnum() {

        assertEquals("COOL", ClimateMessageHandler.ClimateEnumHelper.stripEnumPrefix(ClimateMode.CLIMATE_MODE_COOL));
    }
}
