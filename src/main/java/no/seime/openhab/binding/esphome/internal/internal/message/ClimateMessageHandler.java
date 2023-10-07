package no.seime.openhab.binding.esphome.internal.internal.message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
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

import io.esphome.api.ClimateCommandRequest;
import io.esphome.api.ClimateStateResponse;
import io.esphome.api.ListEntitiesClimateResponse;
import no.seime.openhab.binding.esphome.internal.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.internal.EnumHelper;
import no.seime.openhab.binding.esphome.internal.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.internal.handler.ESPHomeHandler;

public class ClimateMessageHandler extends AbstractMessageHandler<ListEntitiesClimateResponse, ClimateStateResponse> {

    public static final String CHANNEL_TARGET_TEMPERATURE = "target_temperature";
    public static final String CHANNEL_FAN_MODE = "fan_mode";
    public static final String CHANNEL_CUSTOM_FAN_MODE = "custom_fan_mode";
    public static final String CHANNEL_PRESET = "preset";
    public static final String CHANNEL_CUSTOM_PRESET = "custom_preset";
    public static final String CHANNEL_SWING_MODE = "swing_mode";
    public static final String CHANNEL_CURRENT_TEMPERATURE = "current_temperature";
    public static final String CHANNEL_MODE = "mode";
    public static final String SEMANTIC_TYPE_SETPOINT = "Setpoint";
    public static final String COMMAND_CLASS_CLIMATE = "Climate";

    private final Logger logger = LoggerFactory.getLogger(ClimateMessageHandler.class);

    private final ReentrantLock lock = new ReentrantLock();

    private final LoadingCache<Integer, ClimateCommandRequest.Builder> commands;

    private Thread expiryThread = null;

    public ClimateMessageHandler(ESPHomeHandler handler) {
        super(handler);

        commands = CacheBuilder.newBuilder().maximumSize(10).expireAfterAccess(400, TimeUnit.MILLISECONDS)
                .removalListener((RemovalListener<Integer, ClimateCommandRequest.Builder>) notification -> {
                    if (notification.getValue() != null) {
                        try {
                            logger.debug("Sending climate command for key {}", notification.getValue().getKey());
                            handler.sendMessage(notification.getValue().build());
                        } catch (ProtocolAPIError e) {
                            logger.error("Failed to send climate command for key {}", notification.getValue().getKey(),
                                    e);
                        }
                    }
                }).build(new CacheLoader<>() {
                    public ClimateCommandRequest.Builder load(Integer key) {
                        return ClimateCommandRequest.newBuilder().setKey(key);
                    }
                });
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) {
        try {
            lock.lock();
            ClimateCommandRequest.Builder builder = commands.get(key);
            String subCommand = (String) channel.getConfiguration().get(BindingConstants.COMMAND_FIELD);
            switch (subCommand) {
                case CHANNEL_MODE -> builder.setMode(EnumHelper.toClimateMode(command.toString())).setHasMode(true);
                case CHANNEL_TARGET_TEMPERATURE -> {
                    if (command instanceof QuantityType<?> qt) {
                        builder.setTargetTemperature(qt.floatValue());
                    } else if (command instanceof DecimalType dc) {
                        builder.setTargetTemperature(dc.floatValue());
                    }
                    builder.setHasTargetTemperature(true);
                }
                case CHANNEL_FAN_MODE ->
                    builder.setFanMode(EnumHelper.toFanMode(command.toString())).setHasFanMode(true);
                case CHANNEL_CUSTOM_FAN_MODE -> builder.setCustomFanMode(command.toString()).setHasCustomFanMode(true);
                case CHANNEL_PRESET ->
                    builder.setPreset(EnumHelper.toClimatePreset(command.toString())).setHasPreset(true);
                case CHANNEL_CUSTOM_PRESET -> builder.setCustomPreset(command.toString()).setHasCustomPreset(true);
                case CHANNEL_SWING_MODE ->
                    builder.setSwingMode(EnumHelper.toClimateSwingMode(command.toString())).setHasSwingMode(true);
                default -> logger.warn("Unknown climate subcommand {}", subCommand);
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
            logger.error("Error buffering climate command", e);
        } finally {
            lock.unlock();
        }
    }

    public void buildChannels(ListEntitiesClimateResponse rsp) {

        String itemTypeTemperature = "Number:Temperature";
        ChannelType channelTypeTargetTemperature = addChannelType(rsp.getUniqueId() + CHANNEL_TARGET_TEMPERATURE,
                "Target temperature", itemTypeTemperature, Collections.emptyList(), "%.1f %unit%",
                Set.of(SEMANTIC_TYPE_SETPOINT, "Temperature"), false, "temperature", null, null, null);

        Channel channelTargetTemperature = ChannelBuilder
                .create(new ChannelUID(handler.getThing().getUID(), CHANNEL_TARGET_TEMPERATURE))
                .withLabel("Target temperature").withKind(ChannelKind.STATE)
                .withType(channelTypeTargetTemperature.getUID()).withAcceptedItemType(itemTypeTemperature)
                .withConfiguration(configuration(rsp.getKey(), CHANNEL_TARGET_TEMPERATURE, COMMAND_CLASS_CLIMATE))
                .build();
        super.registerChannel(channelTargetTemperature, channelTypeTargetTemperature);

        if (rsp.getSupportsCurrentTemperature()) {
            ChannelType channelType = addChannelType(rsp.getUniqueId() + CHANNEL_CURRENT_TEMPERATURE,
                    "Current temperature", itemTypeTemperature, Collections.emptyList(), "%.1f %unit%",
                    Set.of("Measurement", "Temperature"), true, "temperature", null, null, null);

            Channel channel = ChannelBuilder
                    .create(new ChannelUID(handler.getThing().getUID(), CHANNEL_CURRENT_TEMPERATURE))
                    .withLabel("Current temperature").withKind(ChannelKind.STATE).withType(channelType.getUID())
                    .withAcceptedItemType(itemTypeTemperature)
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_CURRENT_TEMPERATURE, null)).build();
            super.registerChannel(channel, channelType);
        }

