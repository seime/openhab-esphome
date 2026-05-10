package no.seime.openhab.binding.esphome.internal.handler.action;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.automation.annotation.ActionOutput;
import org.openhab.core.automation.annotation.ActionOutputs;
import org.openhab.core.automation.annotation.RuleAction;
import org.openhab.core.thing.binding.ThingActions;
import org.openhab.core.thing.binding.ThingActionsScope;
import org.openhab.core.thing.binding.ThingHandler;

import no.seime.openhab.binding.esphome.internal.ESPHomeConfiguration;
import no.seime.openhab.binding.esphome.internal.ESPHomeVersionService;
import no.seime.openhab.binding.esphome.internal.FirmwareUpgradeService;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

@ThingActionsScope(name = "esphome")
@NonNullByDefault
public class FirmwareUpgradeAction implements ThingActions {

    @Nullable
    private ESPHomeHandler handler;

    public FirmwareUpgradeAction(ESPHomeHandler espHomeHandler) {
        handler = espHomeHandler;
    }

    @RuleAction(label = "Upgrade device firmware", description = "Recompiles and flashes the latest firmware to the device. This includes upgrading the local installation of ESPHome first if necessary. See binding configuration for required parameters")
    public @ActionOutputs({
            @ActionOutput(name = "result", label = "Upgrade result", type = "java.lang.String") }) Map<String, String> upgradeFirmware() {

        ESPHomeHandler handler = this.handler;
        if (handler == null) {
            return Map.of("result",
                    "Failed: Action thing handler is null. This should not happen, check your installation and logs for errors.");
        }

        ESPHomeConfiguration deviceConfig = handler.getThing().getConfiguration().as(ESPHomeConfiguration.class);
        if (StringUtils.trimToNull(deviceConfig.configFileFullPath) == null) {
            return Map.of("result",
                    "Failed: Device configuration parameter configFileFullPath is empty. It should be a fully qualified path to the esphome yaml for your device.");
        }

        FirmwareUpgradeService firmwareUpgradeService = handler.getFirmwareUpgradeService();
        @Nullable
        String installedVersion = firmwareUpgradeService.getInstalledVersion();
        if (installedVersion == null) {
            return Map.of("result",
                    "Failed: Unable to detect installed esphome version (on your computer). Check that binding parameter bindingPropertyEspHomeExecutable is set to the fully qualified path or your esphome installation binary");
        }

        ESPHomeVersionService versionService = handler.getVersionService();
        @Nullable
        String latestVersion = versionService.getLatestVersion();
        if (latestVersion == null) {
            return Map.of("result", "Failed: Unable to detect latest esphome version (from github).");
        }

        if (ESPHomeVersionService.isVersionNewer(latestVersion, installedVersion, handler.getLogPrefix())) {
            // Upgrade locally installed version
            boolean upgradeResult = firmwareUpgradeService.upgradeLocalInstallation();
            if (!upgradeResult) {
                return Map.of("result",
                        String.format("Failed: Unable to upgrade local esphome installation from %s to %s.",
                                installedVersion, latestVersion));
            }
            installedVersion = firmwareUpgradeService.getInstalledVersion();
            if (!installedVersion.equals(latestVersion)) {
                return Map.of("result", String.format(
                        "Failed: Upgrading to latest esphome version (%s) locally apparently went fine, but esphome still reports version ",
                        latestVersion, installedVersion));

            }
        }

        boolean upgradeResult = firmwareUpgradeService.upgradeDevice(deviceConfig.configFileFullPath);
        if (upgradeResult) {
            return Map.of("result", String.format("Success: Firmware upgrade completed successfully for device %s",
                    deviceConfig.deviceId));
        } else {
            return Map.of("result",
                    String.format("Failed: Firmware upgrade failed for device %s", deviceConfig.deviceId));
        }
    }

    @Override
    public void setThingHandler(ThingHandler handler) {
        if (handler instanceof ESPHomeHandler espHomeHandler) {
            this.handler = espHomeHandler;
        }
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return handler;
    }
}
