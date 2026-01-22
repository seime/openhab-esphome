package no.seime.openhab.binding.esphome.internal.message.deviceclass;

import static org.openhab.core.library.CoreItemFactory.ROLLERSHUTTER;

public enum ValveDeviceClass implements DeviceClass {

    NONE("None", ROLLERSHUTTER, "water", "OpenLevel", true),
    WATER("water", ROLLERSHUTTER, "water", "OpenLevel"),
    GAS("gas", ROLLERSHUTTER, "gas", "OpenLevel");

    private final String deviceClass;

    private final String itemType;
    private final String category;
    private final String semanticType;
    private final boolean defaultDeviceClass;

    public static ValveDeviceClass fromDeviceClass(String deviceClass) {
        if ("".equals(deviceClass)) {
            return NONE; // Default to NONE if deviceClass is empty
        }
        for (ValveDeviceClass sensorDeviceClass : ValveDeviceClass.values()) {
            if (sensorDeviceClass.getDeviceClass().equals(deviceClass)) {
                return sensorDeviceClass;
            }
        }
        return null;
    }

    ValveDeviceClass(String deviceClass, String itemType, String category, String semanticType) {
        this.deviceClass = deviceClass;
        this.itemType = itemType;
        this.category = category;
        this.semanticType = semanticType;
        defaultDeviceClass = false;
    }

    ValveDeviceClass(String deviceClass, String itemType, String category, String semanticType,
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
        return "ValveDeviceClass{" + "deviceClass='" + deviceClass + '\'' + ", itemType='" + itemType + '\''
                + ", category='" + category + '\'' + ", measurementType='" + semanticType + '\'' + '}';
    }
}
