package no.seime.openhab.binding.esphome.internal.audio;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.audio.AudioException;
import org.openhab.core.audio.AudioFormat;
import org.openhab.core.audio.AudioSource;
import org.openhab.core.audio.AudioStream;
import org.openhab.core.audio.PipedAudioStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.SubscribeVoiceAssistantRequest;
import io.esphome.api.VoiceAssistantAudio;
import io.esphome.api.VoiceAssistantRequest;
import io.esphome.api.VoiceAssistantResponse;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

@NonNullByDefault
public class ESPHomeVoiceAssistantAudioSource implements AudioSource {

    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(AudioFormat.CONTAINER_NONE,
            AudioFormat.CODEC_PCM_SIGNED, false, 16, null, 16000L, 1);

    private final Logger logger = LoggerFactory.getLogger(ESPHomeVoiceAssistantAudioSource.class);

    private final ESPHomeHandler handler;
    private final String id;
    private final String label;

    private final Object lock = new Object();

    private PipedAudioStream.Group streamGroup = PipedAudioStream.newGroup(AUDIO_FORMAT, 16 * 1024);
    private boolean subscribed;
    private boolean streaming;

    public ESPHomeVoiceAssistantAudioSource(ESPHomeHandler handler) {
        this.handler = handler;
        this.id = handler.getThing().getUID() + ":source:voice_assistant";
        String thingLabel = handler.getThing().getLabel() != null ? handler.getThing().getLabel()
                : handler.getThing().getUID().getId();
        this.label = "ESPHome " + thingLabel + " microphone";
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getLabel(@Nullable Locale locale) {
        return label;
    }

    @Override
    public Set<AudioFormat> getSupportedFormats() {
        return Set.of(AUDIO_FORMAT);
    }

    @Override
    public AudioStream getInputStream(AudioFormat format) throws AudioException {
        if (!(AUDIO_FORMAT.isCompatible(format) || format.isCompatible(AUDIO_FORMAT))) {
            throw new AudioException("Unsupported ESPHome voice assistant input format requested");
        }

        try {
            synchronized (lock) {
                PipedAudioStream stream = streamGroup.getAudioStreamInGroup();
                stream.onClose(this::onStreamClosed);
                ensureSubscribed();
                return stream;
            }
        } catch (IOException | ProtocolAPIError e) {
            throw new AudioException("Failed to open ESPHome voice assistant audio stream", e);
        }
    }

    public void handleVoiceAssistantRequest(VoiceAssistantRequest request) {
        synchronized (lock) {
            if (!request.getStart()) {
                endSession();
                return;
            }

            if (streamGroup.isEmpty()) {
                try {
                    handler.sendMessage(VoiceAssistantResponse.newBuilder().setError(true).build());
                } catch (ProtocolAPIError e) {
                    logger.debug("[{}] Failed to reject unsolicited voice assistant request", handler.getLogPrefix(),
                            e);
                }
                return;
            }

            try {
                handler.sendMessage(VoiceAssistantResponse.newBuilder().setPort(0).setError(false).build());
                streaming = true;
            } catch (ProtocolAPIError e) {
                logger.debug("[{}] Failed to accept voice assistant request", handler.getLogPrefix(), e);
                endSession();
            }
        }
    }

    public void handleVoiceAssistantAudio(VoiceAssistantAudio audio) {
        synchronized (lock) {
            if (!streaming || streamGroup.isEmpty()) {
                return;
            }

            if (!audio.getData().isEmpty()) {
                try {
                    streamGroup.write(audio.getData().toByteArray());
                    streamGroup.flush();
                } catch (RuntimeException e) {
                    logger.debug("[{}] Error forwarding ESPHome voice assistant audio", handler.getLogPrefix(), e);
                    endSession();
                    return;
                }
            }

            if (audio.getEnd()) {
                endSession();
            }
        }
    }

    public void close() {
        synchronized (lock) {
            endSession();
            unsubscribe();
        }
    }

    private void ensureSubscribed() throws ProtocolAPIError {
        if (!subscribed) {
            handler.sendMessage(SubscribeVoiceAssistantRequest.newBuilder().setSubscribe(true)
                    .setFlags(1 /* VOICE_ASSISTANT_SUBSCRIBE_API_AUDIO */).build());
            subscribed = true;
        }
    }

    private void onStreamClosed() {
        synchronized (lock) {
            if (streamGroup.isEmpty()) {
                unsubscribe();
            }
        }
    }

    private void unsubscribe() {
        if (subscribed) {
            try {
                handler.sendMessage(SubscribeVoiceAssistantRequest.newBuilder().setSubscribe(false).build());
            } catch (ProtocolAPIError e) {
                logger.debug("[{}] Failed to unsubscribe from ESPHome voice assistant audio", handler.getLogPrefix(),
                        e);
            }
            subscribed = false;
        }
    }

    private void endSession() {
        streaming = false;
        streamGroup.close();
        streamGroup = PipedAudioStream.newGroup(AUDIO_FORMAT, 16 * 1024);
    }
}
