package no.seime.openhab.binding.esphome.internal.message;

import static org.openhab.core.library.CoreItemFactory.CONTACT;
import static org.openhab.core.library.CoreItemFactory.SWITCH;

public enum BinarySensorDeviceClass {
    // Generic binary sensor with no specific context. 0: Off, 1: On.
    GENERIC("generic", SWITCH, "none", null),

    // Indicates low battery status. 0: Battery not low, 1: Battery low.
    BATTERY("battery", SWITCH, "battery", "LowBattery"),

    // Detects if a battery is charging. 0: Not charging, 1: Charging.
    BATTERY_CHARGING("battery_charging", SWITCH, "energy", null),

    // Detects carbon monoxide presence. 0: No carbon monoxide, 1: Carbon monoxide detected.
    CO("co", SWITCH, "smoke", "Alarm"),

    // Indicates a cold temperature. 0: Not cold, 1: Cold.
    COLD("cold", SWITCH, "temperature_cold", null),

    // Indicates connectivity status. 0: Disconnected, 1: Connected.
    CONNECTIVITY("connectivity", SWITCH, "network", "Status"),

    // Indicates whether a door is open or closed. 0: Door closed, 1: Door open.
    DOOR("door", CONTACT, "door", "OpenState"),

    // For garage door status. 0: Garage door closed, 1: Garage door open.
    GARAGE_DOOR("garage_door", CONTACT, "garagedoor", "OpenState"),

    // For detecting gas presence. 0: No gas, 1: Gas detected.
    GAS("gas", SWITCH, "gas", "Alarm"),

    // Indicates high temperature. 0: Not hot, 1: Hot.
    HEAT("heat", SWITCH, "temperature_hot", null),

    // For detecting light. 0: No light, 1: Light detected.
    LIGHT("light", SWITCH, "light", null),

    // Indicates lock status. 0: Unlocked, 1: Locked.
    LOCK("lock", SWITCH, "lock", null),

    // For detecting moisture. 0: Dry, 1: Moisture detected.
    MOISTURE("moisture", SWITCH, "Water", "Alarm"),

    // For motion detection. 0: No motion, 1: Motion detected.
    MOTION("motion", SWITCH, "motion", "Presence"),

    // For detecting movement. 0: Stationary, 1: Moving.
    MOVING("moving", SWITCH, "motion", null),

    // For room occupancy. 0: Not occupied, 1: Occupied.
    OCCUPANCY("occupancy", SWITCH, "motion", "Presence"),

    // Generic for openings. 0: Closed, 1: Open.
    OPENING("opening", CONTACT, "door", "OpenState"),

    // Indicates plug use. 0: Unplugged, 1: Plugged in.
    PLUG("plug", SWITCH, "poweroutlet", null),

    // For power detection. 0: No power, 1: Power detected.
    POWER("power", SWITCH, "energy", null),

    // Indicates presence at home. 0: Away, 1: Home.
    PRESENCE("presence", SWITCH, "motion", "Presence"),

    // Indicates problem detection. 0: No problem, 1: Problem detected.
    PROBLEM("problem", SWITCH, "error", "Alarm"),

    // For running status. 0: Not running, 1: Running.
    RUNNING("running", SWITCH, "motion", null),

    // Indicates safety status. 0: Safe, 1: Unsafe.
    SAFETY("safety", SWITCH, "error", "Alarm"),

    // For smoke detection. 0: No smoke, 1: Smoke detected.
    SMOKE("smoke", SWITCH, "smoke", "Smoke"),

    // For sound detection. 0: No sound, 1: Sound detected.
    SOUND("sound", SWITCH, "soundvolume", "Sound"),

    // Indicates tampering. 0: No tampering, 1: Tampering detected.
    TAMPER("tamper", SWITCH, "alarm", "Tampered"),

    // For update status. 0: Up-to-date, 1: Update available. Note: Avoid using this device class.
    UPDATE("update", SWITCH, "Update", null),

    // For vibration detection. 0: No vibration, 1: Vibration detected.
    VIBRATION("vibration", SWITCH, "flow", "Tilt"),

    // Indicates window status. 0: Window closed, 1: Window open.
    WINDOW("window", CONTACT, "window", "OpenState");

    private final String deviceClass;
    private final String itemType;
    private final String category;
    private final String semanticType;

    public static BinarySensorDeviceClass fromDeviceClass(String deviceClass) {
        for (BinarySensorDeviceClass sensorDeviceClass : BinarySensorDeviceClass.values()) {
            if (sensorDeviceClass.getDeviceClass().equals(deviceClass)) {
                return sensorDeviceClass;
            }
        }
        return null;
    }

    public String getDeviceClass() {
        return deviceClass;
    }

    BinarySensorDeviceClass(String deviceClass, String itemType, String category, String semanticType) {
        this.deviceClass = deviceClass;
        this.itemType = itemType;
        this.category = category;
        this.semanticType = semanticType;
    }

    public String getSemanticType() {
        return semanticType;
    }

    public String getItemType() {
        return itemType;
    }

    public String getCategory() {
        return category;
    }

    @Override
    public String toString() {
        return "BinarySensorDeviceClass{" + "deviceClass='" + deviceClass + '\'' + ", itemType='" + itemType + '\''
                + ", category='" + category + '\'' + ", measurementType='" + semanticType + '\'' + '}';
    }
}
