package no.seime.openhab.binding.esphome.internal.message.deviceclass;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

public interface DeviceClass {

    @NonNull
    String getDeviceClass();

    @NonNull
    String getItemType();

    @Nullable
    String getCategory();

    @Nullable
    String getSemanticType();

    boolean isDefault();
}
