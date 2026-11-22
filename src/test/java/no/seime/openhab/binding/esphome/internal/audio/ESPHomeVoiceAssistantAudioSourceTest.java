package no.seime.openhab.binding.esphome.internal.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openhab.core.audio.AudioStream;
import org.openhab.core.thing.internal.ThingImpl;

import com.google.protobuf.ByteString;

import io.esphome.api.VoiceAssistantAudio;
import io.esphome.api.VoiceAssistantEvent;
import io.esphome.api.VoiceAssistantEventData;
import io.esphome.api.VoiceAssistantEventResponse;
import io.esphome.api.VoiceAssistantRequest;
import io.esphome.api.VoiceAssistantRequestFlag;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.audio.ESPHomeVoiceAssistantAudioSource.ReplyPlayback;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ESPHomeVoiceAssistantAudioSourceTest {

    private static final long READER_STALL_MS = 200;
    private static final VoiceAssistantEventResponse RUN_END = VoiceAssistantEventResponse.newBuilder()
            .setEventType(VoiceAssistantEvent.VOICE_ASSISTANT_RUN_END).build();
    private static final VoiceAssistantEventResponse STT_START = VoiceAssistantEventResponse.newBuilder()
            .setEventType(VoiceAssistantEvent.VOICE_ASSISTANT_STT_START).build();
    private static final VoiceAssistantEventResponse WAKE_WORD_START = VoiceAssistantEventResponse.newBuilder()
            .setEventType(VoiceAssistantEvent.VOICE_ASSISTANT_WAKE_WORD_START).build();
    private static final VoiceAssistantEventResponse STT_VAD_END = VoiceAssistantEventResponse.newBuilder()
            .setEventType(VoiceAssistantEvent.VOICE_ASSISTANT_STT_VAD_END).build();
    private static final VoiceAssistantEventResponse TTS_START = VoiceAssistantEventResponse.newBuilder()
            .setEventType(VoiceAssistantEvent.VOICE_ASSISTANT_TTS_START)
            .addData(VoiceAssistantEventData.newBuilder().setName("text").setValue("...")).build();
    private static final String REPLY_URL = "http://openhab:8080/audio/reply";
    private static final VoiceAssistantEventResponse TTS_END = VoiceAssistantEventResponse.newBuilder()
            .setEventType(VoiceAssistantEvent.VOICE_ASSISTANT_TTS_END)
            .addData(VoiceAssistantEventData.newBuilder().setName("url").setValue(REPLY_URL)).build();

    @Mock
    private ESPHomeHandler handler;

    private ESPHomeVoiceAssistantAudioSource source;
    private final List<ScheduledTask> scheduled = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        when(handler.getThing()).thenReturn(new ThingImpl(BindingConstants.THING_TYPE_DEVICE, "device"));
        when(handler.getLogPrefix()).thenReturn("device");
        when(handler.schedule(any(), anyLong(), any())).thenAnswer(invocation -> {
            TimeUnit unit = invocation.getArgument(2);
            ScheduledTask task = new ScheduledTask(invocation.getArgument(0), unit.toMillis(invocation.getArgument(1)));
            scheduled.add(task);
            return task;
        });
        source = new ESPHomeVoiceAssistantAudioSource(handler, READER_STALL_MS);
    }

    @AfterEach
    void tearDown() {
        source.close();
    }

    @Test
    void endsRunWhenNothingReadsTheAudio() throws Exception {
        AudioStream stream = source.getInputStream(source.getSupportedFormats().iterator().next());
        source.handleVoiceAssistantRequest(VoiceAssistantRequest.newBuilder().setStart(true).build());
        source.handleVoiceAssistantAudio(audio());
        assertEquals(320, stream.read(new byte[320]));

        CountDownLatch runEnded = new CountDownLatch(1);
        doAnswer(invocation -> {
            runEnded.countDown();
            return null;
        }).when(handler).sendMessage(RUN_END);

        // reader stopped without closing the stream
        for (int i = 0; i < 150 && runEnded.getCount() > 0; i++) {
            source.handleVoiceAssistantAudio(audio());
            runEnded.await(20, TimeUnit.MILLISECONDS);
        }

        verify(handler).sendMessage(RUN_END);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertEquals(-1, stream.read()));
    }

    @Test
    void keepsRunWhileReaderWaitsForAudio() throws Exception {
        AudioStream stream = source.getInputStream(source.getSupportedFormats().iterator().next());
        CompletableFuture<Integer> read = CompletableFuture.supplyAsync(() -> {
            try {
                return stream.read(new byte[320]);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        source.handleVoiceAssistantRequest(VoiceAssistantRequest.newBuilder().setStart(true).build());

        // reader waits for audio longer than the stall timeout
        Thread.sleep(READER_STALL_MS * 2);
        source.handleVoiceAssistantAudio(audio());

        assertEquals(320, read.get());
        verify(handler, never()).sendMessage(RUN_END);
        stream.close();
    }

    @Test
    void keepsOtherReadersWhenOneStalls() throws Exception {
        AudioStream reading = source.getInputStream(source.getSupportedFormats().iterator().next());
        AudioStream stalled = source.getInputStream(source.getSupportedFormats().iterator().next());
        source.handleVoiceAssistantRequest(VoiceAssistantRequest.newBuilder().setStart(true).build());

        for (int i = 0; i < 30; i++) {
            source.handleVoiceAssistantAudio(audio());
            assertEquals(320, reading.read(new byte[320]));
            Thread.sleep(20);
        }

        verify(handler, never()).sendMessage(RUN_END);
        assertEquals(-1, stalled.read());
        reading.close();
        verify(handler).sendMessage(RUN_END);
    }

    @Test
    void endsRunWhenTheReaderFallsTooFarBehind() throws Exception {
        AudioStream stream = source.getInputStream(source.getSupportedFormats().iterator().next());
        source.handleVoiceAssistantRequest(VoiceAssistantRequest.newBuilder().setStart(true).build());

        CountDownLatch runEnded = new CountDownLatch(1);
        doAnswer(invocation -> {
            runEnded.countDown();
            return null;
        }).when(handler).sendMessage(RUN_END);

        // reads, but slower than the audio arrives
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 100 && runEnded.getCount() > 0; i++) {
                source.handleVoiceAssistantAudio(audio(4096));
                stream.read();
                runEnded.await(5, TimeUnit.MILLISECONDS);
            }
        });

        verify(handler).sendMessage(RUN_END);
    }

    @Test
    void endsTheRunWhenTheDeviceStopsIt() throws Exception {
        AudioStream stream = startDialogRun();

        source.handleVoiceAssistantRequest(VoiceAssistantRequest.newBuilder().setStart(false).build());

        verify(handler).sendMessage(RUN_END);
        verify(handler, never()).sendMessage(STT_VAD_END);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertEquals(-1, stream.read()));
    }

    @Test
    void doesNotShowListeningWhileOpenHABDetectsTheWakeWord() throws Exception {
        AudioStream stream = source.getInputStream(source.getSupportedFormats().iterator().next());

        source.handleVoiceAssistantRequest(VoiceAssistantRequest.newBuilder().setStart(true)
                .setFlags(VoiceAssistantRequestFlag.VOICE_ASSISTANT_REQUEST_USE_WAKE_WORD_VALUE).build());

        verify(handler).sendMessage(WAKE_WORD_START);
        verify(handler, never()).sendMessage(STT_START);
        stream.close();
    }

    @Test
    void showsThinkingUntilTheReplyPlays() throws Exception {
        startDialogRun().close();

        verify(handler).sendMessage(STT_VAD_END);
        assertEquals(ReplyPlayback.SINK, source.startReply(null, () -> {
        }));
        verify(handler).sendMessage(TTS_START);
        verify(handler, never()).sendMessage(RUN_END);
    }

    @Test
    void stopsTheMicrophoneWhenTheReplyPlaysWhileListening() throws Exception {
        AudioStream stream = startDialogRun();

        assertEquals(ReplyPlayback.DEVICE, source.startReply(REPLY_URL, () -> {
        }));

        verify(handler).sendMessage(STT_VAD_END);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertEquals(-1, stream.read()));
        verify(handler, never()).sendMessage(TTS_END);
        runMicrophoneStopWait();
        verify(handler).sendMessage(TTS_END);
    }

    @Test
    void ignoresAudioThatIsNotAReply() throws Exception {
        assertEquals(ReplyPlayback.NONE, source.startReply(null, () -> {
        }));
        verify(handler, never()).sendMessage(TTS_START);
    }

    @Test
    void endsTheRunWhenNoReplyComes() throws Exception {
        startDialogRun().close();

        takeScheduled(ESPHomeVoiceAssistantAudioSource.REPLY_TIMEOUT_SECONDS * 1000).run();

        verify(handler).sendMessage(RUN_END);
        assertEquals(ReplyPlayback.NONE, source.startReply(null, () -> {
        }));
    }

    @Test
    void endsTheRunOnceTheMicrophoneHasStoppedWhenTheDialogFinishesWithoutAReply() throws Exception {
        startDialogRun().close();
        verify(handler).sendMessage(STT_VAD_END);

        source.onDialogIdle();
        verify(handler, never()).sendMessage(RUN_END);

        runMicrophoneStopWait();

        verify(handler).sendMessage(RUN_END);
        assertEquals(ReplyPlayback.NONE, source.startReply(null, () -> {
        }));
    }

    @Test
    void doesNotEndTheNextRunWhenTheDialogFinishedWithoutAReply() throws Exception {
        startDialogRun().close();
        source.onDialogIdle();
        Runnable endRun = takeScheduled(0, ESPHomeVoiceAssistantAudioSource.MICROPHONE_STOP_MS);

        AudioStream stream = startDialogRun();
        endRun.run();

        verify(handler, never()).sendMessage(RUN_END);
        stream.close();
    }

    @Test
    void keepsTheRunUntilTheReplyHasPlayedWhenTheDialogFinishes() throws Exception {
        startDialogRun().close();
        assertEquals(ReplyPlayback.SINK, source.startReply(null, () -> {
        }));

        source.onDialogIdle();
        verify(handler, never()).sendMessage(RUN_END);

        source.endReply();
        verify(handler).sendMessage(RUN_END);
    }

    @Test
    void letsTheDevicePlayTheReplyAndEndTheRun() throws Exception {
        startDialogRun().close();

        assertEquals(ReplyPlayback.DEVICE, source.startReply(REPLY_URL, () -> {
        }));
        verify(handler, never()).sendMessage(TTS_START);
        runMicrophoneStopWait();

        InOrder inOrder = Mockito.inOrder(handler);
        inOrder.verify(handler).sendMessage(STT_VAD_END);
        inOrder.verify(handler).sendMessage(continueConversation(false));
        inOrder.verify(handler).sendMessage(TTS_START);
        inOrder.verify(handler).sendMessage(TTS_END);
        inOrder.verify(handler).sendMessage(RUN_END);
        source.endReply();
        verify(handler, times(1)).sendMessage(RUN_END);
    }

    @Test
    void endsTheRunWhenTheReplyEndsBeforeTheDeviceGetsIt() throws Exception {
        startDialogRun().close();
        source.startReply(REPLY_URL, () -> {
        });

        source.endReply();

        verify(handler).sendMessage(RUN_END);
        verify(handler, never()).sendMessage(TTS_END);
    }

    @Test
    void asksTheDeviceToListenAgainWhenTheFollowUpStartsBeforeItGetsTheReply() throws Exception {
        startDialogRun().close();
        source.startReply(REPLY_URL, () -> {
        });

        source.getInputStream(source.getSupportedFormats().iterator().next());
        runMicrophoneStopWait();

        InOrder inOrder = Mockito.inOrder(handler);
        inOrder.verify(handler).sendMessage(continueConversation(true));
        inOrder.verify(handler).sendMessage(TTS_END);
        verify(handler, never()).sendMessage(continueConversation(false));
    }

    @Test
    void passesTheDevicesNextRunToOpenHABsFollowUp() throws Exception {
        startDialogRun().close();
        startDeviceReply();

        AudioStream followUp = source.getInputStream(source.getSupportedFormats().iterator().next());
        verify(handler).sendMessage(continueConversation(true));
        source.endReply();

        // the device listens again once it has played the reply
        source.handleVoiceAssistantRequest(VoiceAssistantRequest.newBuilder().setStart(true).build());
        source.handleVoiceAssistantAudio(audio());
        assertEquals(320, followUp.read(new byte[320]));
        followUp.close();
    }

    @Test
    void tellsTheDeviceNotToListenAgainWhenOpenHABStopsTheFollowUp() throws Exception {
        startDialogRun().close();
        startDeviceReply();

        source.getInputStream(source.getSupportedFormats().iterator().next()).close();

        InOrder inOrder = Mockito.inOrder(handler);
        inOrder.verify(handler).sendMessage(continueConversation(false));
        inOrder.verify(handler).sendMessage(continueConversation(true));
        inOrder.verify(handler).sendMessage(continueConversation(false));
    }

    @Test
    void endsAFollowUpTheDeviceCannotListenFor() throws Exception {
        startDialogRun().close();
        // the device only listens again after replies it plays itself
        source.startReply(null, () -> {
        });

        AudioStream followUp = source.getInputStream(source.getSupportedFormats().iterator().next());
        source.endReply();

        verify(handler, never()).sendMessage(continueConversation(true));
        takeScheduled(ESPHomeVoiceAssistantAudioSource.FOLLOW_UP_START_MS).run();
        assertEquals(-1, followUp.read());
    }

    @Test
    void endsAFollowUpThatStartsAfterTheDeviceHasPlayedTheReply() throws Exception {
        startDialogRun().close();
        startDeviceReply();
        source.endReply();

        AudioStream followUp = source.getInputStream(source.getSupportedFormats().iterator().next());

        verify(handler, never()).sendMessage(continueConversation(true));
        takeScheduled(ESPHomeVoiceAssistantAudioSource.FOLLOW_UP_START_MS).run();
        assertEquals(-1, followUp.read());
    }

    private void startDeviceReply() {
        assertEquals(ReplyPlayback.DEVICE, source.startReply(REPLY_URL, () -> {
        }));
        runMicrophoneStopWait();
    }

    private void runMicrophoneStopWait() {
        takeScheduled(0, ESPHomeVoiceAssistantAudioSource.MICROPHONE_STOP_MS).run();
    }

    private Runnable takeScheduled(long delayMs) {
        return takeScheduled(delayMs, delayMs);
    }

    /**
     * Removes the last task still scheduled with a delay in the range.
     */
    private Runnable takeScheduled(long minDelayMs, long maxDelayMs) {
        for (int i = scheduled.size() - 1; i >= 0; i--) {
            ScheduledTask task = scheduled.get(i);
            if (task.delayMs >= minDelayMs && task.delayMs <= maxDelayMs) {
                scheduled.remove(i);
                return task.task;
            }
        }
        throw new AssertionError("Nothing scheduled within " + minDelayMs + "-" + maxDelayMs + " ms");
    }

    /**
     * A task that only runs when the test runs it.
     */
    private class ScheduledTask extends CompletableFuture<Void> implements ScheduledFuture<Void> {
        private final Runnable task;
        private final long delayMs;

        ScheduledTask(Runnable task, long delayMs) {
            this.task = task;
            this.delayMs = delayMs;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            scheduled.remove(this);
            return super.cancel(mayInterruptIfRunning);
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(delayMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(delayMs, other.getDelay(TimeUnit.MILLISECONDS));
        }
    }

    private static VoiceAssistantEventResponse continueConversation(boolean continueConversation) {
        return VoiceAssistantEventResponse.newBuilder().setEventType(VoiceAssistantEvent.VOICE_ASSISTANT_INTENT_END)
                .addData(VoiceAssistantEventData.newBuilder().setName("continue_conversation")
                        .setValue(continueConversation ? "1" : "0"))
                .build();
    }

    private AudioStream startDialogRun() throws Exception {
        source.setDialogTrigger(() -> {
        });
        AudioStream stream = source.getInputStream(source.getSupportedFormats().iterator().next());
        source.handleVoiceAssistantRequest(VoiceAssistantRequest.newBuilder().setStart(true).build());
        return stream;
    }

    private static VoiceAssistantAudio audio() {
        return audio(320);
    }

    private static VoiceAssistantAudio audio(int length) {
        return VoiceAssistantAudio.newBuilder().setData(ByteString.copyFrom(new byte[length])).build();
    }
}
