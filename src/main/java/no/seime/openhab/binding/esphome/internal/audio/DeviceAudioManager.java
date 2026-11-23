/**
 * Copyright (c) 2023 Contributors to the Seime Openhab Addons project
 * <p>
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 * <p>
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * <p>
 * SPDX-License-Identifier: EPL-2.0
 */
package no.seime.openhab.binding.esphome.internal.audio;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.audio.AudioFormat;
import org.openhab.core.audio.AudioHTTPServer;
import org.openhab.core.audio.AudioSink;
import org.openhab.core.audio.AudioSource;
import org.openhab.core.net.HttpServiceUtil;
import org.openhab.core.net.NetworkAddressService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.ListEntitiesMediaPlayerResponse;
import io.esphome.api.MediaPlayerFormatPurpose;
import io.esphome.api.MediaPlayerStateResponse;
import io.esphome.api.VoiceAssistantAudio;
import io.esphome.api.VoiceAssistantRequest;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;
import no.seime.openhab.binding.esphome.internal.handler.ExponentialBackoff;

/**
 * Owns the openHAB audio services of one ESPHome device: an {@link AudioSink} per media player, the voice assistant
 * {@link AudioSource} and the openHAB voice dialog that uses them.
 *
 * @author Arne Seime - Initial contribution
 */
@NonNullByDefault
public class DeviceAudioManager {

    private static final int VOICE_DIALOG_RETRY_MIN_MS = 5000;
    private static final int VOICE_DIALOG_RETRY_MAX_MS = 60000;

    private final Logger logger = LoggerFactory.getLogger(DeviceAudioManager.class);

    private final ESPHomeHandler handler;
    private final BundleContext bundleContext;
    private final AudioHTTPServer audioHTTPServer;
    private final NetworkAddressService networkAddressService;
    private final @Nullable String bindingPropertyAudioBaseUrl;
    private final BooleanSupplier voiceDialogEnabled;
    private final Consumer<Runnable> runIfConnected;

    private final Map<Integer, ListEntitiesMediaPlayerResponse> mediaPlayers = new HashMap<>();
    private final Map<Integer, MediaPlayerStateResponse> mediaPlayerStates = new HashMap<>();
    private final Map<Integer, ESPHomeAudioSink> audioSinks = new HashMap<>();
    private final Map<Integer, ServiceRegistration<AudioSink>> audioSinkRegistrations = new HashMap<>();
    private @Nullable ESPHomeVoiceAssistantAudioSource audioSource;
    private @Nullable ServiceRegistration<AudioSource> audioSourceRegistration;
    private final Object voiceDialogLock = new Object();
    private @Nullable ScheduledFuture<?> voiceDialogRestartFuture;
    private final ExponentialBackoff voiceDialogBackoff = new ExponentialBackoff(VOICE_DIALOG_RETRY_MIN_MS,
            VOICE_DIALOG_RETRY_MAX_MS);
    private boolean voiceDialogRetryWarned;
    private int voiceAssistantFeatureFlags;

    /**
     * @param voiceDialogEnabled whether the Thing is configured to run an openHAB voice dialog
     * @param runIfConnected runs the task under the handler's connection lock, but only while the device is connected
     *            and interrogated
     */
    public DeviceAudioManager(ESPHomeHandler handler, BundleContext bundleContext, AudioHTTPServer audioHTTPServer,
            NetworkAddressService networkAddressService, @Nullable String bindingPropertyAudioBaseUrl,
            BooleanSupplier voiceDialogEnabled, Consumer<Runnable> runIfConnected) {
        this.handler = handler;
        this.bundleContext = bundleContext;
        this.audioHTTPServer = audioHTTPServer;
        this.networkAddressService = networkAddressService;
        this.bindingPropertyAudioBaseUrl = bindingPropertyAudioBaseUrl;
        this.voiceDialogEnabled = voiceDialogEnabled;
        this.runIfConnected = runIfConnected;
    }

    public void onMediaPlayerEntity(ListEntitiesMediaPlayerResponse message) {
        mediaPlayers.put(message.getKey(), message);
    }

    public void onMediaPlayerState(MediaPlayerStateResponse message) {
        if (!mediaPlayers.containsKey(message.getKey())) {
            return;
        }

        mediaPlayerStates.put(message.getKey(), message);
        ESPHomeAudioSink sink = audioSinks.get(message.getKey());
        if (sink != null) {
            sink.updateState(message);
        }
    }

    public void setVoiceAssistantFeatureFlags(int flags) {
        voiceAssistantFeatureFlags = flags;
    }

    public void onVoiceAssistantRequest(VoiceAssistantRequest request) {
        ESPHomeVoiceAssistantAudioSource source = audioSource;
        if (source != null) {
            source.handleVoiceAssistantRequest(request);
        }
    }

    public void onVoiceAssistantAudio(VoiceAssistantAudio audio) {
        ESPHomeVoiceAssistantAudioSource source = audioSource;
        if (source != null) {
            source.handleVoiceAssistantAudio(audio);
        }
    }

