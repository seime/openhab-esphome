package no.seime.openhab.binding.esphome.internal.message.deviceclass;

import static org.openhab.core.library.CoreItemFactory.*;

public enum SensorNumberDeviceClass implements DeviceClass {

    // https://www.openhab.org/docs/concepts/units-of-measurement.html
    // https://www.home-assistant.io/integrations/sensor#device-class
    // https://www.openhab.org/docs/configuration/iconsets/classic/
    // Updated as of HA 2025.7.3

    NONE("generic_number", NUMBER, "zoom", null, true),
    ENUM("enum", STRING, "text", null),
    TIMESTAMP("timestamp", DATETIME, "time", null),
    APPARENT_POWER("apparent_power", toItem("Power"), "energy", null),
    AQI("aqi", NUMBER, "smoke", null),
    AREA("area", toItem("Area"), "none", null),

    ATMOSPHERIC_PRESSURE("atmospheric_pressure", toItem("Pressure"), "pressure", null),
    BATTERY("battery", toItem("Dimensionless"), "batterylevel", null),
    BLOOD_GLUCOSE_CONCENTRATION("blood_glucose_concentration", NUMBER, null, null), // No dimension in OH?
    CO("carbon_monoxide", toItem("Dimensionless"), "smoke", "CO"),
    CO2("carbon_dioxide", toItem("Dimensionless"), "carbondioxide", "CO2"),
    CURRENT("current", toItem("ElectricCurrent"), "energy", "Current"),

    DATA_RATE("data_rate", toItem("DataTransferRate"), null, null),
    DATA_SIZE("data_size", toItem("DataAmount"), null, null),
    DATE("date", DATETIME, "time", null),
    DISTANCE("distance", toItem("Length"), null, null),
    DURATION("duration", toItem("Time"), "time", null),
    ENERGY("energy", toItem("Energy"), "energy", "Energy"),
    ENERGY_DISTANCE("energy_distance", toItem("Energy"), "energy", "Energy"), // Not sure which dimension to use
    ENERGY_STORAGE("energy_storage", toItem("Energy"), "energy", "Energy"),
    FREQUENCY("frequency", toItem("Frequency"), null, "Frequency"),

    GAS("gas", toItem("Volume"), "gas", "Gas"),
    HUMIDITY("humidity", toItem("Dimensionless"), "humidity", "Humidity"),
    ILLUMINANCE("illuminance", toItem("Illuminance"), "lightbulb", "Light"),
    IRRADIANCE("irradiance", toItem("Intensity"), null, null),
    MOISTURE("moisture", toItem("Dimensionless"), "water", "Humidity"),
    MONETARY("monetary", NUMBER, null, null), // TODO: Add Monetary type
                                              // https://github.com/openhab/openhab-core/issues/3408
    NITROGEN_DIOXIDE("nitrogen_dioxide", toItem("Dimensionless"), "smoke", null),
    NITROGEN_MONOXIDE("nitrogen_monoxide", NUMBER, "smoke", null),
    NITROUS_OXIDE("nitrous_oxide", NUMBER, "smoke", null),

    OZONE("ozone", NUMBER, "smoke", null),
    PH("ph", NUMBER, null, null),
    PM1("pm1", NUMBER, "smoke", null),
    PM10("pm10", NUMBER, "smoke", null),
    PM25("pm25", NUMBER, "smoke", null),
    POWER_FACTOR("power_factor", toItem("Dimensionless"), "energy", "Power"),
    POWER("power", toItem("Power"), "energy", "Power"),
    PRECIPITATION("precipitation", toItem("Length"), "rain", "Rain"),
    PRECIPITATION_RATE("precipitation_rate", toItem("Speed"), "rain", "Rain"), // No longer in HA?
    PRECIPITATION_INTENSITY("precipitation_intensity", toItem("Speed"), "rain", "Rain"),
    PRESSURE("pressure", toItem("Pressure"), "pressure", "Pressure"),
    REACTIVE_ENERGY("reactive_energy", toItem("Energy"), "energy", "Energy"),
    REACTIVE_POWER("reactive_power", toItem("Power"), "energy", "Power"),
    SIGNAL_STRENGTH("signal_strength", toItem("Power"), "qualityofservice", null),

    SOUND_PRESSURE("sound_pressure", toItem("Dimensionless"), "soundvolume", "SoundVolume"),
    SPEED("speed", toItem("Speed"), "motion", null),
    SULPHUR_DIOXIDE("sulphur_dioxide", NUMBER, "smoke", null),
    TEMPERATURE("temperature", toItem("Temperature"), "temperature", "Temperature"),
    VOLATILE_ORGANIC_COMPOUNDS("volatile_organic_compounds", NUMBER, "smoke", null),
    VOLATILE_ORGANIC_COMPOUNDS_PARTS("volatile_organic_compounds_parts", toItem("Dimensionless"), "smoke", null),
    VOLTAGE("voltage", toItem("ElectricPotential"), "energy", "Voltage"),
    VOLUME("volume", toItem("Volume"), null, null),
    VOLUME_STORAGE("volume_storage", toItem("Volume"), null, null),
    WATER("water", toItem("Volume"), "water", "Water"),
    WEIGHT("weight", toItem("Force"), null, null),
    WIND_DIRECTION("wind_direction", toItem("Angle"), "wind", "Wind"),
    WIND_SPEED("wind_speed", toItem("Speed"), "wind", "Wind");

    private static String toItem(String dimension) {
        return String.format("%s:%s", NUMBER, dimension);
    }

    private final String deviceClass;
    private final String itemType;
    private final String category;
    private final String semanticType;
    private final boolean defaultDeviceClass;

    public static SensorNumberDeviceClass fromDeviceClass(String deviceClass) {
        if ("".equals(deviceClass)) {
            return NONE; // Default to NONE if deviceClass is empty
        }
        for (SensorNumberDeviceClass sensorDeviceClass : SensorNumberDeviceClass.values()) {
            if (sensorDeviceClass.getDeviceClass().equals(deviceClass)) {
                return sensorDeviceClass;
            }
        }
        return null;
    }

    SensorNumberDeviceClass(String deviceClass, String itemType, String category, String semanticType) {
        this.deviceClass = deviceClass;
        this.itemType = itemType;
        this.category = category;
        this.semanticType = semanticType;
        this.defaultDeviceClass = false;
    }

    SensorNumberDeviceClass(String deviceClass, String itemType, String category, String semanticType,
            boolean defaultDeviceClass) {
        this.deviceClass = deviceClass;
        this.itemType = itemType;
        this.category = category;
        this.semanticType = semanticType;

        this.defaultDeviceClass = defaultDeviceClass;
    }

    @Override
    public String getDeviceClass() {
        return deviceClass;
    }

    @Override
    public String getSemanticType() {
        return semanticType;
    }

    @Override
    public boolean isDefault() {
        return defaultDeviceClass;
    }

    @Override
    public String getItemType() {
        return itemType;
    }

    @Override
    public String getCategory() {
        return category;
    }

    @Override
    public String toString() {
        return "SensorNumberDeviceClass{" + "deviceClass='" + deviceClass + '\'' + ", itemType='" + itemType + '\''
                + ", category='" + category + '\'' + ", measurementType='" + semanticType + '\'' + '}';
    }
}
