package no.seime.openhab.binding.esphome.internal.message;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.openhab.core.library.types.*;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
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
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class FanMessageHandler extends AbstractMessageHandler<ListEntitiesFanResponse, FanStateResponse> {

    public static final String CHANNEL_STATE = "state";
    public static final String CHANNEL_OSCILLATION = "oscillation";
    public static final String CHANNEL_DIRECTION = "direction";
    public static final String CHANNEL_SPEED_LEVEL = "speed_level";
    public static final String CHANNEL_PRESET = "preset";
    public static final String COMMAND_CLASS_FAN = "Fan";

    private final Logger logger = LoggerFactory.getLogger(FanMessageHandler.class);

    private final ReentrantLock lock = new ReentrantLock();

    private final LoadingCache<Integer, FanCommandRequest.Builder> commands;

    private Thread expiryThread = null;

    public FanMessageHandler(ESPHomeHandler handler) {
        super(handler);

        // Cache the commands for a short time to allow for multiple OH channel commands to be sent as 1 request to
        // ESPHome
        commands = CacheBuilder.newBuilder().maximumSize(10).expireAfterAccess(400, TimeUnit.MILLISECONDS)
                .removalListener((RemovalListener<Integer, FanCommandRequest.Builder>) notification -> {
                    if (notification.getValue() != null) {
                        try {
                            logger.debug("Sending Fan command for key {}", notification.getValue().getKey());
                            handler.sendMessage(notification.getValue().build());
                        } catch (ProtocolAPIError e) {
                            logger.error("Failed to send Fan command for key {}", notification.getValue().getKey(), e);
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
            FanCommandRequest.Builder builder = commands.get(key);

            if (command == StopMoveType.STOP) {
                builder.setSpeedLevel(0);
            } else {

                String subCommand = (String) channel.getConfiguration().get(BindingConstants.COMMAND_FIELD);
                switch (subCommand) {
                    case CHANNEL_STATE -> {
                        if (command instanceof OnOffType) {
                            builder.setState(command == OnOffType.ON);
                            builder.setHasState(true);
                        } else {
                            logger.warn("Unsupported command type for Fan state: {}",
                                    command.getClass().getSimpleName());
                        }
                    }

                    case CHANNEL_OSCILLATION -> {
                        if (command instanceof OnOffType) {
                            builder.setOscillating(command == OnOffType.ON);
                            builder.setHasOscillating(true);
                        } else {
                            logger.warn("Unsupported command type for Fan oscillation: {}",
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
                                logger.warn("Unsupported command value for Fan direction: {}", command);
                            }
                        } else {
                            logger.warn("Unsupported command type for Fan direction: {}",
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
                            logger.warn("Unsupported command type for Fan speed level: {}",
                                    command.getClass().getSimpleName());
                        }
                    }
                    case CHANNEL_PRESET -> {
                        if (command instanceof StringType) {
                            builder.setPresetMode(command.toString());
                            builder.setHasPresetMode(true);
                        } else {
                            logger.warn("Unsupported command type for Fan preset: {}",
                                    command.getClass().getSimpleName());
                        }
                    }

                    default -> logger.warn("Unknown Fan subcommand {}", subCommand);
                }
            }
            // Start a thread that will clean up the cache (send the pending messages)
            if (expiryThread == null || !expiryThread.isAlive()) {
                expiryThread = new Thread(() -> {
                    while (commands.size() > 0) {
                        try {
                            lock.lock();
                            logger.debug("Calling cleanup");
                            commands.cleanUp();
                        } finally {
                            lock.unlock();
                        }
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            logger.error("Error sleeping", e);
                        }

                    }
                });
                expiryThread.start();
            }

        } catch (ExecutionException e) {
            logger.error("Error buffering Fan command", e);
        } finally {
            lock.unlock();
        }
    }

    private ChannelUID createChannelUID(String componentName, String channelName) {
        return new ChannelUID(handler.getThing().getUID(), String.format("%s#%s", componentName, channelName));
    }

    public static String stripEnumPrefix(FanDirection fanDirection) {
        String toRemove = "FAN_DIRECTION";
        return fanDirection.toString().substring(toRemove.length() + 1);
    }

    public void buildChannels(ListEntitiesFanResponse rsp) {

        String cleanedComponentName = rsp.getName().replace(" ", "_").toLowerCase();

        String icon = getChannelIcon(rsp.getIcon(), "fan");

        ChannelType channelTypeState = addChannelType(rsp.getUniqueId() + CHANNEL_STATE, "State", "Switch",
                Collections.emptySet(), null, Set.of("Switch"), false, icon, null, null, null, rsp.getEntityCategory());

        Channel channelState = ChannelBuilder.create(createChannelUID(cleanedComponentName, CHANNEL_STATE))
                .withLabel(createLabel(rsp.getName(), "State")).withKind(ChannelKind.STATE)
                .withType(channelTypeState.getUID()).withAcceptedItemType("Switch")
                .withConfiguration(configuration(rsp.getKey(), CHANNEL_STATE, COMMAND_CLASS_FAN)).build();

        super.registerChannel(channelState, channelTypeState);

        if (rsp.getSupportsOscillation()) {
            ChannelType channelTypeOscillation = addChannelType(rsp.getUniqueId() + CHANNEL_OSCILLATION, "Oscillation",
                    "Switch", Collections.emptySet(), null, Set.of("Switch"), false, icon, null, null, null,
                    rsp.getEntityCategory());

            Channel channelOscillation = ChannelBuilder
                    .create(createChannelUID(cleanedComponentName, CHANNEL_OSCILLATION))
                    .withLabel(createLabel(rsp.getName(), "Oscillation")).withKind(ChannelKind.STATE)
                    .withType(channelTypeOscillation.getUID()).withAcceptedItemType("Switch")
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_OSCILLATION, COMMAND_CLASS_FAN)).build();

            super.registerChannel(channelOscillation, channelTypeOscillation);
        }
        if (rsp.getSupportsDirection()) {

            ChannelType channelTypeDirection = addChannelType(rsp.getUniqueId() + CHANNEL_DIRECTION, "Direction",
                    "String",
                    Arrays.stream(FanDirection.values()).filter(e -> e != FanDirection.UNRECOGNIZED)
                            .map(e -> stripEnumPrefix(e)).collect(Collectors.toList()),
                    "%s", null, false, "fan", null, null, null, rsp.getEntityCategory());

            Channel channelDirection = ChannelBuilder.create(createChannelUID(cleanedComponentName, CHANNEL_DIRECTION))
                    .withLabel(createLabel(rsp.getName(), "Direction")).withKind(ChannelKind.STATE)
                    .withType(channelTypeDirection.getUID()).withAcceptedItemType("String")
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_DIRECTION, COMMAND_CLASS_FAN)).build();
            super.registerChannel(channelDirection, channelTypeDirection);

        }

        if (rsp.getSupportsSpeed()) {

            int supportedSpeedLevels = rsp.getSupportedSpeedCount();

            ChannelType channelTypeSpeed = addChannelType(rsp.getUniqueId() + CHANNEL_SPEED_LEVEL, "Speed", "Dimmer",
                    Collections.emptySet(), null, Set.of("Speed"), false, icon,
                    BigDecimal.valueOf(100 / supportedSpeedLevels), BigDecimal.ZERO, BigDecimal.valueOf(100),
                    rsp.getEntityCategory());

            Channel channelSpeed = ChannelBuilder.create(createChannelUID(cleanedComponentName, CHANNEL_SPEED_LEVEL))
                    .withLabel(createLabel(rsp.getName(), "Speed")).withKind(ChannelKind.STATE)
                    .withType(channelTypeSpeed.getUID()).withAcceptedItemType("Dimmer")
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_SPEED_LEVEL, COMMAND_CLASS_FAN)).build();
            super.registerChannel(channelSpeed, channelTypeSpeed);

        }

        if (rsp.getSupportedPresetModesCount() > 0) {
            ChannelType channelTypePreset = addChannelType(rsp.getUniqueId() + CHANNEL_PRESET, "Preset", "String",
                    rsp.getSupportedPresetModesList(), "%s", Set.of("Setpoint"), false, "fan", null, null, null,
                    rsp.getEntityCategory());
            Channel channelPreset = ChannelBuilder.create(createChannelUID(cleanedComponentName, CHANNEL_PRESET))
                    .withLabel(createLabel(rsp.getName(), "Preset")).withKind(ChannelKind.STATE)
                    .withType(channelTypePreset.getUID()).withAcceptedItemType("String")
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_PRESET, COMMAND_CLASS_FAN)).build();
            super.registerChannel(channelPreset, channelTypePreset);
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
