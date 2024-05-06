package no.seime.openhab.binding.esphome.internal.message;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StopMoveType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
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
    public static final String CHANNEL_SPEED = "speed";
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
                        }
                    }

                    case CHANNEL_OSCILLATION -> {
                        if (command instanceof OnOffType) {
                            builder.setOscillating(command == OnOffType.ON);
                            builder.setHasOscillating(true);
                        }
                    }
                    case CHANNEL_DIRECTION -> {
                        if (command instanceof StringType) {
                            builder.setDirection(
                                    FanDirection.valueOf("FAN_DIRECTION_" + command.toString().toUpperCase()));
                            builder.setHasDirection(true);
                        }
                    }
                    case CHANNEL_SPEED -> {

                        // TODO support speed levels
                        // See https://esphome.io/components/fan/ and
                        // https://developers.home-assistant.io/docs/core/entity/fan/

                        // Not really sure yet which OH item type speed should be mapped to, but make sure this code
                        // block can handle
                        // all types of command that OH allows for the corresponding item type.
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

    private String createLabel(String componentName, String channelName) {
        return String.format("%s %s", componentName, channelName).trim();
    }

    public static String stripEnumPrefix(FanDirection climatePreset) {
        String toRemove = "FAN_DIRECTION";
        return climatePreset.toString().substring(toRemove.length() + 1);
    }

    public void buildChannels(ListEntitiesFanResponse rsp) {

        String cleanedComponentName = rsp.getName().replace(" ", "_").toLowerCase();

        String icon = getChannelIcon(rsp.getIcon(), "fan");

        ChannelType channelTypeState = addChannelType(rsp.getUniqueId() + CHANNEL_STATE, "State", "Switch",
                Collections.emptySet(), null, Set.of("Switch"), false, icon, null, null, null, rsp.getEntityCategory());

        Channel channelState = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel("State").withKind(ChannelKind.STATE).withType(channelTypeState.getUID())
                .withAcceptedItemType("Switch")
                .withConfiguration(configuration(rsp.getKey(), CHANNEL_OSCILLATION, COMMAND_CLASS_FAN)).build();

        super.registerChannel(channelState, channelTypeState);

        if (rsp.getSupportsOscillation()) {
            ChannelType channelTypeOscillation = addChannelType(rsp.getUniqueId() + CHANNEL_OSCILLATION, "Oscillation",
                    "Switch", Collections.emptySet(), null, Set.of("Switch"), false, icon, null, null, null,
                    rsp.getEntityCategory());

            Channel channelOscillation = ChannelBuilder
                    .create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId())).withLabel(rsp.getName())
                    .withKind(ChannelKind.STATE).withType(channelTypeOscillation.getUID())
                    .withAcceptedItemType("Switch")
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_OSCILLATION, COMMAND_CLASS_FAN)).build();

            super.registerChannel(channelOscillation, channelTypeOscillation);
        }
        if (rsp.getSupportsDirection()) {

            ChannelType channelTypeDirection = addChannelType(rsp.getUniqueId() + CHANNEL_DIRECTION, "Direction",
                    "String",
                    Arrays.stream(FanDirection.values()).map(e -> stripEnumPrefix(e)).collect(Collectors.toList()),
                    "%s", null, false, "fan", null, null, null, rsp.getEntityCategory());

            Channel channelDirection = ChannelBuilder.create(createChannelUID(cleanedComponentName, CHANNEL_DIRECTION))
                    .withLabel(createLabel(rsp.getName(), "Direction")).withKind(ChannelKind.STATE)
                    .withType(channelTypeDirection.getUID()).withAcceptedItemType("String")
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_DIRECTION, COMMAND_CLASS_FAN)).build();
            super.registerChannel(channelDirection, channelTypeDirection);

        }

        // TODO handle creation of speed channel
    }

    @Override
    public void handleState(FanStateResponse rsp) {
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_STATE).ifPresent(
                channel -> handler.updateState(channel.getUID(), rsp.getState() ? OnOffType.ON : OnOffType.OFF));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_OSCILLATION).ifPresent(
                channel -> handler.updateState(channel.getUID(), rsp.getOscillating() ? OnOffType.ON : OnOffType.OFF));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_DIRECTION).ifPresent(
                channel -> handler.updateState(channel.getUID(), new StringType(stripEnumPrefix(rsp.getDirection()))));

        // TODO handle speed level
    }
}
