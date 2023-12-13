package no.seime.openhab.binding.esphome.internal;

import io.esphome.api.ClimateFanMode;
import io.esphome.api.ClimateMode;
import io.esphome.api.ClimatePreset;
import io.esphome.api.ClimateSwingMode;

public class ClimateEnumHelper {
    public static String stripEnumPrefix(ClimateSwingMode mode) {
        String toRemove = "CLIMATE_SWING";
        return mode.toString().substring(toRemove.length() + 1);
    }

    public static String stripEnumPrefix(ClimateFanMode mode) {
        String toRemove = "CLIMATE_FAN";
        return mode.toString().substring(toRemove.length() + 1);
    }

    public static String stripEnumPrefix(ClimateMode climateMode) {
        String toRemove = "CLIMATE_MODE";
        return climateMode.toString().substring(toRemove.length() + 1);
    }

    public static String stripEnumPrefix(ClimatePreset climatePreset) {
        String toRemove = "CLIMATE_PRESET";
        return climatePreset.toString().substring(toRemove.length() + 1);
    }

    public static ClimateFanMode toFanMode(String fanMode) {
        return ClimateFanMode.valueOf("CLIMATE_FAN_" + fanMode.toUpperCase());
    }

    public static ClimatePreset toClimatePreset(String climatePreset) {
        return ClimatePreset.valueOf("CLIMATE_PRESET_" + climatePreset.toUpperCase());
    }

    public static ClimateMode toClimateMode(String mode) {
        return ClimateMode.valueOf("CLIMATE_MODE_" + mode.toUpperCase());
    }

    public static ClimateSwingMode toClimateSwingMode(String mode) {
        return ClimateSwingMode.valueOf("CLIMATE_SWING_" + mode.toUpperCase());
    }
}
