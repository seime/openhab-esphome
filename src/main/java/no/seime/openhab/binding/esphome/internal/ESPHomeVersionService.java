package no.seime.openhab.binding.esphome.internal;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.CoreItemFactory;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.AutoUpdatePolicy;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.thing.type.ChannelTypeBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service that fetches the latest ESPHome version from the official release page on GitHub.
 *
 * @author Arne Seime - Initial contribution
 */
@NonNullByDefault
public class ESPHomeVersionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ESPHomeVersionService.class);

    private static final String CHANGELOG_URL = "https://github.com/esphome/esphome/releases/latest";

    private @Nullable String latestVersion;

    private final ScheduledExecutorService scheduler;

    private @Nullable ScheduledFuture<?> scheduledFuture;

    private final List<ESPHomeVersionListener> listeners = new CopyOnWriteArrayList<>();

    public ESPHomeVersionService(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    public void start() {
        scheduledFuture = scheduler.scheduleWithFixedDelay(this::fetchVersion, 0, 24, TimeUnit.HOURS);
    }

    public void stop() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
    }

    public void addListener(ESPHomeVersionListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ESPHomeVersionListener listener) {
        listeners.remove(listener);
    }

    void fetchVersion() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(CHANGELOG_URL).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(false);
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_MOVED_PERM
                    || responseCode == 307 || responseCode == 308) {
                String location = conn.getHeaderField("Location");
                if (location != null) {
                    String version = location.substring(location.lastIndexOf("/") + 1);
                    if (version.startsWith("v")) {
                        version = version.substring(1);
                    }
                    String versionToNotify = version;
                    latestVersion = versionToNotify;
                    LOGGER.info("Latest ESPHome version: {}", latestVersion);
                    listeners.forEach(listener -> listener.onVersionUpdate(versionToNotify));
                } else {
                    LOGGER.warn("Redirected but no Location header found from {}", CHANGELOG_URL);
                }
            } else if (responseCode == HttpURLConnection.HTTP_OK) {
                // In case it doesn't redirect but stays at the same URL (unlikely for /latest)
                String url = conn.getURL().toString();
                String version = url.substring(url.lastIndexOf("/") + 1);
                String versionToNotify = version;
                latestVersion = versionToNotify;
                LOGGER.info("Latest ESPHome version: {}", latestVersion);
                listeners.forEach(listener -> listener.onVersionUpdate(versionToNotify));
            } else {
                LOGGER.warn("Error fetching ESPHome version: HTTP {}", responseCode);
            }
            conn.disconnect();
        } catch (IOException e) {
            LOGGER.warn("Error fetching latest ESPHome version: {}", e.getMessage());
        }
    }

    public @Nullable String getLatestVersion() {
        return latestVersion;
    }

    public static boolean isVersionNewer(String latestVersion, String currentVersion, String logPrefix) {
        try {
            String[] latestParts = latestVersion.split("\\.");
            String[] currentParts = currentVersion.split("\\.");
            int length = Math.max(latestParts.length, currentParts.length);
            for (int i = 0; i < length; i++) {
                int latestPart = i < latestParts.length ? Integer.parseInt(latestParts[i].replaceAll("[^0-9]", "")) : 0;
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i].replaceAll("[^0-9]", ""))
                        : 0;
                if (latestPart > currentPart) {
                    return true;
                } else if (latestPart < currentPart) {
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            LOGGER.debug("[{}] Could not parse version strings for comparison: {} and {}", logPrefix, latestVersion,
                    currentVersion);
        }
        return false;
    }

    public ChannelType createLatestFirmwareVersionChannelType(ThingUID thingUID) {
        return ChannelTypeBuilder
                .state(new ChannelTypeUID(BindingConstants.BINDING_ID,
                        thingUID.getId() + "_" + BindingConstants.CHANNEL_LATEST_FIRMWARE_VERSION),
                        "Latest Firmware Version", CoreItemFactory.STRING)
                .isAdvanced(true).withAutoUpdatePolicy(AutoUpdatePolicy.VETO).build();
    }

    public ChannelType createFirmwareUpdateAvailableChannelType(ThingUID thingUID) {
        return ChannelTypeBuilder
                .state(new ChannelTypeUID(BindingConstants.BINDING_ID,
                        thingUID.getId() + "_" + BindingConstants.CHANNEL_FIRMWARE_UPDATE_AVAILABLE),
                        "Firmware Update Available", CoreItemFactory.CONTACT)
                .isAdvanced(true).withAutoUpdatePolicy(AutoUpdatePolicy.VETO).build();
    }

    public Channel createLatestFirmwareVersionChannel(ThingUID thingUID, ChannelTypeUID channelTypeUID) {
        return ChannelBuilder
                .create(new ChannelUID(thingUID, BindingConstants.CHANNEL_LATEST_FIRMWARE_VERSION),
                        CoreItemFactory.STRING)
                .withLabel("Latest Firmware Version")
                .withDescription(
                        "Latest version of ESPHome firmware fetched from https://github.com/esphome/esphome/releases/latest")
                .withType(channelTypeUID).build();
    }

    public Channel createFirmwareUpdateAvailableChannel(ThingUID thingUID, ChannelTypeUID channelTypeUID) {
        return ChannelBuilder
                .create(new ChannelUID(thingUID, BindingConstants.CHANNEL_FIRMWARE_UPDATE_AVAILABLE),
                        CoreItemFactory.CONTACT)
                .withLabel("Firmware Update Available")
                .withDescription(
                        "OPEN if there is a newer version of ESPHome firmware available. This does only check your device version against the latest version published on GitHub, not against the version installed on your computer. Note that even if there is a new version on GitHub, it might not have been released for your favourite package manager.")
                .withType(channelTypeUID).build();
    }
}
