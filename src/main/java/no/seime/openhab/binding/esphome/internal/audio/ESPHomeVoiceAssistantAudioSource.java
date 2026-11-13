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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import io.esphome.api.VoiceAssistantEvent;
import io.esphome.api.VoiceAssistantEventResponse;
import io.esphome.api.VoiceAssistantRequest;
import io.esphome.api.VoiceAssistantRequestFlag;
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
    // must hold more audio than the stall timeout
    private static final int PIPE_SIZE = 128 * 1024;
    private static final long DEFAULT_READER_STALL_MS = 2000;

    private final Logger logger = LoggerFactory.getLogger(ESPHomeVoiceAssistantAudioSource.class);

    private final ESPHomeHandler handler;
    private final String id;
    private final String label;
    private final long readerStallNanos;

    private final Object lock = new Object();
    private final BlockingQueue<byte[]> audioQueue = new ArrayBlockingQueue<>(AUDIO_QUEUE_CAPACITY);
    private final ExecutorService audioStreamExecutor;
    private final ExecutorService dialogExecutor;
    private final Set<ReadTrackingStream> openStreams = ConcurrentHashMap.newKeySet();
    private @Nullable Future<?> workerFuture;

    private volatile PipedAudioStream.Group streamGroup = PipedAudioStream.newGroup(AUDIO_FORMAT, PIPE_SIZE);
    private boolean subscribed;
    private boolean closed;
    private volatile boolean streaming;
    private int pendingDialogRequests;
    private volatile @Nullable Runnable dialogTrigger;

    public ESPHomeVoiceAssistantAudioSource(ESPHomeHandler handler) {
        this(handler, DEFAULT_READER_STALL_MS);
    }

    ESPHomeVoiceAssistantAudioSource(ESPHomeHandler handler, long readerStallMs) {
        this.handler = handler;
        this.readerStallNanos = TimeUnit.MILLISECONDS.toNanos(readerStallMs);
        this.id = handler.getThing().getUID() + ":source:voice_assistant";
        String thingLabel = handler.getThing().getLabel() != null ? handler.getThing().getLabel()
                : handler.getThing().getUID().getId();
        this.label = "ESPHome " + thingLabel + " microphone";
        this.audioStreamExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "ESPHome-VA-AudioSource-" + id);
            thread.setDaemon(true);
            return thread;
        });
        this.dialogExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "ESPHome-VA-Dialog-" + id);
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
                if (closed) {
                    throw new AudioException("Device disconnected");
                }
                PipedAudioStream stream = streamGroup.getAudioStreamInGroup();
                ReadTrackingStream trackedStream = new ReadTrackingStream(stream);
                openStreams.add(trackedStream);
                stream.onClose(() -> openStreams.remove(trackedStream));
                stream.onClose(this::onStreamClosed);
                ensureSubscribed();
                return trackedStream;
            }
        } catch (IOException | ProtocolAPIError e) {
            throw new AudioException("Failed to open ESPHome voice assistant audio stream", e);
        }
    }

    /**
     * Sets what runs when the device hears its wake word.
     */
    public void setDialogTrigger(@Nullable Runnable dialogTrigger) {
        synchronized (lock) {
            this.dialogTrigger = dialogTrigger;
            if (dialogTrigger == null && streamGroup.isEmpty()) {
                unsubscribe();
            }
        }
    }

    public boolean hasDialogTrigger() {
        return dialogTrigger != null;
    }

    /**
     * The device only reports wake words while subscribed.
     *
     * @return false if subscribing failed
     */
    public boolean subscribeForDialogTrigger() {
        synchronized (lock) {
            try {
                ensureSubscribed();
                return true;
            } catch (ProtocolAPIError e) {
                logger.debug("[{}] Failed to subscribe to voice assistant", handler.getLogPrefix(), e);
                return false;
            }
        }
    }

    public void handleVoiceAssistantRequest(VoiceAssistantRequest request) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            Runnable trigger = dialogTrigger;
            if (request.getStart() && trigger != null && streamGroup.isEmpty()) {
                if ((request.getFlags() & VoiceAssistantRequestFlag.VOICE_ASSISTANT_REQUEST_USE_WAKE_WORD_VALUE) != 0) {
                    logger.debug("[{}] Device isn't detecting the wake word itself, not starting dialog",
                            handler.getLogPrefix());
                } else {
                    logger.debug("[{}] Heard wake word '{}', starting dialog", handler.getLogPrefix(),
                            request.getWakeWordPhrase());
                    handleOnDialogExecutor(request, trigger);
                    return;
                }
            }
            if (pendingDialogRequests > 0) {
                // wait for the dialog to start
                handleOnDialogExecutor(request, null);
                return;
            }
            handleRequest(request);
        }
    }

    /**
     * Runs on the dialog thread. Starting a dialog can be slow.
     */
    private void handleOnDialogExecutor(VoiceAssistantRequest request, @Nullable Runnable trigger) {
        pendingDialogRequests++;
        dialogExecutor.execute(() -> {
            try {
                if (trigger != null) {
                    // opens a stream on this source
                    trigger.run();
                }
            } catch (RuntimeException e) {
                logger.warn("[{}] Failed to start dialog", handler.getLogPrefix(), e);
            } finally {
                synchronized (lock) {
                    pendingDialogRequests--;
                    if (!closed) {
                        handleRequest(request);
                    }
                }
            }
        });
    }

    private void handleRequest(VoiceAssistantRequest request) {
        if (!request.getStart()) {
            endSession();
            return;
        }

        if (streamGroup.isEmpty()) {
            try {
                handler.sendMessage(VoiceAssistantResponse.newBuilder().setError(true).build());
            } catch (ProtocolAPIError e) {
                logger.debug("[{}] Failed to reject unsolicited voice assistant request", handler.getLogPrefix(), e);
            }
            return;
        }

        try {
            handler.sendMessage(VoiceAssistantResponse.newBuilder().setPort(0).setError(false).build());
            streaming = true;
            sendEvent(VoiceAssistantEvent.VOICE_ASSISTANT_RUN_START);
            sendEvent(VoiceAssistantEvent.VOICE_ASSISTANT_STT_START);
            audioQueue.clear();
            openStreams.forEach(ReadTrackingStream::restartStallTimer);
            PipedAudioStream.Group activeGroup = streamGroup;
            workerFuture = audioStreamExecutor.submit(() -> processAudioQueue(activeGroup));
        } catch (ProtocolAPIError e) {
            logger.debug("[{}] Failed to accept voice assistant request", handler.getLogPrefix(), e);
            endSession();
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
                if (hasStalledReader()) {
                    logger.debug("[{}] Audio stalled, stopping the microphone", handler.getLogPrefix());
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
                    endRun();
                    endSession();
                }
            }
        }
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            dialogTrigger = null;
            endSession();
            unsubscribe();
            audioStreamExecutor.shutdownNow();
            dialogExecutor.shutdown();
        }
    }

    private boolean hasStalledReader() {
        long now = System.nanoTime();
        return openStreams.stream().anyMatch(stream -> stream.isStalled(now, readerStallNanos));
    }

    private void ensureSubscribed() throws ProtocolAPIError {
        if (closed) {
            throw new ProtocolAPIError(String.format("[%s] Voice assistant source is closed", handler.getLogPrefix()));
        }
        if (!subscribed) {
            handler.sendMessage(SubscribeVoiceAssistantRequest.newBuilder().setSubscribe(true)
                    .setFlags(1 /* VOICE_ASSISTANT_SUBSCRIBE_API_AUDIO */).build());
            subscribed = true;
        }
    }

    private void onStreamClosed() {
        synchronized (lock) {
            if (streamGroup.isEmpty()) {
                endRun();
                endSession();
                if (dialogTrigger == null) {
                    unsubscribe();
                }
            }
        }
    }

    /**
     * Tells the device to stop streaming. Older firmware ignores RUN_END after STT_VAD_END.
     */
    private void endRun() {
        if (streaming) {
            sendEvent(VoiceAssistantEvent.VOICE_ASSISTANT_RUN_END);
        }
    }

    private void sendEvent(VoiceAssistantEvent event) {
        try {
            handler.sendMessage(VoiceAssistantEventResponse.newBuilder().setEventType(event).build());
        } catch (ProtocolAPIError e) {
            logger.debug("[{}] Failed to send {}", handler.getLogPrefix(), event, e);
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
        streamGroup = PipedAudioStream.newGroup(AUDIO_FORMAT, PIPE_SIZE);
    }

    /**
     * Some readers may stop processing data without closing the stream.
     */
    private static class ReadTrackingStream extends AudioStream {
        private final PipedAudioStream stream;
        private volatile boolean reading;
        private volatile long lastReadNanos = System.nanoTime();

        ReadTrackingStream(PipedAudioStream stream) {
            this.stream = stream;
        }

        @Override
        public AudioFormat getFormat() {
            return stream.getFormat();
        }

        @Override
        public int read() throws IOException {
            reading = true;
            try {
                return stream.read();
            } finally {
                markRead();
            }
        }

        @Override
        public int read(byte @Nullable [] b, int off, int len) throws IOException {
            reading = true;
            try {
                return stream.read(b, off, len);
            } finally {
                markRead();
            }
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }

        private void markRead() {
            lastReadNanos = System.nanoTime();
            reading = false;
        }

        void restartStallTimer() {
            lastReadNanos = System.nanoTime();
        }

        boolean isStalled(long now, long stallNanos) {
            return !reading && now - lastReadNanos > stallNanos;
        }
    }
}
