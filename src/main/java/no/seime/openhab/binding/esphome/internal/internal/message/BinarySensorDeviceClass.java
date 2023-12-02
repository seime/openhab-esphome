package no.seime.openhab.binding.esphome.internal.internal.message;

/* Coded with the help of ChatGPT
 * TODO: Should the type class also be dynamic (OpenClosed / OnOff / UpDown (org.openhab.core.library.types.UpDownType))
 */
public enum BinarySensorDeviceClass {
    // Generic binary sensor with no specific context. 0: Off, 1: On.
    GENERIC("generic", "Switch", "None", null),

    // Indicates low battery status. 0: Battery not low, 1: Battery low.
    BATTERY("battery", "Switch", "Battery", "Measurement"),

    // Detects if a battery is charging. 0: Not charging, 1: Charging.
    BATTERY_CHARGING("battery_charging", "Switch", "Energy", "Status"),

    // Detects carbon monoxide presence. 0: No carbon monoxide, 1: Carbon monoxide detected.
    CO("co", "Switch", "Gas", "Alarm"),

    // Indicates a cold temperature. 0: Not cold, 1: Cold.
    COLD("cold", "Switch", "Temperature", "Measurement"),

    // Indicates connectivity status. 0: Disconnected, 1: Connected.
    CONNECTIVITY("connectivity", "Switch", "Network", "Status"),

    // Indicates whether a door is open or closed. 0: Door closed, 1: Door open.
    DOOR("door", "Contact", "Door", "Point"),

    // For garage door status. 0: Garage door closed, 1: Garage door open.
    GARAGE_DOOR("garage_door", "Contact", "GarageDoor", "Point"),

    // For detecting gas presence. 0: No gas, 1: Gas detected.
    GAS("gas", "Switch", "Gas", "Alarm"),

    // Indicates high temperature. 0: Not hot, 1: Hot.
    HEAT("heat", "Switch", "Temperature", "Measurement"),

    // For detecting light. 0: No light, 1: Light detected.
    LIGHT("light", "Switch", "Light", "Control"),

    // Indicates lock status. 0: Unlocked, 1: Locked.
    LOCK("lock", "Switch", "Lock", "Control"),

    // For detecting moisture. 0: Dry, 1: Moisture detected.
    MOISTURE("moisture", "Switch", "Water", "Alarm"),

    // For motion detection. 0: No motion, 1: Motion detected.
    MOTION("motion", "Switch", "Motion", "Detection"),

    // For detecting movement. 0: Stationary, 1: Moving.
    MOVING("moving", "Switch", "Motion", "Detection"),

    // For room occupancy. 0: Not occupied, 1: Occupied.
    OCCUPANCY("occupancy", "Switch", "Motion", "Presence"),

    // Generic for openings. 0: Closed, 1: Open.
    OPENING("opening", "Contact", "Opening", "Point"),

    // Indicates plug use. 0: Unplugged, 1: Plugged in.
    PLUG("plug", "Switch", "PowerOutlet", "Control"),

    // For power detection. 0: No power, 1: Power detected.
    POWER("power", "Switch", "Energy", "Status"),

    // Indicates presence at home. 0: Away, 1: Home.
    PRESENCE("presence", "Switch", "Presence", "Presence"),

    // Indicates problem detection. 0: No problem, 1: Problem detected.
    PROBLEM("problem", "Switch", "Status", "Alarm"),

    // For running status. 0: Not running, 1: Running.
    RUNNING("running", "Switch", "Motion", "Status"),

    // Indicates safety status. 0: Safe, 1: Unsafe.
    SAFETY("safety", "Switch", "Safety", "Alarm"),

    // For smoke detection. 0: No smoke, 1: Smoke detected.
    SMOKE("smoke", "Switch", "Smoke", "Alarm"),

    // For sound detection. 0: No sound, 1: Sound detected.
    SOUND("sound", "Switch", "Sound", "Detection"),

    // Indicates tampering. 0: No tampering, 1: Tampering detected.
    TAMPER("tamper", "Switch", "Alarm", "Alarm"),

    // For update status. 0: Up-to-date, 1: Update available. Note: Avoid using this device class.
    UPDATE("update", "Switch", "Update", null),

    // For vibration detection. 0: No vibration, 1: Vibration detected.
    VIBRATION("vibration", "Switch", "Vibration", "Alarm"),

    // Indicates window status. 0: Window closed, 1: Window open.
    WINDOW("window", "Contact", "Window", "Point");

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
