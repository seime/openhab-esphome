package no.seime.openhab.binding.esphome.internal.message;

import static org.openhab.core.library.CoreItemFactory.CONTACT;
import static org.openhab.core.library.CoreItemFactory.SWITCH;

/* Coded with the help of ChatGPT
 * TODO: Should the type class also be dynamic (OpenClosed / OnOff / UpDown (org.openhab.core.library.types.UpDownType))
 */
public enum BinarySensorDeviceClass {
    // Generic binary sensor with no specific context. 0: Off, 1: On.
    GENERIC("generic", SWITCH, "None", null),

    // Indicates low battery status. 0: Battery not low, 1: Battery low.
    BATTERY("battery", SWITCH, "Battery", "LowBattery"),

    // Detects if a battery is charging. 0: Not charging, 1: Charging.
    BATTERY_CHARGING("battery_charging", SWITCH, "Energy", null),

    // Detects carbon monoxide presence. 0: No carbon monoxide, 1: Carbon monoxide detected.
    CO("co", SWITCH, "Gas", "Alarm"),

    // Indicates a cold temperature. 0: Not cold, 1: Cold.
    COLD("cold", SWITCH, "Temperature", null),

    // Indicates connectivity status. 0: Disconnected, 1: Connected.
    CONNECTIVITY("connectivity", SWITCH, "Network", "Status"),

    // Indicates whether a door is open or closed. 0: Door closed, 1: Door open.
    DOOR("door", CONTACT, "Door", "OpenState"),

    // For garage door status. 0: Garage door closed, 1: Garage door open.
    GARAGE_DOOR("garage_door", CONTACT, "GarageDoor", "OpenState"),

    // For detecting gas presence. 0: No gas, 1: Gas detected.
    GAS("gas", SWITCH, "Gas", "Alarm"),

    // Indicates high temperature. 0: Not hot, 1: Hot.
    HEAT("heat", SWITCH, "Temperature", null),

    // For detecting light. 0: No light, 1: Light detected.
    LIGHT("light", SWITCH, "Light", null),

    // Indicates lock status. 0: Unlocked, 1: Locked.
    LOCK("lock", SWITCH, "Lock", null),

    // For detecting moisture. 0: Dry, 1: Moisture detected.
    MOISTURE("moisture", SWITCH, "Water", "Alarm"),

    // For motion detection. 0: No motion, 1: Motion detected.
    MOTION("motion", SWITCH, "Motion", "Presence"),

    // For detecting movement. 0: Stationary, 1: Moving.
    MOVING("moving", SWITCH, "Motion", null),

    // For room occupancy. 0: Not occupied, 1: Occupied.
    OCCUPANCY("occupancy", SWITCH, "Motion", "Presence"),

    // Generic for openings. 0: Closed, 1: Open.
    OPENING("opening", CONTACT, "Opening", "OpenState"),

    // Indicates plug use. 0: Unplugged, 1: Plugged in.
    PLUG("plug", SWITCH, "PowerOutlet", null),

    // For power detection. 0: No power, 1: Power detected.
    POWER("power", SWITCH, "Energy", null),

    // Indicates presence at home. 0: Away, 1: Home.
    PRESENCE("presence", SWITCH, "Presence", "Presence"),

    // Indicates problem detection. 0: No problem, 1: Problem detected.
    PROBLEM("problem", SWITCH, "Status", "Alarm"),

    // For running status. 0: Not running, 1: Running.
    RUNNING("running", SWITCH, "Motion", null),

    // Indicates safety status. 0: Safe, 1: Unsafe.
    SAFETY("safety", SWITCH, "Safety", "Alarm"),

    // For smoke detection. 0: No smoke, 1: Smoke detected.
    SMOKE("smoke", SWITCH, "Smoke", "Smoke"),

    // For sound detection. 0: No sound, 1: Sound detected.
    SOUND("sound", SWITCH, "Sound", "Sound"),

    // Indicates tampering. 0: No tampering, 1: Tampering detected.
    TAMPER("tamper", SWITCH, "Alarm", "Tampered"),

    // For update status. 0: Up-to-date, 1: Update available. Note: Avoid using this device class.
    UPDATE("update", SWITCH, "Update", null),

    // For vibration detection. 0: No vibration, 1: Vibration detected.
    VIBRATION("vibration", SWITCH, "Vibration", "Tilt"),

    // Indicates window status. 0: Window closed, 1: Window open.
    WINDOW("window", CONTACT, "Window", "OpenState");

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
