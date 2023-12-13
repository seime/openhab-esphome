package no.seime.openhab.binding.esphome.internal.message;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StopMoveType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.types.UpDownType;
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

import io.esphome.api.CoverCommandRequest;
import io.esphome.api.CoverOperation;
import io.esphome.api.CoverStateResponse;
import io.esphome.api.LegacyCoverCommand;
import io.esphome.api.LegacyCoverState;
import io.esphome.api.ListEntitiesCoverResponse;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class CoverMessageHandler extends AbstractMessageHandler<ListEntitiesCoverResponse, CoverStateResponse> {

    public static final String CHANNEL_POSITION = "position";
    public static final String CHANNEL_STATE = "state";
    public static final String CHANNEL_TILT = "tilt";
    public static final String CHANNEL_CURRENT_OPERATION = "current_operation";
    public static final String COMMAND_CLASS_COVER = "Cover";

    private final Logger logger = LoggerFactory.getLogger(CoverMessageHandler.class);

    private final ReentrantLock lock = new ReentrantLock();

    private final LoadingCache<Integer, CoverCommandRequest.Builder> commands;

    private Thread expiryThread = null;

    public CoverMessageHandler(ESPHomeHandler handler) {
        super(handler);

        commands = CacheBuilder.newBuilder().maximumSize(10).expireAfterAccess(400, TimeUnit.MILLISECONDS)
                .removalListener((RemovalListener<Integer, CoverCommandRequest.Builder>) notification -> {
                    if (notification.getValue() != null) {
                        try {
                            logger.debug("Sending Cover command for key {}", notification.getValue().getKey());
                            handler.sendMessage(notification.getValue().build());
                        } catch (ProtocolAPIError e) {
                            logger.error("Failed to send Cover command for key {}", notification.getValue().getKey(),
                                    e);
                        }
                    }
                }).build(new CacheLoader<>() {
                    public CoverCommandRequest.Builder load(Integer key) {
                        return CoverCommandRequest.newBuilder().setKey(key);
                    }
                });
    }

    public static String stripEnumPrefix(CoverOperation climatePreset) {
        String toRemove = "COVER_OPERATION";
        return climatePreset.toString().substring(toRemove.length() + 1);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) {
        try {
            lock.lock();
            CoverCommandRequest.Builder builder = commands.get(key);

            if (command == StopMoveType.STOP) {
                builder.setStop(true);
                builder.setLegacyCommand(LegacyCoverCommand.LEGACY_COVER_COMMAND_STOP);
            } else {

                String subCommand = (String) channel.getConfiguration().get(BindingConstants.COMMAND_FIELD);
                switch (subCommand) {
                    case CHANNEL_POSITION -> {
                        if (command instanceof QuantityType<?> qt) {
                            builder.setPosition(qt.floatValue());
                        } else if (command instanceof DecimalType dc) {
                            builder.setPosition(dc.floatValue());
                        } else if (command == UpDownType.UP) {
                            builder.setPosition(100);
                        } else if (command == UpDownType.DOWN) {
                            builder.setPosition(0);
                        }
                        builder.setHasPosition(true);
                    }
                    case CHANNEL_TILT -> {
                        if (command instanceof QuantityType<?> qt) {
                            builder.setTilt(qt.floatValue());
                        } else if (command instanceof DecimalType dc) {
                            builder.setTilt(dc.floatValue());
                        } else if (command == UpDownType.UP) {
                            builder.setPosition(100);
                        } else if (command == UpDownType.DOWN) {
                            builder.setPosition(0);
                        }
                        builder.setHasTilt(true);
                    }
                    case CHANNEL_STATE -> {
                        if (command instanceof QuantityType<?> || command instanceof DecimalType) {
                            logger.warn(
                                    "Ignored numeric command for state channel. Use position or tilt channel to set position or tilt, not the state channel");
                        } else if (command == UpDownType.UP) {
                            builder.setLegacyCommand(LegacyCoverCommand.LEGACY_COVER_COMMAND_OPEN);
                        } else if (command == UpDownType.DOWN) {
                            builder.setLegacyCommand(LegacyCoverCommand.LEGACY_COVER_COMMAND_CLOSE);
                        }
                    }

                    case CHANNEL_CURRENT_OPERATION -> logger.warn("current_operation channel is read-only");
                    default -> logger.warn("Unknown Cover subcommand {}", subCommand);
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
            logger.error("Error buffering Cover command", e);
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

    public void buildChannels(ListEntitiesCoverResponse rsp) {

        String cleanedComponentName = rsp.getName().replace(" ", "_").toLowerCase();

        CoverDeviceClass deviceClass = CoverDeviceClass.fromDeviceClass(rsp.getDeviceClass());

        if (rsp.getSupportsPosition()) {
            ChannelType channelTypePosition = addChannelType(rsp.getUniqueId() + CHANNEL_POSITION, "Position",
                    deviceClass.getItemType(), Collections.emptyList(), "%d %%", Set.of("OpenLevel"), false,
                    deviceClass.getCategory(), null, null, null);

            Channel channelPosition = ChannelBuilder.create(createChannelUID(cleanedComponentName, CHANNEL_POSITION))
                    .withLabel(createLabel(rsp.getName(), "Position")).withKind(ChannelKind.STATE)
                    .withType(channelTypePosition.getUID()).withAcceptedItemType(deviceClass.getItemType())
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_POSITION, COMMAND_CLASS_COVER)).build();
            super.registerChannel(channelPosition, channelTypePosition);
        }
        if (rsp.getSupportsTilt()) {
            ChannelType channelTypeTilt = addChannelType(rsp.getUniqueId() + CHANNEL_TILT, "Tilt",
                    deviceClass.getItemType(), Collections.emptyList(), "%d %%", Set.of("Tilt"), false,
                    deviceClass.getCategory(), null, null, null);

            Channel channelTilt = ChannelBuilder.create(createChannelUID(cleanedComponentName, CHANNEL_TILT))
                    .withLabel(createLabel(rsp.getName(), "Tilt")).withKind(ChannelKind.STATE)
                    .withType(channelTypeTilt.getUID()).withAcceptedItemType(deviceClass.getItemType())
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_TILT, COMMAND_CLASS_COVER)).build();
            super.registerChannel(channelTilt, channelTypeTilt);
        }

        // Legacy state
        ChannelType channelTypeState = addChannelType(rsp.getUniqueId() + CHANNEL_STATE, "State",
                deviceClass.getItemType(), Collections.emptyList(), "%s", Set.of("OpenClose"), false,
                deviceClass.getCategory(), null, null, null);

        Channel channelState = ChannelBuilder.create(createChannelUID(cleanedComponentName, CHANNEL_STATE))
                .withLabel(createLabel(rsp.getName(), "State")).withKind(ChannelKind.STATE)
                .withType(channelTypeState.getUID()).withAcceptedItemType(deviceClass.getItemType())
                .withConfiguration(configuration(rsp.getKey(), CHANNEL_STATE, COMMAND_CLASS_COVER)).build();
        super.registerChannel(channelState, channelTypeState);

        // Operation status
        ChannelType channelTypeCurrentOperation = addChannelType(rsp.getUniqueId() + CHANNEL_CURRENT_OPERATION,
                "Current operation", "String", Set.of("IDLE", "IS_OPENING", "IS_CLOSING"), "%s", Set.of("Status"), true,
                "motion", null, null, null);

        Channel channelCurrentOperation = ChannelBuilder
                .create(createChannelUID(cleanedComponentName, CHANNEL_CURRENT_OPERATION))
                .withLabel(createLabel(rsp.getName(), "Current operation")).withKind(ChannelKind.STATE)
                .withType(channelTypeCurrentOperation.getUID()).withAcceptedItemType("String")
                .withConfiguration(configuration(rsp.getKey(), CHANNEL_CURRENT_OPERATION, COMMAND_CLASS_COVER)).build();
        super.registerChannel(channelCurrentOperation, channelTypeCurrentOperation);
    }

    public void handleState(CoverStateResponse rsp) {
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_STATE).ifPresent(channel -> handler.updateState(channel.getUID(),
                new DecimalType(rsp.getLegacyState() == LegacyCoverState.LEGACY_COVER_STATE_OPEN ? 0 : 100)));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_POSITION).ifPresent(
                channel -> handler.updateState(channel.getUID(), toNumericState(channel, rsp.getPosition(), false)));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_TILT).ifPresent(
                channel -> handler.updateState(channel.getUID(), toNumericState(channel, rsp.getTilt(), false)));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_CURRENT_OPERATION).ifPresent(channel -> handler
                .updateState(channel.getUID(), new StringType(stripEnumPrefix(rsp.getCurrentOperation()))));
    }
}