    /**
     * Registers, updates or removes audio services to match the device's entities.
     *
     * @param active whether the device is connected and interrogated; if not, all services are removed
     */
    public void refresh(boolean active) {
        if (!active) {
            unregisterAudioSinks();
            unregisterAudioSource();
            return;
        }

        refreshAudioSink();
        refreshAudioSource();
        refreshVoiceDialog();
    }

    /**
     * Forgets all media players before the device is interrogated again.
     */
    public void clearEntities() {
        unregisterAudioSinks();
        unregisterAudioSource();
        mediaPlayers.clear();
        mediaPlayerStates.clear();
    }

    /**
     * Removes the audio services when the connection is lost. Media players are kept, as the device's entities are
     * reused if its firmware is unchanged when it reconnects.
     */
    public void disconnect() {
        unregisterAudioSinks();
        unregisterAudioSource();
        // unregistering above schedules a restart
        cancelVoiceDialogRestart();
        voiceAssistantFeatureFlags = 0;
    }

    public void dispose() {
        cancelVoiceDialogRestart();
        unregisterAudioSinks();
        mediaPlayers.clear();
        mediaPlayerStates.clear();
        unregisterAudioSource();
    }

    private void refreshAudioSink() {
        String baseUrl = resolveAudioSinkBaseUrl();
        if (baseUrl == null) {
            logger.debug(
                    "[{}] Cannot register ESPHome audio sinks because no base URL is configured and the primary openHAB IPv4 address is unknown",
                    handler.getLogPrefix());
            unregisterAudioSinks();
            return;
        }

        Set<Integer> staleKeys = new HashSet<>(audioSinkRegistrations.keySet());
        for (ListEntitiesMediaPlayerResponse mediaPlayer : mediaPlayers.values()) {
            Set<AudioFormat> supportedFormats = AudioUtil
                    .mapSupportedAudioFormats(mediaPlayer.getSupportedFormatsList());
            if (supportedFormats.isEmpty()) {
                continue;
            }
            staleKeys.remove(mediaPlayer.getKey());
            ESPHomeAudioSink sink = audioSinks.get(mediaPlayer.getKey());
            if (sink == null) {
                ESPHomeAudioSink newAudioSink = new ESPHomeAudioSink(handler, audioHTTPServer, mediaPlayer, baseUrl);
                newAudioSink.updateState(mediaPlayerStates.get(mediaPlayer.getKey()));
                audioSinkRegistrations.put(mediaPlayer.getKey(),
                        bundleContext.registerService(AudioSink.class, newAudioSink, new Hashtable<>()));
                audioSinks.put(mediaPlayer.getKey(), newAudioSink);
            } else {
                sink.setBaseUrl(baseUrl);
                sink.updateState(mediaPlayerStates.get(mediaPlayer.getKey()));
            }
        }

        for (Integer staleKey : staleKeys) {
            ServiceRegistration<AudioSink> registration = audioSinkRegistrations.remove(staleKey);
            if (registration != null) {
                registration.unregister();
            }
            audioSinks.remove(staleKey);
        }
    }

    private void refreshAudioSource() {
        if (!supportsVoiceAssistantApiAudio()) {
            unregisterAudioSource();
            return;
        }

        if (audioSource == null) {
            ESPHomeVoiceAssistantAudioSource newAudioSource = new ESPHomeVoiceAssistantAudioSource(handler);
            audioSourceRegistration = bundleContext.registerService(AudioSource.class, newAudioSource,
                    new Hashtable<>());
            audioSource = newAudioSource;
        }
    }

    private void refreshVoiceDialog() {
        ESPHomeVoiceAssistantAudioSource source = audioSource;
        if (source == null || !voiceDialogEnabled.getAsBoolean()) {
            return;
        }
        if (!source.hasDialogTrigger() && !startVoiceDialog(source)) {
            return;
        }
        if (!source.subscribeForDialogTrigger()) {
            retryVoiceDialog("could not subscribe to the device");
            return;
        }
        synchronized (voiceDialogLock) {
            voiceDialogBackoff.reset();
            voiceDialogRetryWarned = false;
        }
    }

    /**
     * @return true if the dialog started
     */
    private boolean startVoiceDialog(ESPHomeVoiceAssistantAudioSource source) {
        ESPHomeAudioSink sink = voiceDialogSink();
        if (sink == null) {
            logger.debug(mediaPlayers.isEmpty() ? "[{}] No media player, not starting voice dialog"
                    : "[{}] Media player has no audio sink, not starting voice dialog", handler.getLogPrefix());
            return false;
        }
        AtomicBoolean stopped = new AtomicBoolean();
        Runnable trigger;
        try {
            trigger = AudioDialogProviderShim.startDialog(bundleContext, sink, source,
                    () -> onVoiceDialogStopped(source, stopped), source::onDialogIdle);
        } catch (UnsupportedOperationException e) {
            logger.debug("[{}] Voice dialog not supported: {}", handler.getLogPrefix(), e.getMessage());
            return false;
        } catch (IllegalStateException e) {
            retryVoiceDialog(String.valueOf(e.getMessage()));
            return false;
        }
        synchronized (voiceDialogLock) {
            // openHAB already stopped it
            if (stopped.get()) {
                return false;
            }
            source.setDialogTrigger(trigger);
        }
        audioSinks.values().forEach(s -> s.setVoiceAssistant(s == sink ? source : null));
        logger.debug("[{}] Voice dialog started for {} and {}", handler.getLogPrefix(), source.getId(), sink.getId());
        return true;
    }

