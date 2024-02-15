package no.seime.openhab.binding.esphome.internal.comm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.esphome.api.ClimateMode;
import no.seime.openhab.binding.esphome.internal.ClimateEnumHelper;

public class EnumHelperTest {

    @Test
    void stripEnum() {

        assertEquals("COOL", ClimateEnumHelper.stripEnumPrefix(ClimateMode.CLIMATE_MODE_COOL));
    }
}
