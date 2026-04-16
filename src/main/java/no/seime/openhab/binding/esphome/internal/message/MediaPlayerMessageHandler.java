package no.seime.openhab.binding.esphome.internal.message;

import static org.openhab.core.library.CoreItemFactory.DIMMER;
import static org.openhab.core.library.CoreItemFactory.PLAYER;
import static org.openhab.core.library.CoreItemFactory.STRING;
import static org.openhab.core.library.CoreItemFactory.SWITCH;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.IncreaseDecreaseType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.PlayPauseType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.openhab.core.types.StateDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.ListEntitiesMediaPlayerResponse;
import io.esphome.api.MediaPlayerCommand;
import io.esphome.api.MediaPlayerCommandRequest;
import io.esphome.api.MediaPlayerState;
import io.esphome.api.MediaPlayerStateResponse;
import no.seime.openhab.binding.esphome.internal.EntityTypes;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class MediaPlayerMessageHandler
        extends AbstractMessageHandler<ListEntitiesMediaPlayerResponse, MediaPlayerStateResponse> {

    public static final String CHANNEL_STATE = "state";
    public static final String CHANNEL_PLAYER = "player";
    public static final String CHANNEL_POWER = "power";
    public static final String CHANNEL_CLEAR_PLAYLIST = "clear_playlist";
    public static final String CHANNEL_VOLUME = "volume";
    public static final String CHANNEL_MUTE = "mute";
    public static final String CHANNEL_REPEAT = "repeat";

    public enum MediaPlayerFeature {
        PAUSE(1 << 0),
        VOLUME_SET(1 << 2),
        VOLUME_MUTE(1 << 3),
        TURN_ON(1 << 7),
        TURN_OFF(1 << 8),
        VOLUME_STEP(1 << 10),
        STOP(1 << 12),
        CLEAR_PLAYLIST(1 << 13),
        PLAY(1 << 14),
        REPEAT_SET(1 << 18);

        public final int flag;

        MediaPlayerFeature(int flag) {
            this.flag = flag;
        }

        public int getFlag() {
            return flag;
        }
    }

    private static final String REPEAT_ONE = "one";
    private static final String REPEAT_OFF = "off";
    private static final String PRESS = "PRESS";
    private static final List<String> REPEAT_OPTIONS = List.of(REPEAT_ONE, REPEAT_OFF);
    private static final List<String> PRESS_OPTIONS = List.of(PRESS);

    private final Logger logger = LoggerFactory.getLogger(MediaPlayerMessageHandler.class);
    private final Map<Integer, Integer> mediaPlayerFeatureFlags = new ConcurrentHashMap<>();
    private final Map<Integer, String> repeatStates = new ConcurrentHashMap<>();

    public MediaPlayerMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        String field = (String) channel.getConfiguration().get("entity_field");
        if (CHANNEL_VOLUME.equals(field)) {
            handleVolumeCommand(channel, command, key);
            return;
        } else if (CHANNEL_POWER.equals(field)) {
            handlePowerCommand(channel, command, key);
            return;
        } else if (CHANNEL_CLEAR_PLAYLIST.equals(field)) {
            handleClearPlaylistCommand(channel, command, key);
            return;
        } else if (CHANNEL_MUTE.equals(field)) {
            handleMuteCommand(channel, command, key);
            return;
        } else if (CHANNEL_REPEAT.equals(field)) {
            handleRepeatCommand(channel, command, key);
            return;
        }

        MediaPlayerCommand esphomeCommand;

        if (command == PlayPauseType.PLAY) {
            esphomeCommand = MediaPlayerCommand.MEDIA_PLAYER_COMMAND_PLAY;
        } else if (command == PlayPauseType.PAUSE) {
            esphomeCommand = MediaPlayerCommand.MEDIA_PLAYER_COMMAND_PAUSE;
        } else {
            logger.debug("[{}] Ignoring unsupported Player command '{}' for ESPHome media player channel {}",
                    handler.getLogPrefix(), command, channel.getUID());
            return;
        }

        handler.sendMessage(MediaPlayerCommandRequest.newBuilder().setKey(key).setHasCommand(true)
                .setCommand(esphomeCommand).build());
    }

    private void handleVolumeCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        MediaPlayerCommandRequest.Builder builder = MediaPlayerCommandRequest.newBuilder().setKey(key);

        if (command instanceof PercentType percentCommand) {
            builder.setHasVolume(true).setVolume(percentCommand.floatValue() / 100f);
        } else if (command instanceof DecimalType decimalCommand) {
            builder.setHasVolume(true).setVolume(decimalCommand.floatValue() / 100f);
        } else if (command == IncreaseDecreaseType.INCREASE) {
            builder.setHasCommand(true).setCommand(MediaPlayerCommand.MEDIA_PLAYER_COMMAND_VOLUME_UP);
        } else if (command == IncreaseDecreaseType.DECREASE) {
            builder.setHasCommand(true).setCommand(MediaPlayerCommand.MEDIA_PLAYER_COMMAND_VOLUME_DOWN);
        } else if (command == OnOffType.OFF && hasFeature(key, MediaPlayerFeature.VOLUME_MUTE)) {
            builder.setHasCommand(true).setCommand(MediaPlayerCommand.MEDIA_PLAYER_COMMAND_UNMUTE);
        } else if (command == OnOffType.ON && hasFeature(key, MediaPlayerFeature.VOLUME_MUTE)) {
            builder.setHasCommand(true).setCommand(MediaPlayerCommand.MEDIA_PLAYER_COMMAND_MUTE);
        } else if (command == OnOffType.OFF) {
            builder.setHasVolume(true).setVolume(0);
        } else if (command == OnOffType.ON) {
            builder.setHasVolume(true).setVolume(1);
        } else {
            logger.debug("[{}] Ignoring unsupported volume command '{}' for channel {}", handler.getLogPrefix(),
                    command, channel.getUID());
            return;
        }

        handler.sendMessage(builder.build());
    }

    private void handleMuteCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        MediaPlayerCommand esphomeCommand;

        if (command == OnOffType.ON) {
            esphomeCommand = MediaPlayerCommand.MEDIA_PLAYER_COMMAND_MUTE;
        } else if (command == OnOffType.OFF) {
            esphomeCommand = MediaPlayerCommand.MEDIA_PLAYER_COMMAND_UNMUTE;
        } else {
            logger.debug("[{}] Ignoring unsupported mute command '{}' for channel {}", handler.getLogPrefix(), command,
                    channel.getUID());
            return;
        }

        handler.sendMessage(MediaPlayerCommandRequest.newBuilder().setKey(key).setHasCommand(true)
                .setCommand(esphomeCommand).build());
    }

    private void handlePowerCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        MediaPlayerCommand esphomeCommand;

        if (command == OnOffType.ON && hasFeature(key, MediaPlayerFeature.TURN_ON)) {
            esphomeCommand = MediaPlayerCommand.MEDIA_PLAYER_COMMAND_TURN_ON;
        } else if (command == OnOffType.OFF && hasFeature(key, MediaPlayerFeature.TURN_OFF)) {
            esphomeCommand = MediaPlayerCommand.MEDIA_PLAYER_COMMAND_TURN_OFF;
        } else {
            logger.debug("[{}] Ignoring unsupported power command '{}' for channel {}", handler.getLogPrefix(), command,
                    channel.getUID());
            return;
        }

        handler.sendMessage(MediaPlayerCommandRequest.newBuilder().setKey(key).setHasCommand(true)
                .setCommand(esphomeCommand).build());
    }

    private void handleClearPlaylistCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        if (!PRESS.equalsIgnoreCase(command.toString().trim())) {
            logger.debug("[{}] Ignoring unsupported clear_playlist command '{}' for channel {}", handler.getLogPrefix(),
                    command, channel.getUID());
            return;
        }

        handler.sendMessage(MediaPlayerCommandRequest.newBuilder().setKey(key).setHasCommand(true)
                .setCommand(MediaPlayerCommand.MEDIA_PLAYER_COMMAND_CLEAR_PLAYLIST).build());
    }

    private void handleRepeatCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        String repeatValue = command.toString().trim().toLowerCase();
        MediaPlayerCommand esphomeCommand;

        if (REPEAT_ONE.equals(repeatValue)) {
            esphomeCommand = MediaPlayerCommand.MEDIA_PLAYER_COMMAND_REPEAT_ONE;
        } else if (REPEAT_OFF.equals(repeatValue)) {
            esphomeCommand = MediaPlayerCommand.MEDIA_PLAYER_COMMAND_REPEAT_OFF;
        } else {
            logger.debug("[{}] Ignoring unsupported repeat command '{}' for channel {}", handler.getLogPrefix(),
                    command, channel.getUID());
            return;
        }

        repeatStates.put(key, repeatValue);
        handler.sendMessage(MediaPlayerCommandRequest.newBuilder().setKey(key).setHasCommand(true)
                .setCommand(esphomeCommand).build());
        handler.updateState(channel.getUID(), new StringType(repeatValue));
    }

    @Override
    public void buildChannels(ListEntitiesMediaPlayerResponse rsp) {
        mediaPlayerFeatureFlags.put(rsp.getKey(), rsp.getFeatureFlags());

        if (supportsPlayerChannel(rsp)) {
            Configuration configuration = configuration(EntityTypes.MEDIA_PLAYER, rsp.getKey(), CHANNEL_PLAYER);
            ChannelType channelType = addChannelType(rsp.getName(), PLAYER, Set.of("Control", "MediaControl"),
                    "MediaControl", rsp.getEntityCategory(), rsp.getDisabledByDefault());

            Channel channel = ChannelBuilder.create(createChannelUID(rsp.getObjectId(), EntityTypes.MEDIA_PLAYER))
                    .withLabel(createChannelLabel(rsp.getName())).withKind(ChannelKind.STATE)
                    .withType(channelType.getUID()).withAcceptedItemType(PLAYER).withConfiguration(configuration)
                    .build();

            super.registerChannel(channel, channelType);
        }

        if (supportsVolumeChannel(rsp)) {
            ChannelType volumeChannelType = addChannelType("Volume", DIMMER, Set.of("Control", "SoundVolume"),
                    "SoundVolume", rsp.getEntityCategory(), rsp.getDisabledByDefault());
            Channel volumeChannel = ChannelBuilder
                    .create(createChannelUID(rsp.getObjectId(), EntityTypes.MEDIA_PLAYER, CHANNEL_VOLUME))
                    .withLabel(createChannelLabel(rsp.getName(), "Volume")).withKind(ChannelKind.STATE)
                    .withType(volumeChannelType.getUID()).withAcceptedItemType(DIMMER)
                    .withConfiguration(configuration(EntityTypes.MEDIA_PLAYER, rsp.getKey(), CHANNEL_VOLUME)).build();
            super.registerChannel(volumeChannel, volumeChannelType, patternStateDescription("%d %%"));
        }

        if (supportsPowerChannel(rsp)) {
            ChannelType powerChannelType = addChannelType("Power", SWITCH, Set.of("Control"), "Switch",
                    rsp.getEntityCategory(), rsp.getDisabledByDefault());
            Channel powerChannel = ChannelBuilder
                    .create(createChannelUID(rsp.getObjectId(), EntityTypes.MEDIA_PLAYER, CHANNEL_POWER))
                    .withLabel(createChannelLabel(rsp.getName(), "Power")).withKind(ChannelKind.STATE)
                    .withType(powerChannelType.getUID()).withAcceptedItemType(SWITCH)
                    .withConfiguration(configuration(EntityTypes.MEDIA_PLAYER, rsp.getKey(), CHANNEL_POWER)).build();
            super.registerChannel(powerChannel, powerChannelType);
        }

        if (hasFeature(rsp.getKey(), MediaPlayerFeature.CLEAR_PLAYLIST)) {
            ChannelType clearPlaylistChannelType = addChannelType("Clear Playlist", STRING, Set.of("Control"), "",
                    rsp.getEntityCategory(), rsp.getDisabledByDefault());
            Channel clearPlaylistChannel = ChannelBuilder
                    .create(createChannelUID(rsp.getObjectId(), EntityTypes.MEDIA_PLAYER, CHANNEL_CLEAR_PLAYLIST))
                    .withLabel(createChannelLabel(rsp.getName(), "Clear Playlist")).withKind(ChannelKind.STATE)
                    .withType(clearPlaylistChannelType.getUID()).withAcceptedItemType(STRING)
                    .withConfiguration(configuration(EntityTypes.MEDIA_PLAYER, rsp.getKey(), CHANNEL_CLEAR_PLAYLIST))
                    .build();
            super.registerChannel(clearPlaylistChannel, clearPlaylistChannelType, null,
                    optionListCommandDescription(PRESS_OPTIONS));
        }

        if (hasFeature(rsp.getKey(), MediaPlayerFeature.VOLUME_MUTE)) {
            ChannelType muteChannelType = addChannelType("Mute", SWITCH, Set.of("Control"), "SoundVolume",
                    rsp.getEntityCategory(), rsp.getDisabledByDefault());
            Channel muteChannel = ChannelBuilder
                    .create(createChannelUID(rsp.getObjectId(), EntityTypes.MEDIA_PLAYER, CHANNEL_MUTE))
                    .withLabel(createChannelLabel(rsp.getName(), "Mute")).withKind(ChannelKind.STATE)
                    .withType(muteChannelType.getUID()).withAcceptedItemType(SWITCH)
                    .withConfiguration(configuration(EntityTypes.MEDIA_PLAYER, rsp.getKey(), CHANNEL_MUTE)).build();
            super.registerChannel(muteChannel, muteChannelType);
        }

        if (hasFeature(rsp.getKey(), MediaPlayerFeature.REPEAT_SET)) {
            ChannelType repeatChannelType = addChannelType("Repeat", STRING, Set.of("Control"), "",
                    rsp.getEntityCategory(), rsp.getDisabledByDefault());
            Channel repeatChannel = ChannelBuilder
                    .create(createChannelUID(rsp.getObjectId(), EntityTypes.MEDIA_PLAYER, CHANNEL_REPEAT))
                    .withLabel(createChannelLabel(rsp.getName(), "Repeat")).withKind(ChannelKind.STATE)
                    .withType(repeatChannelType.getUID()).withAcceptedItemType(STRING)
                    .withConfiguration(configuration(EntityTypes.MEDIA_PLAYER, rsp.getKey(), CHANNEL_REPEAT)).build();
            super.registerChannel(repeatChannel, repeatChannelType, optionListStateDescription(REPEAT_OPTIONS),
                    optionListCommandDescription(REPEAT_OPTIONS));
        }

        ChannelType stateChannelType = addChannelType("State", STRING, Set.of("Control"), "MediaControl",
                rsp.getEntityCategory(), true);
        StateDescription stateDescription = optionListStateDescription(stateOptions(), true);

        Channel stateChannel = ChannelBuilder
                .create(createChannelUID(rsp.getObjectId(), EntityTypes.MEDIA_PLAYER, CHANNEL_STATE))
                .withLabel(createChannelLabel(rsp.getName(), "State")).withKind(ChannelKind.STATE)
                .withType(stateChannelType.getUID()).withAcceptedItemType(STRING)
                .withConfiguration(configuration(EntityTypes.MEDIA_PLAYER, rsp.getKey(), CHANNEL_STATE)).build();

        super.registerChannel(stateChannel, stateChannelType, stateDescription);
    }

    @Override
    public void handleState(MediaPlayerStateResponse rsp) {
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_PLAYER).ifPresent(channel -> {
            if (rsp.getState() == MediaPlayerState.MEDIA_PLAYER_STATE_PLAYING
                    || rsp.getState() == MediaPlayerState.MEDIA_PLAYER_STATE_ANNOUNCING) {
                handler.updateState(channel.getUID(), PlayPauseType.PLAY);
            } else {
                handler.updateState(channel.getUID(), PlayPauseType.PAUSE);
            }
        });

        findChannelByKeyAndField(rsp.getKey(), CHANNEL_STATE).ifPresent(
                channel -> handler.updateState(channel.getUID(), new StringType(stripEnumPrefix(rsp.getState()))));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_VOLUME).ifPresent(
                channel -> handler.updateState(channel.getUID(), new PercentType(Math.round(rsp.getVolume() * 100f))));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_POWER).ifPresent(channel -> handler.updateState(channel.getUID(),
                rsp.getState() == MediaPlayerState.MEDIA_PLAYER_STATE_OFF ? OnOffType.OFF : OnOffType.ON));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_MUTE).ifPresent(
                channel -> handler.updateState(channel.getUID(), rsp.getMuted() ? OnOffType.ON : OnOffType.OFF));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_REPEAT)
                .ifPresent(channel -> handler.updateState(channel.getUID(),
                        new StringType(repeatStates.computeIfAbsent(rsp.getKey(), key -> REPEAT_OFF))));
    }

    private List<String> stateOptions() {
        return List.of("IDLE", "PLAYING", "PAUSED", "ANNOUNCING", "OFF", "ON");
    }

    private boolean supportsPlayerChannel(ListEntitiesMediaPlayerResponse rsp) {
        return rsp.getSupportsPause() || hasFeature(rsp.getKey(), MediaPlayerFeature.PLAY)
                || hasFeature(rsp.getKey(), MediaPlayerFeature.PAUSE);
    }

    private boolean supportsVolumeChannel(ListEntitiesMediaPlayerResponse rsp) {
        return hasFeature(rsp.getKey(), MediaPlayerFeature.VOLUME_SET)
                || hasFeature(rsp.getKey(), MediaPlayerFeature.VOLUME_STEP);
    }

    private boolean supportsPowerChannel(ListEntitiesMediaPlayerResponse rsp) {
        return hasFeature(rsp.getKey(), MediaPlayerFeature.TURN_ON)
                || hasFeature(rsp.getKey(), MediaPlayerFeature.TURN_OFF);
    }

    private boolean hasFeature(int key, MediaPlayerFeature feature) {
        return (mediaPlayerFeatureFlags.getOrDefault(key, 0) & feature.getFlag()) != 0;
    }

    private String stripEnumPrefix(MediaPlayerState state) {
        return state.name().replaceFirst("^MEDIA_PLAYER_STATE_", "");
    }
}
