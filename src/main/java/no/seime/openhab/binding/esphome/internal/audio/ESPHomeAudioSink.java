package no.seime.openhab.binding.esphome.internal.audio;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.audio.AudioFormat;
import org.openhab.core.audio.AudioHTTPServer;
import org.openhab.core.audio.AudioSinkAsync;
import org.openhab.core.audio.AudioStream;
import org.openhab.core.audio.StreamServed;
import org.openhab.core.audio.URLAudioStream;
import org.openhab.core.audio.UnsupportedAudioFormatException;
import org.openhab.core.audio.UnsupportedAudioStreamException;
import org.openhab.core.library.types.PercentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.ListEntitiesMediaPlayerResponse;
import io.esphome.api.MediaPlayerCommandRequest;
import io.esphome.api.MediaPlayerStateResponse;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

@NonNullByDefault
public class ESPHomeAudioSink extends AudioSinkAsync {

    private static final int STREAM_URL_TTL_SECONDS = 30;
    private static final Set<Class<? extends AudioStream>> SUPPORTED_STREAMS = Set.of(AudioStream.class,
            URLAudioStream.class);

    private final Logger logger = LoggerFactory.getLogger(ESPHomeAudioSink.class);

    private final ESPHomeHandler handler;
    private final AudioHTTPServer audioHTTPServer;
    private final String id;
    private final String label;
    private final String baseUrl;
    private final int mediaPlayerKey;
    private final String mediaPlayerObjectId;
    private final Set<AudioFormat> supportedFormats;

    private volatile @Nullable MediaPlayerStateResponse state;

    public ESPHomeAudioSink(ESPHomeHandler handler, AudioHTTPServer audioHTTPServer,
            ListEntitiesMediaPlayerResponse mediaPlayer, Set<AudioFormat> supportedFormats, String baseUrl) {
        this.handler = handler;
        this.audioHTTPServer = audioHTTPServer;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.mediaPlayerKey = mediaPlayer.getKey();
        this.mediaPlayerObjectId = mediaPlayer.getObjectId();
        this.supportedFormats = supportedFormats;
        this.id = handler.getThing().getUID() + ":sink:" + mediaPlayerObjectId;
        String thingLabel = handler.getThing().getLabel() != null ? handler.getThing().getLabel()
                : handler.getThing().getUID().getId();
        this.label = "ESPHome " + thingLabel + " (" + mediaPlayer.getName() + ")";
    }

    public void updateState(@Nullable MediaPlayerStateResponse state) {
        this.state = state;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public @Nullable String getLabel(@Nullable Locale locale) {
        return label;
    }

    @Override
    protected void processAsynchronously(@Nullable AudioStream audioStream)
            throws UnsupportedAudioFormatException, UnsupportedAudioStreamException {
        if (audioStream == null) {
            try {
                handler.sendMessage(MediaPlayerCommandRequest.newBuilder().setKey(mediaPlayerKey).setHasCommand(true)
                        .setCommand(io.esphome.api.MediaPlayerCommand.MEDIA_PLAYER_COMMAND_STOP).build());
            } catch (ProtocolAPIError e) {
                throw new IllegalStateException("Failed to stop ESPHome media player playback", e);
            }
            return;
        }

        if (!supports(audioStream.getFormat())) {
            throw new UnsupportedAudioFormatException("ESPHome media player does not support stream format",
                    audioStream.getFormat());
        }

        try {
            if (audioStream instanceof URLAudioStream urlAudioStream) {
                handler.sendMessage(MediaPlayerCommandRequest.newBuilder().setKey(mediaPlayerKey).setHasMediaUrl(true)
                        .setMediaUrl(urlAudioStream.getURL()).setHasAnnouncement(true).setAnnouncement(true).build());
                playbackFinished(audioStream);
            } else {
                StreamServed servedStream = audioHTTPServer.serve(audioStream, STREAM_URL_TTL_SECONDS, false);
                String absoluteUrl = URI.create(baseUrl + servedStream.url()).toString();

                MediaPlayerCommandRequest request = MediaPlayerCommandRequest.newBuilder().setKey(mediaPlayerKey)
                        .setHasMediaUrl(true).setMediaUrl(absoluteUrl).setHasAnnouncement(true).setAnnouncement(true)
                        .build();
                handler.sendMessage(request);
                servedStream.playEnd().whenComplete((ignored, error) -> {
                    if (error != null) {
                        runnableByAudioStream.remove(audioStream).completeExceptionally(error);
                    } else {
                        playbackFinished(audioStream);
                    }
                });
            }
        } catch (IOException | ProtocolAPIError e) {
            logger.debug("[{}] Unable to send audio stream to ESPHome media player {}", handler.getLogPrefix(),
                    mediaPlayerObjectId, e);
            throw new IllegalStateException("Unable to send audio stream to ESPHome media player", e);
        }
    }

    @Override
    public Set<AudioFormat> getSupportedFormats() {
        return supportedFormats;
    }

    @Override
    public Set<Class<? extends AudioStream>> getSupportedStreams() {
        return SUPPORTED_STREAMS;
    }

    @Override
    public PercentType getVolume() throws IOException {
        MediaPlayerStateResponse currentState = state;
        if (currentState == null) {
            throw new IOException("ESPHome media player has not reported its current state yet");
        }
        return new PercentType(Math.round(currentState.getVolume() * 100f));
    }

    @Override
    public void setVolume(PercentType volume) throws IOException {
        try {
            handler.sendMessage(MediaPlayerCommandRequest.newBuilder().setKey(mediaPlayerKey).setHasVolume(true)
                    .setVolume(volume.floatValue() / 100f).build());
        } catch (ProtocolAPIError e) {
            throw new IOException("Failed to set ESPHome media player volume", e);
        }
    }

    private boolean supports(AudioFormat actualFormat) {
        return supportedFormats.stream().anyMatch(format -> format.isCompatible(actualFormat));
    }

    private static String normalizeBaseUrl(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
