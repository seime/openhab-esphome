package no.seime.openhab.binding.esphome.internal.message;

import static org.openhab.core.library.CoreItemFactory.ROLLERSHUTTER;

public enum CoverDeviceClass {

    NONE("None", ROLLERSHUTTER, "rollershutter", "OpenLevel"),
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

    public static CoverDeviceClass fromDeviceClass(String deviceClass) {
        for (CoverDeviceClass sensorDeviceClass : CoverDeviceClass.values()) {
            if (sensorDeviceClass.getDeviceClass().equals(deviceClass)) {
                return sensorDeviceClass;
            }
        }
        return null;
    }

    public String getDeviceClass() {
        return deviceClass;
    }

    CoverDeviceClass(String deviceClass, String itemType, String category, String semanticType) {
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
        return "SensorNumberDeviceClass{" + "deviceClass='" + deviceClass + '\'' + ", itemType='" + itemType + '\''
                + ", category='" + category + '\'' + ", measurementType='" + semanticType + '\'' + '}';
    }
}
