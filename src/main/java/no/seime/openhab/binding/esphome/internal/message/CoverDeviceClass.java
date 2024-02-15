package no.seime.openhab.binding.esphome.internal.message;

public enum CoverDeviceClass {

    NONE("None", "Rollershutter", "rollershutter", "OpenLevel"),
    AWNING("awning", "Rollershutter", "terrace", "OpenLevel"),
    BLIND("blind", "Rollershutter", "blinds", "OpenLevel"),
    CURTAIN("curtain", "Rollershutter", "rollershutter", "OpenLevel"),
    DAMPER("damper", "Rollershutter", "rollershutter", "OpenLevel"),
    DOOR("door", "Rollershutter", "door", "OpenLevel"),
    GARAGE("garage", "Rollershutter", "garagedoor", "OpenLevel"),
    GATE("gate", "Rollershutter", "door", "OpenLevel"),
    SHADE("shade", "Rollershutter", "rollershutter", "OpenLevel"),
    SHUTTER("shutter", "Rollershutter", "rollershutter", "OpenLevel"),
    WINDOW("window", "Rollershutter", "window", "OpenLevel");

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
