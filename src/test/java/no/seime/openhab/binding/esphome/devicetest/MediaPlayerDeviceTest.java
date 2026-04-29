package no.seime.openhab.binding.esphome.devicetest;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.openhab.core.audio.AudioSink;
import org.openhab.core.library.CoreItemFactory;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.PlayPauseType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.osgi.framework.ServiceRegistration;

public class MediaPlayerDeviceTest extends AbstractESPHomeDeviceTest {

    @Override
    protected File getEspDeviceConfigurationYamlFileName() {
        return new File("src/test/resources/device_configurations/media_player.yaml");
    }

    @Test
    public void testMediaPlayerChannelsAndCommands() {
        when(bundleContext.registerService(eq(AudioSink.class), any(AudioSink.class), any()))
                .thenReturn(mock(ServiceRegistration.class));
        thingHandler.initialize();
        await().until(() -> thingHandler.isInterrogated());

        assertEquals(7, thingHandler.getDynamicChannels().size());

        Set<String> channelIds = thingHandler.getDynamicChannels().stream().map(channel -> channel.getUID().getId())
                .collect(Collectors.toSet());
        ChannelUID playerChannel = findChannelByAcceptedItemType(CoreItemFactory.PLAYER).getUID();
        ChannelUID stateChannel = findChannelByField("state").getUID();
        ChannelUID volumeChannel = findChannelByField("volume").getUID();
        ChannelUID powerChannel = findChannelByField("power").getUID();
        ChannelUID clearPlaylistChannel = findChannelByField("clear_playlist").getUID();
        ChannelUID muteChannel = findChannelByField("mute").getUID();
        ChannelUID repeatChannel = findChannelByField("repeat").getUID();

        assertTrue(channelIds.contains(playerChannel.getId()));
        assertTrue(channelIds.contains(stateChannel.getId()));
        assertTrue(channelIds.contains(volumeChannel.getId()));
        assertTrue(channelIds.contains(powerChannel.getId()));
        assertTrue(channelIds.contains(clearPlaylistChannel.getId()));
        assertTrue(channelIds.contains(muteChannel.getId()));
        assertTrue(channelIds.contains(repeatChannel.getId()));

        verify(thingHandlerCallback, timeout(2000)).stateUpdated(eq(stateChannel), eq(StringType.valueOf("IDLE")));
        verify(thingHandlerCallback, timeout(2000)).stateUpdated(eq(volumeChannel), eq(new PercentType(25)));
        verify(thingHandlerCallback, timeout(2000)).stateUpdated(eq(powerChannel), eq(OnOffType.ON));
        verify(thingHandlerCallback, timeout(2000)).stateUpdated(eq(muteChannel), eq(OnOffType.OFF));
        verify(thingHandlerCallback, timeout(2000)).stateUpdated(eq(repeatChannel), eq(StringType.valueOf("off")));

        reset(thingHandlerCallback);
        thingHandler.handleCommand(playerChannel, PlayPauseType.PLAY);
        verify(thingHandlerCallback, timeout(3000)).stateUpdated(eq(playerChannel), eq(PlayPauseType.PLAY));
        verify(thingHandlerCallback, timeout(3000)).stateUpdated(eq(stateChannel), eq(StringType.valueOf("PLAYING")));

        reset(thingHandlerCallback);
        thingHandler.handleCommand(playerChannel, PlayPauseType.PAUSE);
        verify(thingHandlerCallback, timeout(3000)).stateUpdated(eq(playerChannel), eq(PlayPauseType.PAUSE));
        verify(thingHandlerCallback, timeout(3000)).stateUpdated(eq(stateChannel), eq(StringType.valueOf("PAUSED")));

        reset(thingHandlerCallback);
        thingHandler.handleCommand(volumeChannel, new PercentType(65));
        verify(thingHandlerCallback, timeout(3000)).stateUpdated(eq(volumeChannel), eq(new PercentType(65)));

        reset(thingHandlerCallback);
        thingHandler.handleCommand(muteChannel, OnOffType.ON);
        verify(thingHandlerCallback, timeout(3000)).stateUpdated(eq(muteChannel), eq(OnOffType.ON));

        reset(thingHandlerCallback);
        thingHandler.handleCommand(powerChannel, OnOffType.OFF);
        verify(thingHandlerCallback, timeout(3000)).stateUpdated(eq(powerChannel), eq(OnOffType.OFF));
        verify(thingHandlerCallback, timeout(3000)).stateUpdated(eq(stateChannel), eq(StringType.valueOf("OFF")));

        reset(thingHandlerCallback);
        thingHandler.handleCommand(repeatChannel, StringType.valueOf("one"));
        verify(thingHandlerCallback, timeout(3000)).stateUpdated(eq(repeatChannel), eq(StringType.valueOf("one")));

        reset(thingHandlerCallback);
        thingHandler.handleCommand(clearPlaylistChannel, StringType.valueOf("PRESS"));
        verify(thingHandlerCallback, timeout(3000)).stateUpdated(eq(stateChannel), eq(StringType.valueOf("IDLE")));
        verify(thingHandlerCallback, timeout(3000)).stateUpdated(eq(playerChannel), eq(PlayPauseType.PAUSE));
    }

    private Channel findChannelByAcceptedItemType(String acceptedItemType) {
        return thingHandler.getDynamicChannels().stream()
                .filter(channel -> acceptedItemType.equals(channel.getAcceptedItemType())).findFirst().orElseThrow();
    }

    private Channel findChannelByField(String entityField) {
        Optional<Channel> channel = thingHandler.getDynamicChannels().stream()
                .filter(c -> entityField.equals(c.getConfiguration().get("entity_field"))).findFirst();
        return channel.orElseThrow();
    }
}
