package no.seime.openhab.binding.esphome.internal.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openhab.core.audio.AudioException;
import org.openhab.core.audio.AudioFormat;
import org.openhab.core.audio.AudioHTTPServer;
import org.openhab.core.audio.AudioSink;
import org.openhab.core.audio.AudioSource;
import org.openhab.core.audio.AudioStream;
import org.openhab.core.audio.StreamServed;
import org.openhab.core.audio.URLAudioStream;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.library.CoreItemFactory;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.PlayPauseType;
import org.openhab.core.net.NetworkAddressService;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.internal.ThingImpl;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

import com.google.protobuf.ByteString;
import com.jano7.executor.KeySequentialExecutor;

import io.esphome.api.DeviceInfoResponse;
import io.esphome.api.ListEntitiesDoneResponse;
import io.esphome.api.ListEntitiesMediaPlayerResponse;
import io.esphome.api.MediaPlayerCommand;
import io.esphome.api.MediaPlayerCommandRequest;
import io.esphome.api.MediaPlayerFormatPurpose;
import io.esphome.api.MediaPlayerState;
import io.esphome.api.MediaPlayerStateResponse;
import io.esphome.api.MediaPlayerSupportedFormat;
import io.esphome.api.SubscribeVoiceAssistantRequest;
import io.esphome.api.VoiceAssistantAudio;
import io.esphome.api.VoiceAssistantEvent;
import io.esphome.api.VoiceAssistantEventResponse;
import io.esphome.api.VoiceAssistantRequest;
import io.esphome.api.VoiceAssistantRequestFlag;
import io.esphome.api.VoiceAssistantResponse;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.ESPHomeConfiguration;
import no.seime.openhab.binding.esphome.internal.ESPHomeVersionService;
import no.seime.openhab.binding.esphome.internal.FirmwareUpgradeService;
import no.seime.openhab.binding.esphome.internal.MonitoredCompositeExecutorService;
import no.seime.openhab.binding.esphome.internal.audio.ESPHomeAudioSink;
import no.seime.openhab.binding.esphome.internal.audio.ESPHomeVoiceAssistantAudioSource;
import no.seime.openhab.binding.esphome.internal.audio.MediaPlayerFeature;
import no.seime.openhab.binding.esphome.internal.audio.VoiceAssistantFeature;
import no.seime.openhab.binding.esphome.internal.comm.ConnectionSelector;
import no.seime.openhab.binding.esphome.internal.comm.EncryptedFrameHelper;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.discovery.ESPHomeMDNSHostnameResolver;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.ESPHomeEventSubscriber;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ESPHomeHandlerAudioServicesTest {

    @Mock
    private ESPChannelTypeProvider channelTypeProvider;
    @Mock
    private ESPStateDescriptionProvider stateDescriptionProvider;
    @Mock
    private ESPHomeEventSubscriber eventSubscriber;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private BundleContext bundleContext;
    @Mock
    private AudioHTTPServer audioHTTPServer;
    @Mock
    private NetworkAddressService networkAddressService;
    @Mock
    private ServiceRegistration<AudioSink> audioSinkRegistration;
    @Mock
    private ServiceRegistration<AudioSource> audioSourceRegistration;
    @Mock
    private EncryptedFrameHelper frameHelper;
    @Mock
    private ThingHandlerCallback callback;
    @Mock
    private ESPHomeVersionService versionService;
    @Mock
    private FirmwareUpgradeService firmwareUpgradeService;
    @Mock
    private ESPHomeMDNSHostnameResolver mdnsHostnameResolver;
    @Mock
    private Bundle dialogProviderBundle;

    private static final String DIALOG_PROVIDER = "org.openhab.core.audio.AudioDialogProvider";
    private static final VoiceAssistantResponse ACCEPT = VoiceAssistantResponse.newBuilder().setPort(0).setError(false)
            .build();
    private static final VoiceAssistantResponse REJECT = VoiceAssistantResponse.newBuilder().setError(true).build();
    private static final SubscribeVoiceAssistantRequest SUBSCRIBE = SubscribeVoiceAssistantRequest.newBuilder()
            .setSubscribe(true).setFlags(1).build();
    private static final SubscribeVoiceAssistantRequest UNSUBSCRIBE = SubscribeVoiceAssistantRequest.newBuilder()
            .setSubscribe(false).build();

    private ESPHomeHandler handler;
    private MonitoredCompositeExecutorService executor;
    private ExecutorService packetProcessorExecutor;

    @BeforeEach
    void setUp() throws Exception {
        executor = new MonitoredCompositeExecutorService(Executors.newScheduledThreadPool(1),
                (ThreadPoolExecutor) Executors.newCachedThreadPool(), 1000);
        packetProcessorExecutor = Executors.newSingleThreadExecutor();

        Mockito.lenient().when(networkAddressService.getPrimaryIpv4HostAddress()).thenReturn("127.0.0.1");
        Mockito.lenient().when(versionService.createFirmwareUpdateAvailableChannel(any()))
                .thenAnswer(i -> ChannelBuilder
                        .create(new ChannelUID((ThingUID) i.getArgument(0), "firmwareUpdateAvailable")).build());
        Mockito.lenient().when(versionService.createLatestFirmwareVersionChannel(any())).thenAnswer(i -> ChannelBuilder
                .create(new ChannelUID((ThingUID) i.getArgument(0), "latestFirmwareVersion")).build());

        handler = new ESPHomeHandler(new ThingImpl(BindingConstants.THING_TYPE_DEVICE, "device"),
                new ConnectionSelector(), channelTypeProvider, stateDescriptionProvider, eventSubscriber, executor,
                new KeySequentialExecutor(packetProcessorExecutor), eventPublisher, null, null, bundleContext,
                versionService, firmwareUpgradeService, mdnsHostnameResolver, audioHTTPServer, networkAddressService);
        handler.setCallback(callback);
        setField("config", new ESPHomeConfiguration());
        setField("connectionState", enumValue(getFieldType("connectionState"), "CONNECTED"));
        setField("frameHelper", frameHelper);
    }

    @AfterEach
    void tearDown() {
        handler.dispose();
        executor.shutdownNow();
        packetProcessorExecutor.shutdownNow();
    }

    @Test
    void registersAudioSinkForEachMediaPlayerEntity() throws Exception {
        when(bundleContext.registerService(eq(AudioSink.class), any(AudioSink.class), any(Dictionary.class)))
                .thenReturn(audioSinkRegistration);

        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One")
                .addSupportedFormats(MediaPlayerSupportedFormat.newBuilder().setFormat("MP3").build()).build());
        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(12).setObjectId("speaker_two")
                .setName("Speaker Two")
                .addSupportedFormats(MediaPlayerSupportedFormat.newBuilder().setFormat("WAV").build()).build());

        invokeHandleConnected(ListEntitiesDoneResponse.getDefaultInstance());

        verify(bundleContext, times(2)).registerService(eq(AudioSink.class), any(AudioSink.class),
                any(Dictionary.class));
    }

    @Test
    void routesToAnnouncementOrDefaultChannelBasedOnFormat() throws Exception {
        ArgumentCaptor<AudioSink> sinkCaptor = ArgumentCaptor.forClass(AudioSink.class);
        when(bundleContext.registerService(eq(AudioSink.class), sinkCaptor.capture(), any(Dictionary.class)))
                .thenReturn(audioSinkRegistration);

        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One")
                .addSupportedFormats(MediaPlayerSupportedFormat.newBuilder().setFormat("MP3")
                        .setPurpose(MediaPlayerFormatPurpose.MEDIA_PLAYER_FORMAT_PURPOSE_DEFAULT).build())
                .addSupportedFormats(MediaPlayerSupportedFormat.newBuilder().setFormat("WAV")
                        .setPurpose(MediaPlayerFormatPurpose.MEDIA_PLAYER_FORMAT_PURPOSE_ANNOUNCEMENT).build())
                .build());

        invokeHandleConnected(ListEntitiesDoneResponse.getDefaultInstance());

        AudioSink sink = sinkCaptor.getValue();
        assertNotNull(sink);
        assertTrue(
                sink.getSupportedFormats().stream().anyMatch(f -> AudioFormat.CODEC_PCM_SIGNED.equals(f.getCodec())));
        assertTrue(sink.getSupportedFormats().stream().anyMatch(f -> AudioFormat.CODEC_MP3.equals(f.getCodec())));

        URLAudioStream wavStream = Mockito.mock(URLAudioStream.class);
        when(wavStream.getURL()).thenReturn("http://example.com/sound.wav");
        when(wavStream.getFormat()).thenReturn(AudioFormat.WAV);
        sink.processAndComplete(wavStream);

        verify(frameHelper).send(MediaPlayerCommandRequest.newBuilder().setKey(11).setHasMediaUrl(true)
                .setMediaUrl("http://example.com/sound.wav").setHasAnnouncement(true).setAnnouncement(true).build());

        URLAudioStream mp3Stream = Mockito.mock(URLAudioStream.class);
        when(mp3Stream.getURL()).thenReturn("http://example.com/sound.mp3");
        when(mp3Stream.getFormat()).thenReturn(AudioFormat.MP3);
        sink.processAndComplete(mp3Stream);

        verify(frameHelper).send(MediaPlayerCommandRequest.newBuilder().setKey(11).setHasMediaUrl(true)
                .setMediaUrl("http://example.com/sound.mp3").setHasAnnouncement(true).setAnnouncement(false).build());
    }

    @Test
    void supportsGenericURLAudioStreamAndTracksPlaybackLifecycle() throws Exception {
        ArgumentCaptor<AudioSink> sinkCaptor = ArgumentCaptor.forClass(AudioSink.class);
        when(bundleContext.registerService(eq(AudioSink.class), sinkCaptor.capture(), any(Dictionary.class)))
                .thenReturn(audioSinkRegistration);

        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One")
                .addSupportedFormats(MediaPlayerSupportedFormat.newBuilder().setFormat("MP3").setSampleRate(44100)
                        .setPurpose(MediaPlayerFormatPurpose.MEDIA_PLAYER_FORMAT_PURPOSE_ANNOUNCEMENT).build())
                .build());

        invokeHandleConnected(ListEntitiesDoneResponse.getDefaultInstance());

        AudioSink sink = sinkCaptor.getValue();
        assertNotNull(sink);

        URLAudioStream urlStream = Mockito.mock(URLAudioStream.class);
        when(urlStream.getURL()).thenReturn("http://example.com/sound.mp3");
        when(urlStream.getFormat()).thenReturn(AudioFormat.MP3);
        CompletableFuture<Void> playFuture = sink.processAndComplete(urlStream);

        verify(frameHelper).send(MediaPlayerCommandRequest.newBuilder().setKey(11).setHasMediaUrl(true)
                .setMediaUrl("http://example.com/sound.mp3").setHasAnnouncement(true).setAnnouncement(true).build());

        // Playback should not finish immediately
        assertFalse(playFuture.isDone());

        // Device enters announcing state
        invokeHandleConnected(MediaPlayerStateResponse.newBuilder().setKey(11)
                .setState(MediaPlayerState.MEDIA_PLAYER_STATE_ANNOUNCING).build());
        assertFalse(playFuture.isDone());

        // Device finishes announcing and returns to idle
        invokeHandleConnected(MediaPlayerStateResponse.newBuilder().setKey(11)
                .setState(MediaPlayerState.MEDIA_PLAYER_STATE_IDLE).build());
        assertTrue(playFuture.isDone());
    }

    @Test
    void stopCommandRoutesToAnnouncementPipelineAndCompletesPendingStream() throws Exception {
        ArgumentCaptor<AudioSink> sinkCaptor = ArgumentCaptor.forClass(AudioSink.class);
        when(bundleContext.registerService(eq(AudioSink.class), sinkCaptor.capture(), any(Dictionary.class)))
                .thenReturn(audioSinkRegistration);

        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One")
                .addSupportedFormats(MediaPlayerSupportedFormat.newBuilder().setFormat("MP3")
                        .setPurpose(MediaPlayerFormatPurpose.MEDIA_PLAYER_FORMAT_PURPOSE_ANNOUNCEMENT).build())
                .build());

        invokeHandleConnected(ListEntitiesDoneResponse.getDefaultInstance());

        AudioSink sink = sinkCaptor.getValue();
        assertNotNull(sink);

        URLAudioStream urlStream = Mockito.mock(URLAudioStream.class);
        when(urlStream.getURL()).thenReturn("http://example.com/sound.mp3");
        when(urlStream.getFormat()).thenReturn(AudioFormat.MP3);
        CompletableFuture<Void> playFuture = sink.processAndComplete(urlStream);
        assertFalse(playFuture.isDone());

        sink.processAndComplete(null);

        verify(frameHelper)
                .send(MediaPlayerCommandRequest.newBuilder().setKey(11).setHasCommand(true).setHasAnnouncement(true)
                        .setAnnouncement(true).setCommand(MediaPlayerCommand.MEDIA_PLAYER_COMMAND_STOP).build());

        assertTrue(playFuture.isDone());
    }

    @Test
    void registersAudioSourceWhenVoiceAssistantApiAudioIsAdvertised() throws Exception {
        when(bundleContext.registerService(eq(AudioSource.class), any(AudioSource.class), any(Dictionary.class)))
                .thenReturn(audioSourceRegistration);

        invokeHandleConnected(DeviceInfoResponse.newBuilder().setName("virtual").setMacAddress("AA").setModel("ESP32")
                .setManufacturer("Espressif").setEsphomeVersion("2026.1.0").setCompilationTime("now")
                .setVoiceAssistantFeatureFlags(VoiceAssistantFeature.API_AUDIO.getFlag()).build());

        invokeHandleConnected(ListEntitiesDoneResponse.getDefaultInstance());

        verify(bundleContext).registerService(eq(AudioSource.class), any(AudioSource.class), any(Dictionary.class));
    }

    @Test
    void unregistersAudioServicesOnDisconnect() throws Exception {
        when(bundleContext.registerService(eq(AudioSink.class), any(AudioSink.class), any(Dictionary.class)))
                .thenReturn(audioSinkRegistration);
        when(bundleContext.registerService(eq(AudioSource.class), any(AudioSource.class), any(Dictionary.class)))
                .thenReturn(audioSourceRegistration);

        invokeHandleConnected(DeviceInfoResponse.newBuilder().setName("virtual").setMacAddress("AA").setModel("ESP32")
                .setManufacturer("Espressif").setEsphomeVersion("2026.1.0").setCompilationTime("now")
                .setVoiceAssistantFeatureFlags(VoiceAssistantFeature.API_AUDIO.getFlag()).build());
        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One")
                .addSupportedFormats(MediaPlayerSupportedFormat.newBuilder().setFormat("MP3").build()).build());
        invokeHandleConnected(ListEntitiesDoneResponse.getDefaultInstance());

        invokeMethod("handleDisconnection",
                new Class<?>[] { org.openhab.core.thing.ThingStatusDetail.class, String.class },
                org.openhab.core.thing.ThingStatusDetail.COMMUNICATION_ERROR, "test");

        verify(audioSinkRegistration).unregister();
        verify(audioSourceRegistration).unregister();
    }

    @Test
    void unregistersAudioServicesOnDisposeWithoutNpeWhenDisconnected() throws Exception {
        when(bundleContext.registerService(eq(AudioSink.class), any(AudioSink.class), any(Dictionary.class)))
                .thenReturn(audioSinkRegistration);
        when(bundleContext.registerService(eq(AudioSource.class), any(AudioSource.class), any(Dictionary.class)))
                .thenReturn(audioSourceRegistration);

        invokeHandleConnected(DeviceInfoResponse.newBuilder().setName("virtual").setMacAddress("AA").setModel("ESP32")
                .setManufacturer("Espressif").setEsphomeVersion("2026.1.0").setCompilationTime("now")
                .setVoiceAssistantFeatureFlags(VoiceAssistantFeature.API_AUDIO.getFlag()).build());
        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One")
                .addSupportedFormats(MediaPlayerSupportedFormat.newBuilder().setFormat("MP3").build()).build());
        invokeHandleConnected(ListEntitiesDoneResponse.getDefaultInstance());

        // Set frameHelper to null simulating closed transport prior to dispose
        setField("frameHelper", null);

        handler.dispose();

        verify(audioSinkRegistration).unregister();
        verify(audioSourceRegistration).unregister();
    }

    @Test
    void voiceAssistantAudioSourceStreamsAndClosesWithoutDeadlock() throws Exception {
        ArgumentCaptor<AudioSource> sourceCaptor = ArgumentCaptor.forClass(AudioSource.class);
        when(bundleContext.registerService(eq(AudioSource.class), sourceCaptor.capture(), any(Dictionary.class)))
                .thenReturn(audioSourceRegistration);

        invokeHandleConnected(DeviceInfoResponse.newBuilder().setName("virtual").setMacAddress("AA").setModel("ESP32")
                .setManufacturer("Espressif").setEsphomeVersion("2026.1.0").setCompilationTime("now")
                .setVoiceAssistantFeatureFlags(VoiceAssistantFeature.API_AUDIO.getFlag()).build());
        invokeHandleConnected(ListEntitiesDoneResponse.getDefaultInstance());

        AudioSource source = sourceCaptor.getValue();
        assertNotNull(source);

        AudioStream stream = source.getInputStream(source.getSupportedFormats().iterator().next());
        assertNotNull(stream);

        // Start voice session from device
        invokeHandleConnected(VoiceAssistantRequest.newBuilder().setStart(true).build());

        // Send audio data from device
        byte[] pcmData = new byte[320];
        for (int i = 0; i < pcmData.length; i++) {
            pcmData[i] = (byte) i;
        }
        invokeHandleConnected(VoiceAssistantAudio.newBuilder().setData(ByteString.copyFrom(pcmData)).build());

        // Audio data is expected to be queued, read audio data from the queue
        byte[] readBuffer = new byte[320];
        int bytesRead = stream.read(readBuffer);
        assertEquals(320, bytesRead);
        assertEquals(Arrays.toString(readBuffer), Arrays.toString(pcmData));

        // End voice session from device
        invokeHandleConnected(VoiceAssistantAudio.newBuilder().setEnd(true).build());

        // Closing stream should unblock and not deadlock => test should run without hanging the test runner
        stream.close();
    }

    @Test
    void sendsPipelineEventsAroundVoiceSession() throws Exception {
        AudioSource source = registerVoiceAssistantSource();
        AudioStream stream = source.getInputStream(source.getSupportedFormats().iterator().next());

        invokeHandleConnected(VoiceAssistantRequest.newBuilder().setStart(true).build());

        verify(frameHelper).send(ACCEPT);
        verify(frameHelper).send(event(VoiceAssistantEvent.VOICE_ASSISTANT_RUN_START));
        verify(frameHelper).send(event(VoiceAssistantEvent.VOICE_ASSISTANT_STT_START));

        stream.close();

        verify(frameHelper).send(event(VoiceAssistantEvent.VOICE_ASSISTANT_RUN_END));
        verify(frameHelper, never()).send(event(VoiceAssistantEvent.VOICE_ASSISTANT_STT_VAD_END));
    }

    @Test
    void rejectsVoiceRequestWhenNothingIsListening() throws Exception {
        registerVoiceAssistantSource();

        invokeHandleConnected(VoiceAssistantRequest.newBuilder().setStart(true).build());

        verify(frameHelper).send(REJECT);
        verify(frameHelper, never()).send(event(VoiceAssistantEvent.VOICE_ASSISTANT_RUN_START));
    }

    @Test
    void dialogTriggerStartsListeningOnWakeWordOffThePacketThread() throws Exception {
        ESPHomeVoiceAssistantAudioSource source = (ESPHomeVoiceAssistantAudioSource) registerVoiceAssistantSource();
        AudioStream[] dialogStream = new AudioStream[1];
        Thread[] triggerThread = new Thread[1];
        source.setDialogTrigger(() -> {
            triggerThread[0] = Thread.currentThread();
            dialogStream[0] = openStream(source);
        });

        invokeHandleConnected(
                VoiceAssistantRequest.newBuilder().setStart(true).setWakeWordPhrase("Hey Jarvis").build());

        verify(frameHelper, timeout(2000)).send(ACCEPT);
        verify(frameHelper).send(event(VoiceAssistantEvent.VOICE_ASSISTANT_RUN_START));
        assertNotNull(dialogStream[0]);
        assertNotEquals(Thread.currentThread(), triggerThread[0]);
        dialogStream[0].close();
    }

    @Test
    void dialogTriggerIsNotUsedWhenDeviceAsksOpenHABToDetectTheWakeWord() throws Exception {
        ESPHomeVoiceAssistantAudioSource source = (ESPHomeVoiceAssistantAudioSource) registerVoiceAssistantSource();
        CountDownLatch triggered = new CountDownLatch(1);
        source.setDialogTrigger(triggered::countDown);

        invokeHandleConnected(VoiceAssistantRequest.newBuilder().setStart(true)
                .setFlags(VoiceAssistantRequestFlag.VOICE_ASSISTANT_REQUEST_USE_WAKE_WORD_VALUE).build());

        verify(frameHelper).send(REJECT);
        assertFalse(triggered.await(200, TimeUnit.MILLISECONDS));
    }

    @Test
    void failingDialogTriggerStillAnswersTheDevice() throws Exception {
        ESPHomeVoiceAssistantAudioSource source = (ESPHomeVoiceAssistantAudioSource) registerVoiceAssistantSource();
        source.setDialogTrigger(() -> {
            throw new IllegalStateException("STT failed");
        });

        invokeHandleConnected(VoiceAssistantRequest.newBuilder().setStart(true).build());

        verify(frameHelper, timeout(2000)).send(REJECT);
    }

    @Test
    void stopRequestWhileDialogStartsIsHandledAfterIt() throws Exception {
        ESPHomeVoiceAssistantAudioSource source = (ESPHomeVoiceAssistantAudioSource) registerVoiceAssistantSource();
        CountDownLatch release = new CountDownLatch(1);
        AudioStream[] dialogStream = new AudioStream[1];
        source.setDialogTrigger(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            dialogStream[0] = openStream(source);
        });

        invokeHandleConnected(VoiceAssistantRequest.newBuilder().setStart(true).build());
        invokeHandleConnected(VoiceAssistantRequest.newBuilder().setStart(false).build());
        release.countDown();

        verify(frameHelper, timeout(2000)).send(ACCEPT);
        // the stop closes the dialog's stream
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertEquals(-1, dialogStream[0].read()));
    }

    @Test
    void dialogTriggerIsNotUsedWhenSomethingIsAlreadyListening() throws Exception {
        ESPHomeVoiceAssistantAudioSource source = (ESPHomeVoiceAssistantAudioSource) registerVoiceAssistantSource();
        boolean[] triggered = new boolean[1];
        source.setDialogTrigger(() -> triggered[0] = true);
        AudioStream stream = openStream(source);

        invokeHandleConnected(VoiceAssistantRequest.newBuilder().setStart(true).build());

        assertFalse(triggered[0]);
        verify(frameHelper).send(ACCEPT);
        stream.close();
    }

    @Test
    void dialogTriggerKeepsDeviceSubscribedBetweenRuns() throws Exception {
        ESPHomeVoiceAssistantAudioSource source = (ESPHomeVoiceAssistantAudioSource) registerVoiceAssistantSource();

        source.setDialogTrigger(() -> {
        });
        assertTrue(source.subscribeForDialogTrigger());
        verify(frameHelper).send(SUBSCRIBE);

        AudioStream stream = openStream(source);
        invokeHandleConnected(VoiceAssistantRequest.newBuilder().setStart(true).build());
        stream.close();

        verify(frameHelper).send(event(VoiceAssistantEvent.VOICE_ASSISTANT_RUN_END));
        verify(frameHelper, times(1)).send(SUBSCRIBE);
        verify(frameHelper, never()).send(UNSUBSCRIBE);

        source.setDialogTrigger(null);
        verify(frameHelper).send(UNSUBSCRIBE);
    }

    @Test
    void doesNotLookUpDialogProviderWhenVoiceDialogIsDisabled() throws Exception {
        ESPHomeConfiguration config = new ESPHomeConfiguration();
        config.voiceDialog = false;
        setField("config", config);
        when(bundleContext.registerService(eq(AudioSink.class), any(AudioSink.class), any(Dictionary.class)))
                .thenReturn(audioSinkRegistration);
        registerVoiceAssistantSourceWithMediaPlayer();

        verify(bundleContext, never()).getServiceReference(DIALOG_PROVIDER);
    }

    @Test
    void doesNotRetryVoiceDialogOnOpenHABWithoutDialogProvider() throws Exception {
        ESPHomeConfiguration config = new ESPHomeConfiguration();
        config.voiceDialog = true;
        setField("config", config);
        when(bundleContext.registerService(eq(AudioSink.class), any(AudioSink.class), any(Dictionary.class)))
                .thenReturn(audioSinkRegistration);

        ESPHomeVoiceAssistantAudioSource source = (ESPHomeVoiceAssistantAudioSource) registerVoiceAssistantSourceWithMediaPlayer();

        verify(bundleContext).getServiceReference(DIALOG_PROVIDER);
        assertFalse(source.hasDialogTrigger());
        assertNull(getField("voiceDialogRestartFuture"));
    }

    @Test
    void doesNotRetryVoiceDialogWithIncompatibleDialogProvider() throws Exception {
        voiceDialogProvider();
        Mockito.doReturn(Runnable.class).when(dialogProviderBundle).loadClass(DIALOG_PROVIDER);

        ESPHomeVoiceAssistantAudioSource source = (ESPHomeVoiceAssistantAudioSource) registerVoiceAssistantSourceWithMediaPlayer();

        assertFalse(source.hasDialogTrigger());
        assertNull(getField("voiceDialogRestartFuture"));
    }

    @Test
    void restartsVoiceDialogAfterOpenHABStopsIt() throws Exception {
        DialogProvider provider = voiceDialogProvider();
        ArgumentCaptor<Runnable> onAbort = ArgumentCaptor.forClass(Runnable.class);
        when(provider.startDialog(any(), any(), any(), any(), onAbort.capture())).thenReturn(() -> {
        });

        ESPHomeVoiceAssistantAudioSource source = (ESPHomeVoiceAssistantAudioSource) registerVoiceAssistantSourceWithMediaPlayer();
        assertTrue(source.hasDialogTrigger());

        onAbort.getValue().run();
        assertFalse(source.hasDialogTrigger());
        assertNotNull(getField("voiceDialogRestartFuture"));

        restartVoiceDialog();

        verify(provider, times(2)).startDialog(any(), any(), any(), any(), any());
        assertTrue(source.hasDialogTrigger());
    }

    @Test
    void retriesVoiceDialogUntilOpenHABCanStartIt() throws Exception {
        DialogProvider provider = voiceDialogProvider();
        when(provider.startDialog(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("no default STT")).thenReturn(() -> {
                });

        ESPHomeVoiceAssistantAudioSource source = (ESPHomeVoiceAssistantAudioSource) registerVoiceAssistantSourceWithMediaPlayer();
        assertFalse(source.hasDialogTrigger());
        assertNotNull(getField("voiceDialogRestartFuture"));

        restartVoiceDialog();

        verify(provider, times(2)).startDialog(any(), any(), any(), any(), any());
        assertTrue(source.hasDialogTrigger());
    }

    @Test
    void ignoresTriggerOfDialogStoppedWhileItStarted() throws Exception {
        DialogProvider provider = voiceDialogProvider();
        when(provider.startDialog(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(4)).run();
            return (Runnable) () -> {
            };
        }).thenReturn(() -> {
        });

        ESPHomeVoiceAssistantAudioSource source = (ESPHomeVoiceAssistantAudioSource) registerVoiceAssistantSourceWithMediaPlayer();
        assertFalse(source.hasDialogTrigger());
        assertNotNull(getField("voiceDialogRestartFuture"));

        restartVoiceDialog();

        verify(provider, times(2)).startDialog(any(), any(), any(), any(), any());
        assertTrue(source.hasDialogTrigger());
    }

    @Test
    void retriesSubscriptionWithoutStartingAnotherDialog() throws Exception {
        DialogProvider provider = voiceDialogProvider();
        when(provider.startDialog(any(), any(), any(), any(), any())).thenReturn(() -> {
        });
        Mockito.doThrow(new ProtocolAPIError("socket closed")).doNothing().when(frameHelper).send(SUBSCRIBE);

        ESPHomeVoiceAssistantAudioSource source = (ESPHomeVoiceAssistantAudioSource) registerVoiceAssistantSourceWithMediaPlayer();
        assertTrue(source.hasDialogTrigger());
        assertNotNull(getField("voiceDialogRestartFuture"));

        restartVoiceDialog();

        verify(frameHelper, times(2)).send(SUBSCRIBE);
        verify(provider, times(1)).startDialog(any(), any(), any(), any(), any());
        assertNull(getField("voiceDialogRestartFuture"));
    }

    @Test
    void doesNotRestartVoiceDialogAfterDispose() throws Exception {
        DialogProvider provider = voiceDialogProvider();
        ArgumentCaptor<Runnable> onAbort = ArgumentCaptor.forClass(Runnable.class);
        when(provider.startDialog(any(), any(), any(), any(), onAbort.capture())).thenReturn(() -> {
        });
        registerVoiceAssistantSourceWithMediaPlayer();

        onAbort.getValue().run();
        handler.dispose();
        restartVoiceDialog();

        verify(provider, times(1)).startDialog(any(), any(), any(), any(), any());
    }

    @Test
    void doesNotRestartVoiceDialogAfterDisconnect() throws Exception {
        DialogProvider provider = voiceDialogProvider();
        ArgumentCaptor<Runnable> onAbort = ArgumentCaptor.forClass(Runnable.class);
        when(provider.startDialog(any(), any(), any(), any(), onAbort.capture())).thenReturn(() -> {
        });
        registerVoiceAssistantSourceWithMediaPlayer();
        // openHAB stops the dialog when the source is unregistered
        Mockito.doAnswer(invocation -> {
            onAbort.getValue().run();
            return null;
        }).when(audioSourceRegistration).unregister();

        invokeMethod("handleDisconnection",
                new Class<?>[] { org.openhab.core.thing.ThingStatusDetail.class, String.class },
                org.openhab.core.thing.ThingStatusDetail.COMMUNICATION_ERROR, "test");

        assertNull(getField("voiceDialogRestartFuture"));
        restartVoiceDialog();
        verify(provider, times(1)).startDialog(any(), any(), any(), any(), any());
    }

    @Test
    void repliesOnTheMediaPlayerThatTakesAnnouncements() throws Exception {
        DialogProvider provider = voiceDialogProvider();
        ArgumentCaptor<AudioSink> sink = ArgumentCaptor.forClass(AudioSink.class);
        when(provider.startDialog(sink.capture(), any(), any(), any(), any())).thenReturn(() -> {
        });
        when(bundleContext.registerService(eq(AudioSource.class), any(AudioSource.class), any(Dictionary.class)))
                .thenReturn(audioSourceRegistration);

        invokeHandleConnected(voiceAssistantDeviceInfo());
        invokeHandleConnected(
                ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("a_media").setName("Media")
                        .addSupportedFormats(MediaPlayerSupportedFormat.newBuilder().setFormat("FLAC")
                                .setPurpose(MediaPlayerFormatPurpose.MEDIA_PLAYER_FORMAT_PURPOSE_DEFAULT).build())
                        .build());
        invokeHandleConnected(
                ListEntitiesMediaPlayerResponse.newBuilder().setKey(12).setObjectId("z_announce").setName("Announce")
                        .addSupportedFormats(MediaPlayerSupportedFormat.newBuilder().setFormat("FLAC")
                                .setPurpose(MediaPlayerFormatPurpose.MEDIA_PLAYER_FORMAT_PURPOSE_ANNOUNCEMENT).build())
                        .build());
        invokeHandleConnected(ListEntitiesDoneResponse.getDefaultInstance());

        assertTrue(sink.getValue().getId().endsWith(":z_announce"));
    }

    private DialogProvider voiceDialogProvider() throws Exception {
        ESPHomeConfiguration config = new ESPHomeConfiguration();
        config.voiceDialog = true;
        setField("config", config);
        when(bundleContext.registerService(eq(AudioSink.class), any(AudioSink.class), any(Dictionary.class)))
                .thenReturn(audioSinkRegistration);
        DialogProvider provider = Mockito.mock(DialogProvider.class);
        ServiceReference<?> reference = Mockito.mock(ServiceReference.class);
        Mockito.doReturn(reference).when(bundleContext).getServiceReference(DIALOG_PROVIDER);
        Mockito.doReturn(dialogProviderBundle).when(reference).getBundle();
        Mockito.doReturn(DialogProvider.class).when(dialogProviderBundle).loadClass(DIALOG_PROVIDER);
        Mockito.doReturn(provider).when(bundleContext).getService(reference);
        return provider;
    }

    private void restartVoiceDialog() throws Exception {
        invokeMethod("restartVoiceDialog", new Class<?>[0]);
    }

    private static AudioStream openStream(AudioSource source) {
        try {
            return source.getInputStream(source.getSupportedFormats().iterator().next());
        } catch (AudioException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DeviceInfoResponse voiceAssistantDeviceInfo() {
        return DeviceInfoResponse.newBuilder().setName("virtual").setMacAddress("AA").setModel("ESP32")
                .setManufacturer("Espressif").setEsphomeVersion("2026.1.0").setCompilationTime("now")
                .setVoiceAssistantFeatureFlags(VoiceAssistantFeature.API_AUDIO.getFlag()).build();
    }

    private AudioSource registerVoiceAssistantSource() throws Exception {
        ArgumentCaptor<AudioSource> sourceCaptor = ArgumentCaptor.forClass(AudioSource.class);
        when(bundleContext.registerService(eq(AudioSource.class), sourceCaptor.capture(), any(Dictionary.class)))
                .thenReturn(audioSourceRegistration);
        invokeHandleConnected(voiceAssistantDeviceInfo());
        invokeHandleConnected(ListEntitiesDoneResponse.getDefaultInstance());
        return sourceCaptor.getValue();
    }

    private AudioSource registerVoiceAssistantSourceWithMediaPlayer() throws Exception {
        ArgumentCaptor<AudioSource> sourceCaptor = ArgumentCaptor.forClass(AudioSource.class);
        when(bundleContext.registerService(eq(AudioSource.class), sourceCaptor.capture(), any(Dictionary.class)))
                .thenReturn(audioSourceRegistration);
        invokeHandleConnected(voiceAssistantDeviceInfo());
        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One")
                .addSupportedFormats(MediaPlayerSupportedFormat.newBuilder().setFormat("FLAC").build()).build());
        invokeHandleConnected(ListEntitiesDoneResponse.getDefaultInstance());
        return sourceCaptor.getValue();
    }

    private static VoiceAssistantEventResponse event(VoiceAssistantEvent type) {
        return VoiceAssistantEventResponse.newBuilder().setEventType(type).build();
    }

    /**
     * Stand-in for openHAB 5.1's AudioDialogProvider.
     */
    public interface DialogProvider {
        @Nullable
        Runnable startDialog(AudioSink audioSink, AudioSource audioSource, @Nullable String locationItem,
                @Nullable String listeningItem, @Nullable Runnable onAbort);
    }

    @Test
    void registersPlayerChannelForMediaPlayerEntity() throws Exception {
        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One").setSupportsPause(true)
                .setFeatureFlags(flags(MediaPlayerFeature.PLAY, MediaPlayerFeature.PAUSE, MediaPlayerFeature.STOP,
                        MediaPlayerFeature.VOLUME_SET, MediaPlayerFeature.VOLUME_MUTE, MediaPlayerFeature.REPEAT_SET,
                        MediaPlayerFeature.TURN_ON, MediaPlayerFeature.TURN_OFF, MediaPlayerFeature.CLEAR_PLAYLIST))
                .build());

        Channel channel = handler.getDynamicChannels().stream().filter(c -> "speaker_one".equals(c.getUID().getId()))
                .findFirst().orElse(null);

        assertNotNull(channel);
        assertEquals("speaker_one", channel.getUID().getId());
        assertEquals(CoreItemFactory.PLAYER, channel.getAcceptedItemType());
        assertTrue(handler.getDynamicChannels().stream()
                .anyMatch(c -> "speaker_one_mediaplayer#volume".equals(c.getUID().getId())));
        assertTrue(handler.getDynamicChannels().stream()
                .anyMatch(c -> "speaker_one_mediaplayer#power".equals(c.getUID().getId())));
        assertTrue(handler.getDynamicChannels().stream()
                .anyMatch(c -> "speaker_one_mediaplayer#clear_playlist".equals(c.getUID().getId())));
        assertTrue(handler.getDynamicChannels().stream()
                .anyMatch(c -> "speaker_one_mediaplayer#mute".equals(c.getUID().getId())));
        assertTrue(handler.getDynamicChannels().stream()
                .anyMatch(c -> "speaker_one_mediaplayer#repeat".equals(c.getUID().getId())));
        assertTrue(handler.getDynamicChannels().stream()
                .anyMatch(c -> "speaker_one_mediaplayer#state".equals(c.getUID().getId())));
    }

    @Test
    void mapsPlayerCommandToMediaPlayerCommandRequest() throws Exception {
        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One").setSupportsPause(true)
                .setFeatureFlags(flags(MediaPlayerFeature.PLAY, MediaPlayerFeature.PAUSE)).build());
        thing().setChannels(handler.getDynamicChannels());

        handler.handleCommand(new ChannelUID(thing().getUID(), "speaker_one"), PlayPauseType.PLAY);

        verify(frameHelper).send(MediaPlayerCommandRequest.newBuilder().setKey(11).setHasCommand(true)
                .setCommand(MediaPlayerCommand.MEDIA_PLAYER_COMMAND_PLAY).build());
    }

    @Test
    void updatesPlayerChannelStateFromMediaPlayerState() throws Exception {
        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One").setSupportsPause(true)
                .setFeatureFlags(flags(MediaPlayerFeature.PLAY, MediaPlayerFeature.PAUSE)).build());
        thing().setChannels(handler.getDynamicChannels());

        invokeHandleConnected(MediaPlayerStateResponse.newBuilder().setKey(11)
                .setState(MediaPlayerState.MEDIA_PLAYER_STATE_PLAYING).build());

        verify(callback).stateUpdated(new ChannelUID(thing().getUID(), "speaker_one"), PlayPauseType.PLAY);
    }

    @Test
    void updatesRawPlayerChannelStateFromMediaPlayerState() throws Exception {
        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One").build());
        thing().setChannels(handler.getDynamicChannels());

        invokeHandleConnected(MediaPlayerStateResponse.newBuilder().setKey(11)
                .setState(MediaPlayerState.MEDIA_PLAYER_STATE_IDLE).build());

        verify(callback).stateUpdated(stateChannelUID(), new org.openhab.core.library.types.StringType("IDLE"));
    }

    @Test
    void mapsVolumeCommandToMediaPlayerVolumeRequest() throws Exception {
        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One").setFeatureFlags(flags(MediaPlayerFeature.VOLUME_SET)).build());
        thing().setChannels(handler.getDynamicChannels());

        handler.handleCommand(volumeChannelUID(), new PercentType(75));

        verify(frameHelper)
                .send(MediaPlayerCommandRequest.newBuilder().setKey(11).setHasVolume(true).setVolume(0.75f).build());
    }

    @Test
    void mapsVolumeOnOffCommandToMuteRequestWhenMuteIsSupported() throws Exception {
        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One")
                .setFeatureFlags(flags(MediaPlayerFeature.VOLUME_SET, MediaPlayerFeature.VOLUME_MUTE)).build());
        thing().setChannels(handler.getDynamicChannels());

        handler.handleCommand(volumeChannelUID(), OnOffType.ON);

        verify(frameHelper).send(MediaPlayerCommandRequest.newBuilder().setKey(11).setHasCommand(true)
                .setCommand(MediaPlayerCommand.MEDIA_PLAYER_COMMAND_MUTE).build());

        handler.handleCommand(volumeChannelUID(), OnOffType.OFF);

        verify(frameHelper).send(MediaPlayerCommandRequest.newBuilder().setKey(11).setHasCommand(true)
                .setCommand(MediaPlayerCommand.MEDIA_PLAYER_COMMAND_UNMUTE).build());
    }

    @Test
    void mapsRepeatCommandToMediaPlayerRepeatRequest() throws Exception {
        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One").setFeatureFlags(flags(MediaPlayerFeature.REPEAT_SET)).build());
        thing().setChannels(handler.getDynamicChannels());

        handler.handleCommand(repeatChannelUID(), new org.openhab.core.library.types.StringType("one"));

        verify(frameHelper).send(MediaPlayerCommandRequest.newBuilder().setKey(11).setHasCommand(true)
                .setCommand(MediaPlayerCommand.MEDIA_PLAYER_COMMAND_REPEAT_ONE).build());
        verify(callback).stateUpdated(repeatChannelUID(), new org.openhab.core.library.types.StringType("one"));
    }

    @Test
    void mapsMuteCommandToMediaPlayerMuteRequest() throws Exception {
        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One").setFeatureFlags(flags(MediaPlayerFeature.VOLUME_MUTE)).build());
        thing().setChannels(handler.getDynamicChannels());

        handler.handleCommand(muteChannelUID(), OnOffType.ON);

        verify(frameHelper).send(MediaPlayerCommandRequest.newBuilder().setKey(11).setHasCommand(true)
                .setCommand(MediaPlayerCommand.MEDIA_PLAYER_COMMAND_MUTE).build());
    }

    @Test
    void mapsPowerCommandToMediaPlayerPowerRequest() throws Exception {
        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One").setFeatureFlags(flags(MediaPlayerFeature.TURN_ON, MediaPlayerFeature.TURN_OFF))
                .build());
        thing().setChannels(handler.getDynamicChannels());

        handler.handleCommand(powerChannelUID(), OnOffType.OFF);

        verify(frameHelper).send(MediaPlayerCommandRequest.newBuilder().setKey(11).setHasCommand(true)
                .setCommand(MediaPlayerCommand.MEDIA_PLAYER_COMMAND_TURN_OFF).build());
    }

    @Test
    void registersPowerChannelWhenOnlyOnePowerCommandIsSupported() throws Exception {
        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One").setFeatureFlags(flags(MediaPlayerFeature.TURN_ON)).build());
        thing().setChannels(handler.getDynamicChannels());

        assertEquals(powerChannelUID(),
                handler.getDynamicChannels().stream()
                        .filter(c -> "power".equals(c.getConfiguration().get("entity_field"))).map(Channel::getUID)
                        .findFirst().orElseThrow());

        handler.handleCommand(powerChannelUID(), OnOffType.OFF);

        verify(frameHelper, never()).send(MediaPlayerCommandRequest.newBuilder().setKey(11).setHasCommand(true)
                .setCommand(MediaPlayerCommand.MEDIA_PLAYER_COMMAND_TURN_OFF).build());
    }

    @Test
    void mapsClearPlaylistCommandToMediaPlayerClearPlaylistRequest() throws Exception {
        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One").setFeatureFlags(flags(MediaPlayerFeature.CLEAR_PLAYLIST)).build());
        thing().setChannels(handler.getDynamicChannels());

        handler.handleCommand(clearPlaylistChannelUID(), new org.openhab.core.library.types.StringType("PRESS"));

        verify(frameHelper).send(MediaPlayerCommandRequest.newBuilder().setKey(11).setHasCommand(true)
                .setCommand(MediaPlayerCommand.MEDIA_PLAYER_COMMAND_CLEAR_PLAYLIST).build());
    }

    @Test
    void updatesVolumeAndMuteChannelsFromMediaPlayerState() throws Exception {
        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One")
                .setFeatureFlags(flags(MediaPlayerFeature.VOLUME_SET, MediaPlayerFeature.VOLUME_MUTE,
                        MediaPlayerFeature.REPEAT_SET, MediaPlayerFeature.TURN_ON, MediaPlayerFeature.TURN_OFF))
                .build());
        thing().setChannels(handler.getDynamicChannels());

        invokeHandleConnected(MediaPlayerStateResponse.newBuilder().setKey(11).setVolume(0.42f).setMuted(true)
                .setState(MediaPlayerState.MEDIA_PLAYER_STATE_OFF).build());

        verify(callback).stateUpdated(volumeChannelUID(), new PercentType(42));
        verify(callback).stateUpdated(powerChannelUID(), OnOffType.OFF);
        verify(callback).stateUpdated(muteChannelUID(), OnOffType.ON);
        verify(callback).stateUpdated(repeatChannelUID(), new org.openhab.core.library.types.StringType("off"));
    }

    @Test
    void onlyRegistersSupportedMediaPlayerChannels() throws Exception {
        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One").setFeatureFlags(flags(MediaPlayerFeature.STOP)).build());

        assertEquals(1, handler.getDynamicChannels().stream().filter(c -> c.getUID().getId().startsWith("speaker_one"))
                .filter(c -> !c.getUID().getId().equals("speaker_one")).count());
        assertTrue(handler.getDynamicChannels().stream()
                .anyMatch(c -> "state".equals(c.getConfiguration().get("entity_field"))));
    }

    @Test
    void resolvesAudioSinkBaseUrlFromBindingConfiguration() throws Exception {
        setField("bindingPropertyAudioBaseUrl", "https://custom-openhab.example.com/");

        ArgumentCaptor<AudioSink> sinkCaptor = ArgumentCaptor.forClass(AudioSink.class);
        when(bundleContext.registerService(eq(AudioSink.class), sinkCaptor.capture(), any(Dictionary.class)))
                .thenReturn(audioSinkRegistration);

        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One")
                .addSupportedFormats(MediaPlayerSupportedFormat.newBuilder().setFormat("MP3").build()).build());
        invokeHandleConnected(ListEntitiesDoneResponse.getDefaultInstance());

        AudioSink sink = sinkCaptor.getValue();
        assertNotNull(sink);
        assertInstanceOf(ESPHomeAudioSink.class, sink);
        assertEquals("https://custom-openhab.example.com", ((ESPHomeAudioSink) sink).getBaseUrl());

        AudioStream audioStream = Mockito.mock(AudioStream.class);
        when(audioStream.getFormat()).thenReturn(AudioFormat.MP3);

        StreamServed servedStream = new StreamServed("/audio/stream123.mp3", audioStream,
                new java.util.concurrent.atomic.AtomicInteger(1), new java.util.concurrent.atomic.AtomicLong(0), false,
                new CompletableFuture<>());
        when(audioHTTPServer.serve(any(), anyInt(), anyBoolean())).thenReturn(servedStream);

        sink.processAndComplete(audioStream);

        verify(frameHelper).send(MediaPlayerCommandRequest.newBuilder().setKey(11).setHasMediaUrl(true)
                .setMediaUrl("https://custom-openhab.example.com/audio/stream123.mp3").setHasAnnouncement(true)
                .setAnnouncement(false).build());
    }

    @Test
    void fallsBackToPrimaryIpv4WhenNoBindingAudioBaseUrlConfigured() throws Exception {
        setField("bindingPropertyAudioBaseUrl", null);

        ArgumentCaptor<AudioSink> sinkCaptor = ArgumentCaptor.forClass(AudioSink.class);
        when(bundleContext.registerService(eq(AudioSink.class), sinkCaptor.capture(), any(Dictionary.class)))
                .thenReturn(audioSinkRegistration);

        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One")
                .addSupportedFormats(MediaPlayerSupportedFormat.newBuilder().setFormat("MP3").build()).build());
        invokeHandleConnected(ListEntitiesDoneResponse.getDefaultInstance());

        AudioSink sink = sinkCaptor.getValue();
        assertNotNull(sink);
        assertEquals("http://127.0.0.1:" + org.openhab.core.net.HttpServiceUtil.getHttpServicePort(bundleContext),
                ((ESPHomeAudioSink) sink).getBaseUrl());
    }

    @Test
    void registersAudioSinkEvenIfPrimaryIpv4IsNullWhenBindingAudioBaseUrlIsConfigured() throws Exception {
        when(networkAddressService.getPrimaryIpv4HostAddress()).thenReturn(null);
        when(bundleContext.registerService(eq(AudioSink.class), any(AudioSink.class), any(Dictionary.class)))
                .thenReturn(audioSinkRegistration);

        setField("bindingPropertyAudioBaseUrl", "https://proxy.example.com");

        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One")
                .addSupportedFormats(MediaPlayerSupportedFormat.newBuilder().setFormat("MP3").build()).build());
        invokeHandleConnected(ListEntitiesDoneResponse.getDefaultInstance());

        verify(bundleContext).registerService(eq(AudioSink.class), any(AudioSink.class), any(Dictionary.class));
    }

    @Test
    void updatesBaseUrlOnExistingAudioSinkWhenRefreshed() throws Exception {
        setField("bindingPropertyAudioBaseUrl", "https://initial-url.com");

        ArgumentCaptor<AudioSink> sinkCaptor = ArgumentCaptor.forClass(AudioSink.class);
        when(bundleContext.registerService(eq(AudioSink.class), sinkCaptor.capture(), any(Dictionary.class)))
                .thenReturn(audioSinkRegistration);

        invokeHandleConnected(ListEntitiesMediaPlayerResponse.newBuilder().setKey(11).setObjectId("speaker_one")
                .setName("Speaker One")
                .addSupportedFormats(MediaPlayerSupportedFormat.newBuilder().setFormat("MP3").build()).build());
        invokeHandleConnected(ListEntitiesDoneResponse.getDefaultInstance());

        AudioSink sink = sinkCaptor.getValue();
        assertEquals("https://initial-url.com", ((ESPHomeAudioSink) sink).getBaseUrl());

        // Update binding property and refresh
        setField("bindingPropertyAudioBaseUrl", "https://updated-url.com///");
        invokeMethod("refreshAudioSink", new Class<?>[0]);

        assertEquals("https://updated-url.com", ((ESPHomeAudioSink) sink).getBaseUrl());
    }

    @Test
    void normalizesBaseUrlCorrectly() {
        assertEquals("http://example.com:8080", ESPHomeAudioSink.normalizeBaseUrl("http://example.com:8080"));
        assertEquals("http://example.com:8080", ESPHomeAudioSink.normalizeBaseUrl("http://example.com:8080/"));
        assertEquals("http://example.com:8080", ESPHomeAudioSink.normalizeBaseUrl("  http://example.com:8080///  "));
        assertEquals("https://proxy.com/openhab", ESPHomeAudioSink.normalizeBaseUrl("https://proxy.com/openhab/"));
    }

    private void invokeHandleConnected(com.google.protobuf.GeneratedMessage message) throws Exception {
        invokeMethod("handleConnected", new Class<?>[] { com.google.protobuf.GeneratedMessage.class }, message);
    }

    private Object invokeMethod(String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = ESPHomeHandler.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(handler, args);
    }

    private ThingImpl thing() throws Exception {
        Field field = org.openhab.core.thing.binding.BaseThingHandler.class.getDeclaredField("thing");
        field.setAccessible(true);
        return (ThingImpl) field.get(handler);
    }

    private ChannelUID stateChannelUID() {
        return handler.getDynamicChannels().stream().filter(c -> CoreItemFactory.STRING.equals(c.getAcceptedItemType()))
                .filter(c -> "state".equals(c.getConfiguration().get("entity_field"))).map(Channel::getUID).findFirst()
                .orElseThrow();
    }

    private ChannelUID volumeChannelUID() {
        return handler.getDynamicChannels().stream().filter(c -> CoreItemFactory.DIMMER.equals(c.getAcceptedItemType()))
                .map(Channel::getUID).findFirst().orElseThrow();
    }

    private ChannelUID muteChannelUID() {
        return handler.getDynamicChannels().stream().filter(c -> CoreItemFactory.SWITCH.equals(c.getAcceptedItemType()))
                .filter(c -> "mute".equals(c.getConfiguration().get("entity_field"))).map(Channel::getUID).findFirst()
                .orElseThrow();
    }

    private ChannelUID powerChannelUID() {
        return handler.getDynamicChannels().stream().filter(c -> CoreItemFactory.SWITCH.equals(c.getAcceptedItemType()))
                .filter(c -> "power".equals(c.getConfiguration().get("entity_field"))).map(Channel::getUID).findFirst()
                .orElseThrow();
    }

    private ChannelUID clearPlaylistChannelUID() {
        return handler.getDynamicChannels().stream().filter(c -> CoreItemFactory.STRING.equals(c.getAcceptedItemType()))
                .filter(c -> "clear_playlist".equals(c.getConfiguration().get("entity_field"))).map(Channel::getUID)
                .findFirst().orElseThrow();
    }

    private ChannelUID repeatChannelUID() {
        return handler.getDynamicChannels().stream().filter(c -> CoreItemFactory.STRING.equals(c.getAcceptedItemType()))
                .filter(c -> "repeat".equals(c.getConfiguration().get("entity_field"))).map(Channel::getUID).findFirst()
                .orElseThrow();
    }

    private void setField(String name, Object value) throws Exception {
        Field field = ESPHomeHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(handler, value);
    }

    private @Nullable Object getField(String name) throws Exception {
        Field field = ESPHomeHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(handler);
    }

    private Class<?> getFieldType(String name) throws Exception {
        Field field = ESPHomeHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getType();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Enum<?> enumValue(Class<?> enumType, String name) {
        return Enum.valueOf((Class<Enum>) enumType, name);
    }

    private static int flags(MediaPlayerFeature... features) {
        int flags = 0;
        for (MediaPlayerFeature feature : features) {
            flags |= feature.getFlag();
        }
        return flags;
    }
}
