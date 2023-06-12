package no.seime.openhab.binding.esphome.internal.message;

import java.util.Collections;
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

import io.esphome.api.ClimateCommandRequest;
import io.esphome.api.ClimateStateResponse;
import io.esphome.api.ListEntitiesClimateResponse;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.EnumHelper;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class ClimateMessageHandler extends AbstractMessageHandler<ListEntitiesClimateResponse, ClimateStateResponse> {

    public ClimateMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        ClimateCommandRequest.Builder builder = ClimateCommandRequest.newBuilder().setKey(key);
        String subCommand = (String) channel.getConfiguration().get(BindingConstants.COMMAND_FIELD);
        switch (subCommand) {
            case "mode":
                builder.setMode(EnumHelper.toClimateMode(command.toString())).setHasMode(true);
                break;
            case "target_temperature":
                if (command instanceof QuantityType<?> qt) {
                    builder.setTargetTemperature(qt.floatValue());
                } else if (command instanceof DecimalType dc) {
                    builder.setTargetTemperature(dc.floatValue());
                }

                builder.setHasTargetTemperature(true);
                break;
            case "fan_mode":
                builder.setFanMode(EnumHelper.toFanMode(command.toString())).setHasFanMode(true);
                break;
            case "custom_fan_mode":
                builder.setCustomFanMode(command.toString()).setHasCustomFanMode(true);
                break;
            case "preset":
                builder.setPreset(EnumHelper.toClimatePreset(command.toString())).setHasPreset(true);
                break;
            case "custom_preset":
                builder.setCustomPreset(command.toString()).setHasCustomPreset(true);
                break;
            case "swing_mode":
                builder.setSwingMode(EnumHelper.toClimateSwingMode(command.toString())).setHasSwingMode(true);
                break;
        }

        handler.sendMessage(builder.build());
    }

    public void buildChannels(ListEntitiesClimateResponse rsp) {

        ChannelType channelTypeTargetTemperature = addChannelType(
                rsp.getObjectId() + "_" + BindingConstants.CHANNEL_NAME_TARGET_TEMPERATURE, "Target temperature",
                "Number:Temperature", Collections.emptyList(), "%.1f", null);

        Channel channelTargetTemperature = ChannelBuilder
                .create(new ChannelUID(handler.getThing().getUID(), BindingConstants.CHANNEL_NAME_TARGET_TEMPERATURE))
                .withLabel("Target temperature").withKind(ChannelKind.STATE)
                .withType(channelTypeTargetTemperature.getUID())
                .withConfiguration(configuration(rsp.getKey(), "target_temperature", "Climate")).build();
        super.registerChannel(channelTargetTemperature, channelTypeTargetTemperature);

        if (rsp.getSupportsCurrentTemperature()) {
            ChannelType channelType = addChannelType(
                    rsp.getObjectId() + "_" + BindingConstants.CHANNEL_NAME_CURRENT_TEMPERATURE, "Current temperature",
                    "Number:Temperature", Collections.emptyList(), "%.1f", null);

            Channel channel = ChannelBuilder
                    .create(new ChannelUID(handler.getThing().getUID(),
                            BindingConstants.CHANNEL_NAME_CURRENT_TEMPERATURE))
                    .withLabel("Current temperature").withKind(ChannelKind.STATE).withType(channelType.getUID())
                    .withConfiguration(configuration(rsp.getKey(), "current_temperature", null)).build();
            super.registerChannel(channel, channelType);
        }

        if (rsp.getSupportedModesCount() > 0) {
            ChannelType channelType = addChannelType(rsp.getObjectId() + "_" + BindingConstants.CHANNEL_NAME_MODE,
                    "Mode", "String", rsp.getSupportedModesList().stream().map(val -> EnumHelper.stripEnumPrefix(val))
                            .collect(Collectors.toList()),
                    "%s", null);

            Channel channel = ChannelBuilder
                    .create(new ChannelUID(handler.getThing().getUID(), BindingConstants.CHANNEL_NAME_MODE))
                    .withLabel("Mode").withKind(ChannelKind.STATE).withType(channelType.getUID())
                    .withConfiguration(configuration(rsp.getKey(), "mode", "Climate")).build();
            super.registerChannel(channel, channelType);
        }
        if (rsp.getSupportedFanModesCount() > 0) {
            ChannelType channelType = addChannelType(BindingConstants.CHANNEL_NAME_FAN_MODE, "Fan Mode", "String",
                    rsp.getSupportedFanModesList().stream().map(val -> EnumHelper.stripEnumPrefix(val)).toList(), "%s",
                    null);

            Channel channel = ChannelBuilder
                    .create(new ChannelUID(handler.getThing().getUID(), BindingConstants.CHANNEL_NAME_FAN_MODE))
                    .withLabel("Fan Mode").withKind(ChannelKind.STATE).withType(channelType.getUID())
                    .withConfiguration(configuration(rsp.getKey(), "fan_mode", "Climate")).build();
            super.registerChannel(channel, channelType);
        }
        if (rsp.getSupportedCustomFanModesCount() > 0) {
            ChannelType channelType = addChannelType(BindingConstants.CHANNEL_NAME_CUSTOM_FAN_MODE, "Custom Fan Mode",
                    "String", rsp.getSupportedCustomFanModesList().stream().toList(), "%s", null);

            Channel channel = ChannelBuilder
                    .create(new ChannelUID(handler.getThing().getUID(), BindingConstants.CHANNEL_NAME_CUSTOM_FAN_MODE))
                    .withLabel("Custom Fan Mode").withKind(ChannelKind.STATE).withType(channelType.getUID())
                    .withConfiguration(configuration(rsp.getKey(), "custom_fan_mode", "Climate")).build();
            super.registerChannel(channel, channelType);
        }
        if (rsp.getSupportedPresetsCount() > 0) {
            ChannelType channelType = addChannelType(rsp.getObjectId() + "_" + BindingConstants.CHANNEL_NAME_PRESET,
                    "Preset", "String",
                    rsp.getSupportedPresetsList().stream().map(val -> EnumHelper.stripEnumPrefix(val)).toList(), "%s",
                    null);
            Channel channel = ChannelBuilder
                    .create(new ChannelUID(handler.getThing().getUID(), BindingConstants.CHANNEL_NAME_PRESET))
                    .withLabel("Preset").withKind(ChannelKind.STATE).withType(channelType.getUID())
                    .withConfiguration(configuration(rsp.getKey(), "preset", "Climate")).build();
            super.registerChannel(channel, channelType);
        }
        if (rsp.getSupportedCustomPresetsCount() > 0) {
            ChannelType channelType = addChannelType(
                    rsp.getObjectId() + "_" + BindingConstants.CHANNEL_NAME_CUSTOM_PRESET, "Custom Preset", "String",
                    rsp.getSupportedCustomPresetsList().stream().toList(), "%s", null);
            Channel channel = ChannelBuilder
                    .create(new ChannelUID(handler.getThing().getUID(), BindingConstants.CHANNEL_NAME_CUSTOM_PRESET))
                    .withLabel("Custom Preset").withKind(ChannelKind.STATE).withType(channelType.getUID())
                    .withConfiguration(configuration(rsp.getKey(), "custom_preset", "Climate")).build();
            super.registerChannel(channel, channelType);
        }
        if (rsp.getSupportedSwingModesCount() > 0) {
            ChannelType channelType = addChannelType(rsp.getObjectId() + "_" + BindingConstants.CHANNEL_NAME_SWING_MODE,
                    "Swing Mode", "String",
                    rsp.getSupportedSwingModesList().stream().map(val -> EnumHelper.stripEnumPrefix(val)).toList(),
                    "%s", null);
            Channel channel = ChannelBuilder
                    .create(new ChannelUID(handler.getThing().getUID(), BindingConstants.CHANNEL_NAME_SWING_MODE))
                    .withLabel("Swing Mode").withKind(ChannelKind.STATE).withType(channelType.getUID())
                    .withConfiguration(configuration(rsp.getKey(), "swing_mode", "Climate")).build();
            super.registerChannel(channel, channelType);
        }
    }

    public void handleState(ClimateStateResponse rsp) {
        findChannelByKeyAndField(rsp.getKey(), "target_temperature").ifPresent(channel -> handler
                .updateState(channel.getUID(), new QuantityType<>(rsp.getTargetTemperature(), SIUnits.CELSIUS)));
        findChannelByKeyAndField(rsp.getKey(), "current_temperature").ifPresent(channel -> handler
                .updateState(channel.getUID(), new QuantityType<>(rsp.getCurrentTemperature(), SIUnits.CELSIUS)));
        findChannelByKeyAndField(rsp.getKey(), "mode").ifPresent(channel -> handler.updateState(channel.getUID(),
                new StringType(EnumHelper.stripEnumPrefix(rsp.getMode()))));
        findChannelByKeyAndField(rsp.getKey(), "fan_mode").ifPresent(channel -> handler.updateState(channel.getUID(),
                new StringType(EnumHelper.stripEnumPrefix(rsp.getFanMode()))));
        findChannelByKeyAndField(rsp.getKey(), "custom_fan_mode")
                .ifPresent(channel -> handler.updateState(channel.getUID(), new StringType(rsp.getCustomFanMode())));

        findChannelByKeyAndField(rsp.getKey(), "preset").ifPresent(channel -> handler.updateState(channel.getUID(),
                new StringType(EnumHelper.stripEnumPrefix(rsp.getPreset()))));
        findChannelByKeyAndField(rsp.getKey(), "custom_preset")
                .ifPresent(channel -> handler.updateState(channel.getUID(), new StringType(rsp.getCustomPreset())));
        findChannelByKeyAndField(rsp.getKey(), "swing_mode").ifPresent(channel -> handler.updateState(channel.getUID(),
                new StringType(EnumHelper.stripEnumPrefix(rsp.getSwingMode()))));
    }
}
