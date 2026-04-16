package no.seime.openhab.binding.esphome.internal.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.library.CoreItemFactory;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.PlayPauseType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.internal.ThingImpl;
import org.osgi.framework.BundleContext;

import com.jano7.executor.KeySequentialExecutor;

import io.esphome.api.ListEntitiesMediaPlayerResponse;
import io.esphome.api.MediaPlayerCommand;
import io.esphome.api.MediaPlayerCommandRequest;
import io.esphome.api.MediaPlayerState;
import io.esphome.api.MediaPlayerStateResponse;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.comm.ConnectionSelector;
import no.seime.openhab.binding.esphome.internal.comm.EncryptedFrameHelper;
import no.seime.openhab.binding.esphome.internal.message.MediaPlayerMessageHandler.MediaPlayerFeature;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.ESPHomeEventSubscriber;

@ExtendWith(MockitoExtension.class)
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
    private EncryptedFrameHelper frameHelper;
    @Mock
    private ThingHandlerCallback callback;

    private ESPHomeHandler handler;
    private MonitoredScheduledThreadPoolExecutor executor;
    private ExecutorService packetProcessorExecutor;

    @BeforeEach
    void setUp() throws Exception {
        executor = new MonitoredScheduledThreadPoolExecutor(1, Executors.defaultThreadFactory(), 1000);
        packetProcessorExecutor = Executors.newSingleThreadExecutor();

        handler = new ESPHomeHandler(new ThingImpl(BindingConstants.THING_TYPE_DEVICE, "device"),
                new ConnectionSelector(), channelTypeProvider, stateDescriptionProvider, eventSubscriber, executor,
                new KeySequentialExecutor(packetProcessorExecutor), eventPublisher, null, bundleContext);
        handler.setCallback(callback);
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
