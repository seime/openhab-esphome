package no.seime.openhab.binding.esphome.internal.message;

import static org.openhab.core.library.CoreItemFactory.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.openhab.core.library.types.*;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.openhab.core.types.StateDescription;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;

import io.esphome.api.FanCommandRequest;
import io.esphome.api.FanDirection;
import io.esphome.api.FanStateResponse;
import io.esphome.api.ListEntitiesFanResponse;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.EntityTypes;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class FanMessageHandler extends AbstractMessageHandler<ListEntitiesFanResponse, FanStateResponse> {

    public static final String CHANNEL_STATE = "state";
    public static final String CHANNEL_OSCILLATION = "oscillation";
    public static final String CHANNEL_DIRECTION = "direction";
    public static final String CHANNEL_SPEED_LEVEL = "speed_level";
    public static final String CHANNEL_PRESET = "preset";

    private final Logger logger = LoggerFactory.getLogger(FanMessageHandler.class);

    private final ReentrantLock lock = new ReentrantLock();

    private final LoadingCache<Integer, FanCommandRequest.Builder> commandAggregatingCache;

    private Thread expiryThread = null;

    public FanMessageHandler(ESPHomeHandler handler) {
        super(handler);

        // Cache the commands for a short time to allow for multiple OH channel commands to be sent as 1 request to
        // ESPHome
        commandAggregatingCache = CacheBuilder.newBuilder().maximumSize(10)
                .expireAfterAccess(400, TimeUnit.MILLISECONDS)
                .removalListener((RemovalListener<Integer, FanCommandRequest.Builder>) notification -> {
                    if (notification.getValue() != null) {
                        try {
                            logger.debug("[{}] Sending Fan command for key {}", handler.getLogPrefix(),
                                    notification.getValue().getKey());
                            handler.sendMessage(notification.getValue().build());
                        } catch (ProtocolAPIError e) {
                            logger.error("[{}] Failed to send Fan command for key {}", handler.getLogPrefix(),
                                    notification.getValue().getKey(), e);
                        }
                    }
                }).build(new CacheLoader<>() {
                    public FanCommandRequest.Builder load(Integer key) {
                        return FanCommandRequest.newBuilder().setKey(key);
                    }
                });
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) {
        try {
            lock.lock();
            FanCommandRequest.Builder builder = commandAggregatingCache.get(key);

            if (command == StopMoveType.STOP) {
                builder.setSpeedLevel(0);
            } else {

                String subCommand = (String) channel.getConfiguration()
                        .get(BindingConstants.CHANNEL_CONFIGURATION_ENTITY_FIELD);
                switch (subCommand) {
                    case CHANNEL_STATE -> {
                        if (command instanceof OnOffType) {
                            builder.setState(command == OnOffType.ON);
                            builder.setHasState(true);
                        } else {
                            logger.warn("[{}] Unsupported command type for Fan state: {}", handler.getLogPrefix(),
                                    command.getClass().getSimpleName());
                        }
                    }

                    case CHANNEL_OSCILLATION -> {
                        if (command instanceof OnOffType) {
                            builder.setOscillating(command == OnOffType.ON);
                            builder.setHasOscillating(true);
                        } else {
                            logger.warn("[{}] Unsupported command type for Fan oscillation: {}", handler.getLogPrefix(),
                                    command.getClass().getSimpleName());
                        }
                    }
                    case CHANNEL_DIRECTION -> {
                        if (command instanceof StringType) {
                            try {
                                builder.setDirection(
                                        FanDirection.valueOf("FAN_DIRECTION_" + command.toString().toUpperCase()));
                                builder.setHasDirection(true);
                            } catch (IllegalArgumentException e) {
                                logger.warn("[{}] Unsupported command value for Fan direction: {}",
                                        handler.getLogPrefix(), command);
                            }
                        } else {
                            logger.warn("[{}] Unsupported command type for Fan direction: {}", handler.getLogPrefix(),
                                    command.getClass().getSimpleName());
                        }
                    }
                    case CHANNEL_SPEED_LEVEL -> {
                        if (command instanceof PercentType) {
                            builder.setSpeedLevel(((PercentType) command).intValue());
                            builder.setHasSpeedLevel(true);
                        } else if (command instanceof DecimalType) {
                            builder.setSpeedLevel(((DecimalType) command).intValue());
                            builder.setHasSpeedLevel(true);
                        } else if (command instanceof QuantityType<?>) {
                            builder.setSpeedLevel(((QuantityType) command).intValue());
                            builder.setHasSpeedLevel(true);
                        } else {
                            logger.warn("[{}] Unsupported command type for Fan speed level: {}", handler.getLogPrefix(),
                                    command.getClass().getSimpleName());
                        }
                    }
                    case CHANNEL_PRESET -> {
                        if (command instanceof StringType) {
                            builder.setPresetMode(command.toString());
                            builder.setHasPresetMode(true);
                        } else {
                            logger.warn("[{}] Unsupported command type for Fan preset: {}", handler.getLogPrefix(),
                                    command.getClass().getSimpleName());
                        }
                    }

                    default -> logger.warn("[{}] Unknown Fan subcommand {}", handler.getLogPrefix(), subCommand);
                }
            }
            // Start a thread that will clean up the cache (send the pending messages)
            if (expiryThread == null || !expiryThread.isAlive()) {
                expiryThread = new Thread(() -> {
                    while (commandAggregatingCache.size() > 0) {
                        try {
                            lock.lock();
                            logger.debug("[{}] Calling cleanup", handler.getLogPrefix());
                            commandAggregatingCache.cleanUp();
                        } finally {
                            lock.unlock();
                        }
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            logger.error("[{}] Error sleeping", handler.getLogPrefix(), e);
                        }

                    }
                });
                expiryThread.start();
            }

        } catch (ExecutionException e) {
            logger.error("[{}] Error buffering Fan command", handler.getLogPrefix(), e);
        } finally {
            lock.unlock();
        }
    }

    public static String stripEnumPrefix(FanDirection fanDirection) {
        String toRemove = "FAN_DIRECTION";
        return fanDirection.toString().substring(toRemove.length() + 1);
    }

    public void buildChannels(ListEntitiesFanResponse rsp) {

        String icon = getChannelIcon(rsp.getIcon(), "fan");

        ChannelType channelTypeState = addChannelType(rsp.getObjectId() + CHANNEL_STATE, "State", SWITCH,
                Set.of("Switch"), icon, rsp.getEntityCategory(), rsp.getDisabledByDefault());

        Channel channelState = ChannelBuilder
                .create(createChannelUID(handler, rsp.getObjectId(), EntityTypes.FAN, CHANNEL_STATE))
                .withLabel(createChannelLabel(rsp.getName(), "State")).withKind(ChannelKind.STATE)
                .withType(channelTypeState.getUID()).withAcceptedItemType(SWITCH)
                .withConfiguration(configuration(EntityTypes.FAN, rsp.getKey(), CHANNEL_STATE)).build();

        super.registerChannel(channelState, channelTypeState);

        if (rsp.getSupportsOscillation()) {
            ChannelType channelTypeOscillation = addChannelType(rsp.getObjectId() + CHANNEL_OSCILLATION, "Oscillation",
                    SWITCH, Set.of("Control"), icon, rsp.getEntityCategory(), rsp.getDisabledByDefault());

            Channel channelOscillation = ChannelBuilder
                    .create(createChannelUID(handler, rsp.getObjectId(), EntityTypes.FAN, CHANNEL_OSCILLATION))
                    .withLabel(createChannelLabel(rsp.getName(), "Oscillation")).withKind(ChannelKind.STATE)
                    .withType(channelTypeOscillation.getUID()).withAcceptedItemType(SWITCH)
                    .withConfiguration(configuration(EntityTypes.FAN, rsp.getKey(), CHANNEL_OSCILLATION)).build();

            super.registerChannel(channelOscillation, channelTypeOscillation);
        }
        if (rsp.getSupportsDirection()) {

            ChannelType channelTypeDirection = addChannelType(rsp.getObjectId() + CHANNEL_DIRECTION, "Direction",
                    STRING, Set.of("Control"), "fan", rsp.getEntityCategory(), rsp.getDisabledByDefault());
            StateDescription stateDescription = optionListStateDescription(
                    Arrays.stream(FanDirection.values()).filter(e -> e != FanDirection.UNRECOGNIZED)
                            .map(e -> stripEnumPrefix(e)).collect(Collectors.toList()));

            Channel channelDirection = ChannelBuilder
                    .create(createChannelUID(handler, rsp.getObjectId(), EntityTypes.FAN, CHANNEL_DIRECTION))
                    .withLabel(createChannelLabel(rsp.getName(), "Direction")).withKind(ChannelKind.STATE)
                    .withType(channelTypeDirection.getUID()).withAcceptedItemType(STRING)
                    .withConfiguration(configuration(EntityTypes.FAN, rsp.getKey(), CHANNEL_DIRECTION)).build();
            super.registerChannel(channelDirection, channelTypeDirection, stateDescription);

        }

        if (rsp.getSupportsSpeed()) {

            int supportedSpeedLevels = rsp.getSupportedSpeedCount();

            ChannelType channelTypeSpeed = addChannelType(rsp.getObjectId() + CHANNEL_SPEED_LEVEL, "Speed", DIMMER,
                    Set.of("Setpoint", "Speed"), icon, rsp.getEntityCategory(), rsp.getDisabledByDefault());
            StateDescription stateDescription = numericStateDescription(null,
                    BigDecimal.valueOf(100 / supportedSpeedLevels), BigDecimal.ZERO, BigDecimal.valueOf(100));

            Channel channelSpeed = ChannelBuilder
                    .create(createChannelUID(handler, rsp.getObjectId(), EntityTypes.FAN, CHANNEL_SPEED_LEVEL))
                    .withLabel(createChannelLabel(rsp.getName(), "Speed")).withKind(ChannelKind.STATE)
                    .withType(channelTypeSpeed.getUID()).withAcceptedItemType(DIMMER)
                    .withConfiguration(configuration(EntityTypes.FAN, rsp.getKey(), CHANNEL_SPEED_LEVEL)).build();
            super.registerChannel(channelSpeed, channelTypeSpeed, stateDescription);

        }

        if (rsp.getSupportedPresetModesCount() > 0) {
            ChannelType channelTypePreset = addChannelType(rsp.getObjectId() + CHANNEL_PRESET, "Preset", STRING,
                    Set.of("Setpoint"), "fan", rsp.getEntityCategory(), rsp.getDisabledByDefault());
            StateDescription stateDescription = optionListStateDescription(rsp.getSupportedPresetModesList());
            Channel channelPreset = ChannelBuilder
                    .create(createChannelUID(handler, rsp.getObjectId(), EntityTypes.FAN, CHANNEL_PRESET))
                    .withLabel(createChannelLabel(rsp.getName(), "Preset")).withKind(ChannelKind.STATE)
                    .withType(channelTypePreset.getUID()).withAcceptedItemType(STRING)
                    .withConfiguration(configuration(EntityTypes.FAN, rsp.getKey(), CHANNEL_PRESET)).build();
            super.registerChannel(channelPreset, channelTypePreset, stateDescription);
        }
    }

    @Override
    public void handleState(FanStateResponse rsp) {
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_STATE).ifPresent(
                channel -> handler.updateState(channel.getUID(), rsp.getState() ? OnOffType.ON : OnOffType.OFF));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_OSCILLATION).ifPresent(
                channel -> handler.updateState(channel.getUID(), rsp.getOscillating() ? OnOffType.ON : OnOffType.OFF));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_DIRECTION).ifPresent(
                channel -> handler.updateState(channel.getUID(), new StringType(stripEnumPrefix(rsp.getDirection()))));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_SPEED_LEVEL)
                .ifPresent(channel -> handler.updateState(channel.getUID(), new PercentType(rsp.getSpeedLevel())));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_PRESET)
                .ifPresent(channel -> handler.updateState(channel.getUID(),
                        "".equals(rsp.getPresetMode()) ? UnDefType.UNDEF : new StringType(rsp.getPresetMode())));
    }
}
