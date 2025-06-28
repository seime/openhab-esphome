package no.seime.openhab.binding.esphome.internal.message;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.thing.Channel;
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

import io.esphome.api.*;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class ClimateMessageHandler extends AbstractMessageHandler<ListEntitiesClimateResponse, ClimateStateResponse> {

    public static final String CHANNEL_TARGET_TEMPERATURE = "target_temperature";
    public static final String CHANNEL_TARGET_TEMPERATURE_LOW = CHANNEL_TARGET_TEMPERATURE + "_low";
    public static final String CHANNEL_TARGET_TEMPERATURE_HIGH = CHANNEL_TARGET_TEMPERATURE + "_high";
    public static final String CHANNEL_TARGET_HUMIDITY = "target_humidity";
    public static final String CHANNEL_FAN_MODE = "fan_mode";
    public static final String CHANNEL_CUSTOM_FAN_MODE = "custom_fan_mode";
    public static final String CHANNEL_PRESET = "preset";
    public static final String CHANNEL_CUSTOM_PRESET = "custom_preset";
    public static final String CHANNEL_SWING_MODE = "swing_mode";
    public static final String CHANNEL_CURRENT_TEMPERATURE = "current_temperature";
    public static final String CHANNEL_CURRENT_HUMIDITY = "current_humidity";
    public static final String CHANNEL_MODE = "mode";
    public static final String SEMANTIC_TYPE_SETPOINT = "Setpoint";
    public static final String SEMANTIC_TYPE_CONTROL = "Control";
    public static final String SEMANTIC_TYPE_MEASUREMENT = "Measurement";
    public static final String COMMAND_CLASS_CLIMATE = "Climate";
    public static final String ITEM_TYPE_NUMBER_DIMENSIONLESS = "Number:Dimensionless";
    public static final String ITEM_TYPE_TEMPERATURE = "Number:Temperature";

    private final Logger logger = LoggerFactory.getLogger(ClimateMessageHandler.class);

    private final ReentrantLock lock = new ReentrantLock();

    private final LoadingCache<Integer, ClimateCommandRequest.Builder> commandAggregatingCache;

    private Thread expiryThread = null;

    public ClimateMessageHandler(ESPHomeHandler handler) {
        super(handler);

        commandAggregatingCache = CacheBuilder.newBuilder().maximumSize(10)
                .expireAfterAccess(400, TimeUnit.MILLISECONDS)
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
            ClimateCommandRequest.Builder builder = commandAggregatingCache.get(key);
            String subCommand = (String) channel.getConfiguration().get(BindingConstants.COMMAND_FIELD);
            switch (subCommand) {
                case CHANNEL_MODE ->
                    builder.setMode(ClimateEnumHelper.toClimateMode(command.toString())).setHasMode(true);
                case CHANNEL_TARGET_TEMPERATURE -> {
                    if (command instanceof QuantityType<?> qt) {
                        QuantityType<?> celsius = qt.toUnit(SIUnits.CELSIUS);
                        if (celsius != null) {
                            builder.setTargetTemperature(celsius.floatValue());
                        }
                    } else if (command instanceof DecimalType dc) {
                        builder.setTargetTemperature(dc.floatValue());
                    }
                    builder.setHasTargetTemperature(true);
                }
                case CHANNEL_TARGET_TEMPERATURE_LOW -> {
                    if (command instanceof QuantityType<?> qt) {
                        QuantityType<?> celsius = qt.toUnit(SIUnits.CELSIUS);
                        if (celsius != null) {
                            builder.setTargetTemperatureLow(celsius.floatValue());
                        }
                    } else if (command instanceof DecimalType dc) {
                        builder.setTargetTemperatureLow(dc.floatValue());
                    }
                    builder.setHasTargetTemperatureLow(true);
                }
                case CHANNEL_TARGET_TEMPERATURE_HIGH -> {
                    if (command instanceof QuantityType<?> qt) {
                        QuantityType<?> celsius = qt.toUnit(SIUnits.CELSIUS);
                        if (celsius != null) {
                            builder.setTargetTemperatureHigh(celsius.floatValue());
                        }
                    } else if (command instanceof DecimalType dc) {
                        builder.setTargetTemperatureHigh(dc.floatValue());
                    }
                    builder.setHasTargetTemperatureHigh(true);
                }
                case CHANNEL_TARGET_HUMIDITY -> {
                    if (command instanceof QuantityType<?> qt) {
                        builder.setTargetHumidity(qt.floatValue());
                    } else if (command instanceof DecimalType dc) {
                        builder.setTargetHumidity(dc.floatValue());
                    }
                    builder.setHasTargetHumidity(true);
                }
                case CHANNEL_FAN_MODE ->
                    builder.setFanMode(ClimateEnumHelper.toFanMode(command.toString())).setHasFanMode(true);
                case CHANNEL_CUSTOM_FAN_MODE -> builder.setCustomFanMode(command.toString()).setHasCustomFanMode(true);
                case CHANNEL_PRESET ->
                    builder.setPreset(ClimateEnumHelper.toClimatePreset(command.toString())).setHasPreset(true);
                case CHANNEL_CUSTOM_PRESET -> builder.setCustomPreset(command.toString()).setHasCustomPreset(true);
                case CHANNEL_SWING_MODE -> builder
                        .setSwingMode(ClimateEnumHelper.toClimateSwingMode(command.toString())).setHasSwingMode(true);
                default -> logger.warn("Unknown climate subcommand {}", subCommand);
            }
            // Start a thread that will clean up the cache (send the pending messages)
            if (expiryThread == null || !expiryThread.isAlive()) {
                expiryThread = new Thread(() -> {
                    while (commandAggregatingCache.size() > 0) {
                        try {
                            lock.lock();
                            logger.debug("Calling cleanup");
                            commandAggregatingCache.cleanUp();
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

        if (rsp.getSupportsTwoPointTargetTemperature()) {
            addTargetTemperatureChannel(CHANNEL_TARGET_TEMPERATURE_LOW, "Target temperature (low)", rsp);
            addTargetTemperatureChannel(CHANNEL_TARGET_TEMPERATURE_HIGH, "Target temperature (high)", rsp);
        } else {
            addTargetTemperatureChannel(CHANNEL_TARGET_TEMPERATURE, "Target temperature", rsp);
        }
        if (rsp.getSupportsCurrentTemperature()) {
            ChannelType channelType = addChannelType(rsp.getUniqueId() + CHANNEL_CURRENT_TEMPERATURE,
                    "Current temperature", ITEM_TYPE_TEMPERATURE, Collections.emptyList(), "%.1f %unit%",
                    Set.of(SEMANTIC_TYPE_MEASUREMENT, "Temperature"), true, "temperature",
                    rsp.getVisualCurrentTemperatureStep() == 0f ? null
                            : BigDecimal.valueOf(rsp.getVisualCurrentTemperatureStep()),
                    rsp.getVisualMinTemperature() == 0f ? null
                            : rsp.getVisualMaxTemperature() == 0f ? null
                                    : BigDecimal.valueOf(rsp.getVisualMinTemperature()),
                    BigDecimal.valueOf(rsp.getVisualMaxTemperature()), rsp.getEntityCategory());

            Configuration configuration = configuration(rsp.getKey(), CHANNEL_CURRENT_TEMPERATURE, null);
            configuration.put("unit", "°C"); // ESPHome always uses Celsius for temperature
            Channel channel = ChannelBuilder.create(createChannelUID(rsp.getObjectId(), CHANNEL_CURRENT_TEMPERATURE))
                    .withLabel(createLabel(rsp.getName(), "Current temperature")).withKind(ChannelKind.STATE)
                    .withType(channelType.getUID()).withAcceptedItemType(ITEM_TYPE_TEMPERATURE)
                    .withConfiguration(configuration).build();
            super.registerChannel(channel, channelType);
        }
        if (rsp.getSupportsTargetHumidity()) {
            ChannelType channelTypeTargetHumidity = addChannelType(rsp.getUniqueId() + CHANNEL_TARGET_HUMIDITY,
                    "Target humidity", ITEM_TYPE_NUMBER_DIMENSIONLESS, Collections.emptyList(), "%.0f %unit%",
                    Set.of(SEMANTIC_TYPE_SETPOINT, "Humidity"), false, "humidity", null,
                    BigDecimal.valueOf(rsp.getVisualMinHumidity()), BigDecimal.valueOf(rsp.getVisualMaxHumidity()),
                    rsp.getEntityCategory());

            Channel channelTargetHumidity = ChannelBuilder
                    .create(createChannelUID(rsp.getObjectId(), CHANNEL_TARGET_HUMIDITY))
                    .withLabel(createLabel(rsp.getName(), "Target humidity")).withKind(ChannelKind.STATE)
                    .withType(channelTypeTargetHumidity.getUID()).withAcceptedItemType(ITEM_TYPE_NUMBER_DIMENSIONLESS)
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_TARGET_HUMIDITY, COMMAND_CLASS_CLIMATE))
                    .build();
            super.registerChannel(channelTargetHumidity, channelTypeTargetHumidity);

        }

        if (rsp.getSupportsCurrentHumidity()) {
            ChannelType channelType = addChannelType(rsp.getUniqueId() + CHANNEL_CURRENT_HUMIDITY, "Current humidity",
                    ITEM_TYPE_NUMBER_DIMENSIONLESS, Collections.emptyList(), "%.0f %unit%",
                    Set.of(SEMANTIC_TYPE_MEASUREMENT, "Humidity"), true, "humidity", null, null, null,
                    rsp.getEntityCategory());

            Channel channel = ChannelBuilder.create(createChannelUID(rsp.getObjectId(), CHANNEL_CURRENT_HUMIDITY))
                    .withLabel(createLabel(rsp.getName(), "Current humidity")).withKind(ChannelKind.STATE)
                    .withType(channelType.getUID()).withAcceptedItemType(ITEM_TYPE_NUMBER_DIMENSIONLESS)
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_CURRENT_HUMIDITY, null)).build();
            super.registerChannel(channel, channelType);
        }

        String itemTypeString = "String";
        if (rsp.getSupportedModesCount() > 0) {
            ChannelType channelType = addChannelType(rsp.getUniqueId() + CHANNEL_MODE, "Mode", itemTypeString,
                    rsp.getSupportedModesList().stream().map(ClimateEnumHelper::stripEnumPrefix)
                            .collect(Collectors.toList()),
                    "%s", Set.of(SEMANTIC_TYPE_CONTROL), false, "climate", null, null, null, rsp.getEntityCategory());

            Channel channel = ChannelBuilder.create(createChannelUID(rsp.getObjectId(), CHANNEL_MODE))
                    .withLabel(createLabel(rsp.getName(), "Mode")).withKind(ChannelKind.STATE)
                    .withType(channelType.getUID()).withAcceptedItemType(itemTypeString)
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_MODE, COMMAND_CLASS_CLIMATE)).build();
            super.registerChannel(channel, channelType);
        }
        if (rsp.getSupportedFanModesCount() > 0) {
            ChannelType channelType = addChannelType(rsp.getUniqueId() + CHANNEL_FAN_MODE, "Fan Mode", itemTypeString,
                    rsp.getSupportedFanModesList().stream().map(ClimateEnumHelper::stripEnumPrefix)
                            .collect(Collectors.toList()),
                    "%s", Set.of(SEMANTIC_TYPE_CONTROL, "Wind"), false, "fan", null, null, null,
                    rsp.getEntityCategory());

            Channel channel = ChannelBuilder.create(createChannelUID(rsp.getObjectId(), CHANNEL_FAN_MODE))
                    .withLabel(createLabel(rsp.getName(), "Fan Mode")).withKind(ChannelKind.STATE)
                    .withType(channelType.getUID()).withAcceptedItemType(itemTypeString)
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_FAN_MODE, COMMAND_CLASS_CLIMATE)).build();
            super.registerChannel(channel, channelType);
        }
        if (rsp.getSupportedCustomFanModesCount() > 0) {
            ChannelType channelType = addChannelType(rsp.getUniqueId() + CHANNEL_CUSTOM_FAN_MODE, "Custom Fan Mode",
                    itemTypeString, new ArrayList<>(rsp.getSupportedCustomFanModesList()), "%s",
                    Set.of(SEMANTIC_TYPE_CONTROL, "Wind"), false, "fan", null, null, null, rsp.getEntityCategory());

            Channel channel = ChannelBuilder.create(createChannelUID(rsp.getObjectId(), CHANNEL_CUSTOM_FAN_MODE))
                    .withLabel(createLabel(rsp.getName(), "Custom Fan Mode")).withKind(ChannelKind.STATE)
                    .withType(channelType.getUID()).withAcceptedItemType(itemTypeString)
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_CUSTOM_FAN_MODE, COMMAND_CLASS_CLIMATE))
                    .build();
            super.registerChannel(channel, channelType);
        }
        if (rsp.getSupportedPresetsCount() > 0) {
            ChannelType channelType = addChannelType(rsp.getUniqueId() + CHANNEL_PRESET, "Preset", itemTypeString,
                    rsp.getSupportedPresetsList().stream().map(ClimateEnumHelper::stripEnumPrefix)
                            .collect(Collectors.toList()),
                    "%s", Set.of(SEMANTIC_TYPE_CONTROL), false, "climate", null, null, null, rsp.getEntityCategory());
            Channel channel = ChannelBuilder.create(createChannelUID(rsp.getObjectId(), CHANNEL_PRESET))
                    .withLabel(createLabel(rsp.getName(), "Preset")).withKind(ChannelKind.STATE)
                    .withType(channelType.getUID()).withAcceptedItemType(itemTypeString)
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_PRESET, COMMAND_CLASS_CLIMATE)).build();
            super.registerChannel(channel, channelType);
        }
        if (rsp.getSupportedCustomPresetsCount() > 0) {
            ChannelType channelType = addChannelType(rsp.getUniqueId() + CHANNEL_CUSTOM_PRESET, "Custom Preset",
                    itemTypeString, new ArrayList<>(rsp.getSupportedCustomPresetsList()), "%s",
                    Set.of(SEMANTIC_TYPE_CONTROL), false, "climate", null, null, null, rsp.getEntityCategory());
            Channel channel = ChannelBuilder.create(createChannelUID(rsp.getObjectId(), CHANNEL_CUSTOM_PRESET))
                    .withLabel(createLabel(rsp.getName(), "Custom Preset")).withKind(ChannelKind.STATE)
                    .withType(channelType.getUID()).withAcceptedItemType(itemTypeString)
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_CUSTOM_PRESET, COMMAND_CLASS_CLIMATE))
                    .build();
            super.registerChannel(channel, channelType);
        }
        if (rsp.getSupportedSwingModesCount() > 0) {
            ChannelType channelType = addChannelType(rsp.getUniqueId() + CHANNEL_SWING_MODE, "Swing Mode",
                    itemTypeString,
                    rsp.getSupportedSwingModesList().stream().map(ClimateEnumHelper::stripEnumPrefix)
                            .collect(Collectors.toList()),
                    "%s", Set.of(SEMANTIC_TYPE_CONTROL, "Wind"), false, "fan", null, null, null,
                    rsp.getEntityCategory());
            Channel channel = ChannelBuilder.create(createChannelUID(rsp.getObjectId(), CHANNEL_SWING_MODE))
                    .withAcceptedItemType(itemTypeString).withLabel(createLabel(rsp.getName(), "Swing Mode"))
                    .withKind(ChannelKind.STATE).withType(channelType.getUID())
                    .withConfiguration(configuration(rsp.getKey(), CHANNEL_SWING_MODE, COMMAND_CLASS_CLIMATE)).build();
            super.registerChannel(channel, channelType);
        }
    }

    private void addTargetTemperatureChannel(String channelID, String label, ListEntitiesClimateResponse rsp) {
        ChannelType channelTypeTargetTemperature = addChannelType(rsp.getUniqueId() + channelID, label,
                ITEM_TYPE_TEMPERATURE, Collections.emptyList(), "%.1f %unit%",
                Set.of(SEMANTIC_TYPE_SETPOINT, "Temperature"), false, "temperature",
                rsp.getVisualTargetTemperatureStep() == 0f ? null
                        : BigDecimal.valueOf(rsp.getVisualTargetTemperatureStep()),
                rsp.getVisualMinTemperature() == 0f ? null
                        : rsp.getVisualMaxTemperature() == 0f ? null
                                : BigDecimal.valueOf(rsp.getVisualMinTemperature()),
                BigDecimal.valueOf(rsp.getVisualMaxTemperature()), rsp.getEntityCategory());

        Configuration configuration = configuration(rsp.getKey(), channelID, COMMAND_CLASS_CLIMATE);
        configuration.put("unit", "°C"); // ESPHome always uses Celsius for temperature
        Channel channelTargetTemperatureLow = ChannelBuilder.create(createChannelUID(rsp.getObjectId(), channelID))
                .withLabel(createLabel(rsp.getName(), label)).withKind(ChannelKind.STATE)
                .withType(channelTypeTargetTemperature.getUID()).withAcceptedItemType(ITEM_TYPE_TEMPERATURE)
                .withConfiguration(configuration).build();
        super.registerChannel(channelTargetTemperatureLow, channelTypeTargetTemperature);
    }

    public void handleState(ClimateStateResponse rsp) {
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_TARGET_TEMPERATURE).ifPresent(channel -> handler
                .updateState(channel.getUID(), toNumericState(channel, rsp.getTargetTemperature(), false)));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_TARGET_TEMPERATURE_LOW).ifPresent(channel -> handler
                .updateState(channel.getUID(), toNumericState(channel, rsp.getTargetTemperatureLow(), false)));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_TARGET_TEMPERATURE_HIGH).ifPresent(channel -> handler
                .updateState(channel.getUID(), toNumericState(channel, rsp.getTargetTemperatureHigh(), false)));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_CURRENT_TEMPERATURE).ifPresent(channel -> handler
                .updateState(channel.getUID(), toNumericState(channel, rsp.getCurrentTemperature(), false)));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_MODE).ifPresent(channel -> handler.updateState(channel.getUID(),
                new StringType(ClimateEnumHelper.stripEnumPrefix(rsp.getMode()))));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_FAN_MODE).ifPresent(channel -> handler
                .updateState(channel.getUID(), new StringType(ClimateEnumHelper.stripEnumPrefix(rsp.getFanMode()))));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_CUSTOM_FAN_MODE)
                .ifPresent(channel -> handler.updateState(channel.getUID(), new StringType(rsp.getCustomFanMode())));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_PRESET).ifPresent(channel -> handler
                .updateState(channel.getUID(), new StringType(ClimateEnumHelper.stripEnumPrefix(rsp.getPreset()))));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_CUSTOM_PRESET)
                .ifPresent(channel -> handler.updateState(channel.getUID(), new StringType(rsp.getCustomPreset())));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_SWING_MODE).ifPresent(channel -> handler
                .updateState(channel.getUID(), new StringType(ClimateEnumHelper.stripEnumPrefix(rsp.getSwingMode()))));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_CURRENT_HUMIDITY).ifPresent(channel -> handler
                .updateState(channel.getUID(), toNumericState(channel, rsp.getCurrentHumidity(), false)));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_TARGET_HUMIDITY).ifPresent(channel -> handler
                .updateState(channel.getUID(), toNumericState(channel, rsp.getTargetHumidity(), false)));
    }

    public static class ClimateEnumHelper {
        public static String stripEnumPrefix(ClimateSwingMode mode) {
            String toRemove = "CLIMATE_SWING";
            return mode.toString().substring(toRemove.length() + 1);
        }

        public static String stripEnumPrefix(ClimateFanMode mode) {
            String toRemove = "CLIMATE_FAN";
            return mode.toString().substring(toRemove.length() + 1);
        }

        public static String stripEnumPrefix(ClimateMode climateMode) {
            String toRemove = "CLIMATE_MODE";
            return climateMode.toString().substring(toRemove.length() + 1);
        }

        public static String stripEnumPrefix(ClimatePreset climatePreset) {
            String toRemove = "CLIMATE_PRESET";
            return climatePreset.toString().substring(toRemove.length() + 1);
        }

        public static ClimateFanMode toFanMode(String fanMode) {
            return ClimateFanMode.valueOf("CLIMATE_FAN_" + fanMode.toUpperCase());
        }

        public static ClimatePreset toClimatePreset(String climatePreset) {
            return ClimatePreset.valueOf("CLIMATE_PRESET_" + climatePreset.toUpperCase());
        }

        public static ClimateMode toClimateMode(String mode) {
            return ClimateMode.valueOf("CLIMATE_MODE_" + mode.toUpperCase());
        }

        public static ClimateSwingMode toClimateSwingMode(String mode) {
            return ClimateSwingMode.valueOf("CLIMATE_SWING_" + mode.toUpperCase());
        }
    }
}
