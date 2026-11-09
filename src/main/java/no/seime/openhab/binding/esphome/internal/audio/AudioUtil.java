package no.seime.openhab.binding.esphome.internal.audio;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.audio.AudioFormat;

import io.esphome.api.MediaPlayerSupportedFormat;

/**
 * Utility class related to ESPHome audio support.
 *
 * @author Cody Cutrer - Initial contribution
 */
public final class AudioUtil {

    private AudioUtil() {
    }

    public static Set<AudioFormat> mapSupportedAudioFormats(List<MediaPlayerSupportedFormat> supportedFormats) {
        Set<AudioFormat> formats = new HashSet<>();
        for (MediaPlayerSupportedFormat format : supportedFormats) {
            AudioFormat mappedFormat = mapSupportedAudioFormat(format);
            if (mappedFormat != null) {
                formats.add(mappedFormat);
            }
        }
        return formats;
    }

    public static @Nullable AudioFormat mapSupportedAudioFormat(MediaPlayerSupportedFormat format) {
        Integer bitDepth = format.getSampleBytes() > 0 ? format.getSampleBytes() * 8 : null;
        Long frequency = format.getSampleRate() > 0 ? (long) format.getSampleRate() : null;
        Integer channels = format.getNumChannels() > 0 ? format.getNumChannels() : null;

        return switch (format.getFormat().toUpperCase(Locale.ROOT)) {
            case "MP3" -> new AudioFormat(AudioFormat.CONTAINER_NONE, AudioFormat.CODEC_MP3, null, bitDepth, null,
                    frequency, channels);
            case "WAV" -> new AudioFormat(AudioFormat.CONTAINER_WAVE, AudioFormat.CODEC_PCM_SIGNED, null, bitDepth,
                    null, frequency, channels);
            case "OGG" -> new AudioFormat(AudioFormat.CONTAINER_OGG, AudioFormat.CODEC_VORBIS, null, bitDepth, null,
                    frequency, channels);
            // Note: org.openhab.core.audio.AudioFormat added FLAC consts in 5.3, which is higher than our compile
            // target
            case "FLAC" -> new AudioFormat("FLAC", "FLAC", null, bitDepth, null, frequency, channels);
            default -> null;
        };
    }
}