    /**
     * Prefers a media player that supports announcements.
     */
    private @Nullable ESPHomeAudioSink voiceDialogSink() {
        return mediaPlayers.values().stream()
                .sorted(Comparator.comparing((ListEntitiesMediaPlayerResponse p) -> !supportsAnnouncements(p))
                        .thenComparing(ListEntitiesMediaPlayerResponse::getObjectId))
                .map(p -> audioSinks.get(p.getKey())).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static boolean supportsAnnouncements(ListEntitiesMediaPlayerResponse mediaPlayer) {
        return mediaPlayer.getSupportedFormatsList().stream()
                .anyMatch(f -> f.getPurpose() == MediaPlayerFormatPurpose.MEDIA_PLAYER_FORMAT_PURPOSE_ANNOUNCEMENT);
    }

    /**
     * Called when openHAB stops the dialog.
     */
    private void onVoiceDialogStopped(ESPHomeVoiceAssistantAudioSource source, AtomicBoolean stopped) {
        synchronized (voiceDialogLock) {
            stopped.set(true);
            source.setDialogTrigger(null);
        }
        logger.debug("[{}] Voice dialog stopped by openHAB, restarting", handler.getLogPrefix());
        scheduleVoiceDialogRestart();
    }

    private void retryVoiceDialog(String reason) {
        synchronized (voiceDialogLock) {
            if (!voiceDialogRetryWarned) {
                voiceDialogRetryWarned = true;
                logger.warn("[{}] Cannot start voice dialog, retrying: {}", handler.getLogPrefix(), reason);
            } else {
                logger.debug("[{}] Cannot start voice dialog, retrying: {}", handler.getLogPrefix(), reason);
            }
        }
        scheduleVoiceDialogRestart();
    }

    private void scheduleVoiceDialogRestart() {
        if (handler.isDisposed()) {
            return;
        }
        synchronized (voiceDialogLock) {
            ScheduledFuture<?> pending = voiceDialogRestartFuture;
            if (pending != null && !pending.isDone()) {
                return;
            }
            voiceDialogRestartFuture = handler.schedule(this::restartVoiceDialog, voiceDialogBackoff.getNextDelay(),
                    TimeUnit.MILLISECONDS);
        }
    }

    private void restartVoiceDialog() {
        synchronized (voiceDialogLock) {
            voiceDialogRestartFuture = null;
        }
        // the connection lock blocks disconnects until the dialog has started
        runIfConnected.accept(this::refreshVoiceDialog);
    }

    private void cancelVoiceDialogRestart() {
        synchronized (voiceDialogLock) {
            ScheduledFuture<?> pending = voiceDialogRestartFuture;
            if (pending != null) {
                pending.cancel(false);
                voiceDialogRestartFuture = null;
            }
            voiceDialogBackoff.reset();
            voiceDialogRetryWarned = false;
        }
    }

    private @Nullable String resolveAudioSinkBaseUrl() {
        String configuredBaseUrl = bindingPropertyAudioBaseUrl;
        if (StringUtils.isNotBlank(configuredBaseUrl)) {
            return configuredBaseUrl.trim();
        }
        @Nullable
        String primaryAddress = networkAddressService.getPrimaryIpv4HostAddress();
        return primaryAddress != null
                ? "http://" + primaryAddress + ":" + HttpServiceUtil.getHttpServicePort(bundleContext)
                : null;
    }

    private void unregisterAudioSinks() {
        for (ServiceRegistration<AudioSink> registration : audioSinkRegistrations.values()) {
            if (registration != null) {
                registration.unregister();
            }
        }
        audioSinkRegistrations.clear();
        for (ESPHomeAudioSink sink : audioSinks.values()) {
            sink.dispose();
        }
        audioSinks.clear();
    }

    private void unregisterAudioSource() {
        ESPHomeVoiceAssistantAudioSource source = audioSource;
        audioSource = null;
        if (source != null) {
            source.close();
        }
        ServiceRegistration<AudioSource> sourceRegistration = audioSourceRegistration;
        audioSourceRegistration = null;
        if (sourceRegistration != null) {
            sourceRegistration.unregister();
        }
    }

    private boolean supportsVoiceAssistantApiAudio() {
        return (voiceAssistantFeatureFlags & VoiceAssistantFeature.API_AUDIO.getFlag()) != 0;
    }
}
