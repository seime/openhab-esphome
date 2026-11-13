package no.seime.openhab.binding.esphome.internal.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openhab.core.audio.AudioStream;
import org.openhab.core.thing.internal.ThingImpl;

import com.google.protobuf.ByteString;

import io.esphome.api.VoiceAssistantAudio;
import io.esphome.api.VoiceAssistantEvent;
import io.esphome.api.VoiceAssistantEventResponse;
import io.esphome.api.VoiceAssistantRequest;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ESPHomeVoiceAssistantAudioSourceTest {

    private static final long READER_STALL_MS = 200;
    private static final VoiceAssistantEventResponse RUN_END = VoiceAssistantEventResponse.newBuilder()
            .setEventType(VoiceAssistantEvent.VOICE_ASSISTANT_RUN_END).build();

    @Mock
    private ESPHomeHandler handler;

    private ESPHomeVoiceAssistantAudioSource source;

    @BeforeEach
    void setUp() {
        when(handler.getThing()).thenReturn(new ThingImpl(BindingConstants.THING_TYPE_DEVICE, "device"));
        when(handler.getLogPrefix()).thenReturn("device");
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

    private static VoiceAssistantAudio audio() {
        return VoiceAssistantAudio.newBuilder().setData(ByteString.copyFrom(new byte[320])).build();
    }
}
