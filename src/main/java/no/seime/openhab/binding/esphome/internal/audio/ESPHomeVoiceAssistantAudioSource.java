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

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

/**
 * An EPSHome {@link AudioSource} implementation receiving audio from an ESPHome voice assistant over the native API.
 *
 * @author Cody Cutrer - Initial contribution
 */
@NonNullByDefault
public class ESPHomeVoiceAssistantAudioSource implements AudioSource {

    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(AudioFormat.CONTAINER_NONE,
            AudioFormat.CODEC_PCM_SIGNED, false, 16, null, 16000L, 1);
    private static final byte[] END_OF_STREAM_MARKER = new byte[0];
    private static final int AUDIO_QUEUE_CAPACITY = 128;

    private final Logger logger = LoggerFactory.getLogger(ESPHomeVoiceAssistantAudioSource.class);

    private final ESPHomeHandler handler;
    private final String id;
    private final String label;

    private final Object lock = new Object();
    private final BlockingQueue<byte[]> audioQueue = new ArrayBlockingQueue<>(AUDIO_QUEUE_CAPACITY);
    private final ExecutorService audioStreamExecutor;
    private @Nullable Future<?> workerFuture;

    private volatile PipedAudioStream.Group streamGroup = PipedAudioStream.newGroup(AUDIO_FORMAT, 16 * 1024);
    private boolean subscribed;
    private volatile boolean streaming;

    public ESPHomeVoiceAssistantAudioSource(ESPHomeHandler handler) {
        this.handler = handler;
        this.id = handler.getThing().getUID() + ":source:voice_assistant";
        String thingLabel = handler.getThing().getLabel() != null ? handler.getThing().getLabel()
                : handler.getThing().getUID().getId();
        this.label = "ESPHome " + thingLabel + " microphone";
        this.audioStreamExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "ESPHome-VA-AudioSource-" + id);
            thread.setDaemon(true);
            return thread;
        });
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
                audioQueue.clear();
                PipedAudioStream.Group activeGroup = streamGroup;
                workerFuture = audioStreamExecutor.submit(() -> processAudioQueue(activeGroup));
            } catch (ProtocolAPIError e) {
                logger.debug("[{}] Failed to accept voice assistant request", handler.getLogPrefix(), e);
                endSession();
            }
        }
    }

    public void handleVoiceAssistantAudio(VoiceAssistantAudio audio) {
        // Do NOT lock in "hot" ingestion path, instead rely on thread-safe visibility of volatile streaming flag

        if (!streaming) {
            return;
        }

        if (!audio.getData().isEmpty()) {
            byte[] data = audio.getData().toByteArray();
            while (!audioQueue.offer(data)) {
                audioQueue.poll(); // Evict oldest frame to prevent unbounded memory growth & keep latency low
            }
        }

        if (audio.getEnd()) {
            while (!audioQueue.offer(END_OF_STREAM_MARKER)) {
                audioQueue.poll(); // Evict oldest frame to prevent END_OF_STREAM_MARKER being dropped silently
            }
        }
    }

    private void processAudioQueue(PipedAudioStream.Group targetGroup) {
        try {
            while (streaming && !Thread.currentThread().isInterrupted()) {
                byte[] chunk = audioQueue.take();
                if (chunk == END_OF_STREAM_MARKER || chunk.length == 0) {
                    break;
                }
                try {
                    targetGroup.write(chunk);
                    targetGroup.flush();
                } catch (RuntimeException e) {
                    logger.debug("[{}] Error forwarding ESPHome voice assistant audio", handler.getLogPrefix(), e);
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (lock) {
                if (this.streamGroup == targetGroup) {
                    endSession();
                }
            }
        }
    }

    public void close() {
        synchronized (lock) {
            endSession();
            unsubscribe();
            audioStreamExecutor.shutdownNow();
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
                endSession();
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
        Future<?> future = workerFuture;
        if (future != null) {
            future.cancel(true);
            workerFuture = null;
        }
        audioQueue.clear();
        streamGroup.close();
        streamGroup = PipedAudioStream.newGroup(AUDIO_FORMAT, 16 * 1024);
    }
}