        String itemTypeString = "String";
        if (rsp.getSupportedModesCount() > 0) {
            ChannelType channelType = addChannelType(rsp.getUniqueId() + CHANNEL_MODE, "Mode", itemTypeString,
                    rsp.getSupportedModesList().stream().map(EnumHelper::stripEnumPrefix).collect(Collectors.toList()),
                    "%s", null, false, "climate", null, null, null);

            Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), CHANNEL_MODE))
                    .withLabel("Mode").withKind(ChannelKind.STATE).withType(channelType.getUID())
                    .withAcceptedItemType(itemTypeString)
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_MODE, COMMAND_CLASS_CLIMATE)).build();
            super.registerChannel(channel, channelType);
        }
        if (rsp.getSupportedFanModesCount() > 0) {
            ChannelType channelType = addChannelType(rsp.getUniqueId() + CHANNEL_FAN_MODE, "Fan Mode", itemTypeString,
                    rsp.getSupportedFanModesList().stream().map(EnumHelper::stripEnumPrefix)
                            .collect(Collectors.toList()),
                    "%s", Set.of(SEMANTIC_TYPE_SETPOINT, "Wind"), false, "fan", null, null, null);

            Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), CHANNEL_FAN_MODE))
                    .withLabel("Fan Mode").withKind(ChannelKind.STATE).withType(channelType.getUID())
                    .withAcceptedItemType(itemTypeString)
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_FAN_MODE, COMMAND_CLASS_CLIMATE)).build();
            super.registerChannel(channel, channelType);
        }
        if (rsp.getSupportedCustomFanModesCount() > 0) {
            ChannelType channelType = addChannelType(rsp.getUniqueId() + CHANNEL_CUSTOM_FAN_MODE, "Custom Fan Mode",
                    itemTypeString, new ArrayList<>(rsp.getSupportedCustomFanModesList()), "%s",
                    Set.of(SEMANTIC_TYPE_SETPOINT, "Wind"), false, "fan", null, null, null);

            Channel channel = ChannelBuilder
                    .create(new ChannelUID(handler.getThing().getUID(), CHANNEL_CUSTOM_FAN_MODE))
                    .withLabel("Custom Fan Mode").withKind(ChannelKind.STATE).withType(channelType.getUID())
                    .withAcceptedItemType(itemTypeString)
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_CUSTOM_FAN_MODE, COMMAND_CLASS_CLIMATE))
                    .build();
            super.registerChannel(channel, channelType);
        }
        if (rsp.getSupportedPresetsCount() > 0) {
            ChannelType channelType = addChannelType(rsp.getObjectId() + CHANNEL_PRESET, "Preset", itemTypeString,
                    rsp.getSupportedPresetsList().stream().map(EnumHelper::stripEnumPrefix)
                            .collect(Collectors.toList()),
                    "%s", Set.of(SEMANTIC_TYPE_SETPOINT), false, "climate", null, null, null);
            Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), CHANNEL_PRESET))
                    .withLabel("Preset").withKind(ChannelKind.STATE).withType(channelType.getUID())
                    .withAcceptedItemType(itemTypeString)
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_PRESET, COMMAND_CLASS_CLIMATE)).build();
            super.registerChannel(channel, channelType);
        }
        if (rsp.getSupportedCustomPresetsCount() > 0) {
            ChannelType channelType = addChannelType(rsp.getUniqueId() + CHANNEL_CUSTOM_PRESET, "Custom Preset",
                    itemTypeString, new ArrayList<>(rsp.getSupportedCustomPresetsList()), "%s",
                    Set.of(SEMANTIC_TYPE_SETPOINT), false, "climate", null, null, null);
            Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), CHANNEL_CUSTOM_PRESET))
                    .withLabel("Custom Preset").withKind(ChannelKind.STATE).withType(channelType.getUID())
                    .withAcceptedItemType(itemTypeString)
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_CUSTOM_PRESET, COMMAND_CLASS_CLIMATE))
                    .build();
            super.registerChannel(channel, channelType);
        }
        if (rsp.getSupportedSwingModesCount() > 0) {
            ChannelType channelType = addChannelType(rsp.getUniqueId() + CHANNEL_SWING_MODE, "Swing Mode",
                    itemTypeString,
                    rsp.getSupportedSwingModesList().stream().map(EnumHelper::stripEnumPrefix)
                            .collect(Collectors.toList()),
                    "%s", Set.of(SEMANTIC_TYPE_SETPOINT, "Wind"), false, "fan", null, null, null);
            Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), CHANNEL_SWING_MODE))
                    .withAcceptedItemType(itemTypeString).withLabel("Swing Mode").withKind(ChannelKind.STATE)
                    .withType(channelType.getUID())
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_SWING_MODE, COMMAND_CLASS_CLIMATE)).build();
            super.registerChannel(channel, channelType);
        }
    }

    public void handleState(ClimateStateResponse rsp) {
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_TARGET_TEMPERATURE).ifPresent(channel -> handler
                .updateState(channel.getUID(), new QuantityType<>(rsp.getTargetTemperature(), SIUnits.CELSIUS)));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_CURRENT_TEMPERATURE).ifPresent(channel -> handler
                .updateState(channel.getUID(), new QuantityType<>(rsp.getCurrentTemperature(), SIUnits.CELSIUS)));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_MODE).ifPresent(channel -> handler.updateState(channel.getUID(),
                new StringType(EnumHelper.stripEnumPrefix(rsp.getMode()))));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_FAN_MODE).ifPresent(channel -> handler
                .updateState(channel.getUID(), new StringType(EnumHelper.stripEnumPrefix(rsp.getFanMode()))));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_CUSTOM_FAN_MODE)
                .ifPresent(channel -> handler.updateState(channel.getUID(), new StringType(rsp.getCustomFanMode())));

        findChannelByKeyAndField(rsp.getKey(), CHANNEL_PRESET).ifPresent(channel -> handler
                .updateState(channel.getUID(), new StringType(EnumHelper.stripEnumPrefix(rsp.getPreset()))));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_CUSTOM_PRESET)
                .ifPresent(channel -> handler.updateState(channel.getUID(), new StringType(rsp.getCustomPreset())));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_SWING_MODE).ifPresent(channel -> handler
                .updateState(channel.getUID(), new StringType(EnumHelper.stripEnumPrefix(rsp.getSwingMode()))));
    }
}
