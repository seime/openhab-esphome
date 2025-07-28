package no.seime.openhab.binding.esphome.internal.message.deviceclass;

import static org.openhab.core.library.CoreItemFactory.ROLLERSHUTTER;

public enum CoverDeviceClass implements DeviceClass {

    NONE("None", ROLLERSHUTTER, "rollershutter", "OpenLevel", true),
    AWNING("awning", ROLLERSHUTTER, "terrace", "OpenLevel"),
    BLIND("blind", ROLLERSHUTTER, "blinds", "OpenLevel"),
    CURTAIN("curtain", ROLLERSHUTTER, "rollershutter", "OpenLevel"),
    DAMPER("damper", ROLLERSHUTTER, "rollershutter", "OpenLevel"),
    DOOR("door", ROLLERSHUTTER, "door", "OpenLevel"),
    GARAGE("garage", ROLLERSHUTTER, "garagedoor", "OpenLevel"),
    GATE("gate", ROLLERSHUTTER, "door", "OpenLevel"),
    SHADE("shade", ROLLERSHUTTER, "rollershutter", "OpenLevel"),
    SHUTTER("shutter", ROLLERSHUTTER, "rollershutter", "OpenLevel"),
    WINDOW("window", ROLLERSHUTTER, "window", "OpenLevel");

    private final String deviceClass;

    private final String itemType;
    private final String category;
    private final String semanticType;
    private final boolean defaultDeviceClass;

    public static CoverDeviceClass fromDeviceClass(String deviceClass) {
        if ("".equals(deviceClass)) {
            return NONE; // Default to NONE if deviceClass is empty
        }
        for (CoverDeviceClass sensorDeviceClass : CoverDeviceClass.values()) {
            if (sensorDeviceClass.getDeviceClass().equals(deviceClass)) {
                return sensorDeviceClass;
            }
        }
        return null;
    }

    CoverDeviceClass(String deviceClass, String itemType, String category, String semanticType) {
        this.deviceClass = deviceClass;
        this.itemType = itemType;
        this.category = category;
        this.semanticType = semanticType;
        this.defaultDeviceClass = false;
    }

    CoverDeviceClass(String deviceClass, String itemType, String category, String semanticType,
            boolean defaultDeviceClass) {
        this.deviceClass = deviceClass;
        this.itemType = itemType;
        this.category = category;
        this.semanticType = semanticType;
        this.defaultDeviceClass = defaultDeviceClass;
    }

    public String getDeviceClass() {
        return deviceClass;
    }

    public String getSemanticType() {
        return semanticType;
    }

    @Override
    public boolean isDefault() {
        return defaultDeviceClass;
    }

    public String getItemType() {
        return itemType;
    }

    public String getCategory() {
        return category;
    }

    @Override
    public String toString() {
        return "SensorNumberDeviceClass{" + "deviceClass='" + deviceClass + '\'' + ", itemType='" + itemType + '\''
                + ", category='" + category + '\'' + ", measurementType='" + semanticType + '\'' + '}';
    }
}
