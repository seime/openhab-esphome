package no.seime.openhab.binding.esphome.internal.message;

import static org.openhab.core.library.CoreItemFactory.STRING;
import static org.openhab.core.library.CoreItemFactory.SWITCH;

import java.math.BigDecimal;
import java.util.Set;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.*;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.StateDescription;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.*;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.EntityTypes;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;
import no.seime.openhab.binding.esphome.internal.message.deviceclass.CoverDeviceClass;
import no.seime.openhab.binding.esphome.internal.message.deviceclass.DeviceClass;

public class CoverMessageHandler extends AbstractMessageHandler<ListEntitiesCoverResponse, CoverStateResponse> {

    public static final String CHANNEL_POSITION = "position";
    public static final String CHANNEL_TILT = "tilt";
    public static final String CHANNEL_CURRENT_OPERATION = "current_operation";
    public static final String CHANNEL_TOGGLE = "toggle";
    public static final String CONFIG_SUPPORTS_STOP_TILT = "supportsStopTilt";

    private final Logger logger = LoggerFactory.getLogger(CoverMessageHandler.class);

    public CoverMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    public static String stripEnumPrefix(CoverOperation climatePreset) {
        String toRemove = "COVER_OPERATION";
        return climatePreset.toString().substring(toRemove.length() + 1);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        CoverCommandRequest.Builder builder = CoverCommandRequest.newBuilder().setKey(key);
        String subCommand = (String) channel.getConfiguration()
                .get(BindingConstants.CHANNEL_CONFIGURATION_ENTITY_FIELD);

        if (command == StopMoveType.STOP) {
            if (CHANNEL_TILT.equals(subCommand) && supportsStopTilt(channel)) {
                // Stop only the tilt movement, leave a running open/close alone
                builder.setStopTilt(true);
            } else {
                builder.setStop(true);
                builder.setLegacyCommand(LegacyCoverCommand.LEGACY_COVER_COMMAND_STOP);
            }
        } else {
            switch (subCommand) {
                case CHANNEL_POSITION -> {
                    if (command instanceof QuantityType<?> number) {
                        builder.setPosition(invert(number.floatValue() / 100));
                    } else if (command instanceof PercentType number) {
                        builder.setPosition(invert(number.floatValue() / 100));
                    } else if (command instanceof DecimalType number) {
                        builder.setPosition(invert(number.floatValue() / 100));
                    } else if (command == UpDownType.UP) {
                        builder.setHasLegacyCommand(true);
                        builder.setLegacyCommand(LegacyCoverCommand.LEGACY_COVER_COMMAND_OPEN);
                        builder.setPosition(1);
                    } else if (command == UpDownType.DOWN) {
                        builder.setHasLegacyCommand(true);
                        builder.setLegacyCommand(LegacyCoverCommand.LEGACY_COVER_COMMAND_CLOSE);
                        builder.setPosition(0);
                    }
                    builder.setHasPosition(true);
                }
                case CHANNEL_TILT -> {
                    if (command instanceof QuantityType<?> number) {
                        builder.setTilt(invert(number.floatValue() / 100));
                    } else if (command instanceof PercentType number) {
                        builder.setTilt(invert(number.floatValue() / 100));
                    } else if (command instanceof DecimalType number) {
                        builder.setTilt(invert(number.floatValue() / 100));
                    } else if (command == UpDownType.UP) {
                        builder.setTilt(1);
                    } else if (command == UpDownType.DOWN) {
                        builder.setTilt(0);
                    }
                    builder.setHasTilt(true);
                }

                case CHANNEL_TOGGLE -> {
                    if (command instanceof OnOffType) {
                        // Behaves like a push button, the cover reports the resulting movement
                        handler.updateState(channel.getUID(), OnOffType.OFF);
                        builder.setToggle(true);
                    } else {
                        logger.warn("[{}] Unsupported command type for cover toggle: {}, use OnOffType instead",
                                handler.getLogPrefix(), command);
                        return;
                    }
                }
                case CHANNEL_CURRENT_OPERATION -> logger.warn("current_operation channel is read-only");
                default -> logger.warn("[{}] Unknown Cover subcommand {}", handler.getLogPrefix(), subCommand);
            }
        }
        handler.sendMessage(builder.build());
    }

    public void buildChannels(ListEntitiesCoverResponse rsp) {

        DeviceClass deviceClass = resolveDeviceClassAndSetInConfiguration(null,
                CoverDeviceClass.fromDeviceClass(rsp.getDeviceClass()), CoverDeviceClass.NONE, rsp.getDeviceClass(),
                rsp.getName(), "https://www.home-assistant.io/integrations/cover/#device-class");

        String icon = getChannelIcon(rsp.getIcon(), deviceClass.getCategory());

        Set<String> semanticTags = createSemanticTags("OpenLevel", deviceClass);

        ChannelType channelTypePosition = addChannelType("Position", deviceClass.getItemType(), semanticTags, icon,
                rsp.getEntityCategory(), rsp.getDisabledByDefault());
        StateDescription stateDescription = patternStateDescription("%d %%");

        Channel channelPosition = ChannelBuilder
                .create(createChannelUID(rsp.getName(), EntityTypes.COVER, CHANNEL_POSITION))
                .withLabel(createChannelLabel(rsp.getName(), "Position")).withKind(ChannelKind.STATE)
                .withType(channelTypePosition.getUID()).withAcceptedItemType(deviceClass.getItemType())
                .withConfiguration(configuration(EntityTypes.COVER, rsp.getKey(), CHANNEL_POSITION)).build();
        super.registerChannel(channelPosition, channelTypePosition, stateDescription);

        if (rsp.getSupportsTilt()) {
            semanticTags = createSemanticTags("Tilt", deviceClass);

            ChannelType channelTypeTilt = addChannelType("Tilt", deviceClass.getItemType(), semanticTags, icon,
                    rsp.getEntityCategory(), rsp.getDisabledByDefault());
            stateDescription = patternStateDescription("%d %%");

            Configuration tiltConfiguration = configuration(EntityTypes.COVER, rsp.getKey(), CHANNEL_TILT);
            tiltConfiguration.put(CONFIG_SUPPORTS_STOP_TILT, rsp.getSupportsStopTilt());

            Channel channelTilt = ChannelBuilder
                    .create(createChannelUID(rsp.getName(), EntityTypes.COVER, CHANNEL_TILT))
                    .withLabel(createChannelLabel(rsp.getName(), "Tilt")).withKind(ChannelKind.STATE)
                    .withType(channelTypeTilt.getUID()).withAcceptedItemType(deviceClass.getItemType())
                    .withConfiguration(tiltConfiguration).build();
            super.registerChannel(channelTilt, channelTypeTilt, stateDescription);
        }

        if (rsp.getSupportsToggle()) {
            ChannelType channelTypeToggle = addChannelType("Toggle", SWITCH, createSemanticTags("Switch", deviceClass),
                    icon, rsp.getEntityCategory(), rsp.getDisabledByDefault());

            Channel channelToggle = ChannelBuilder
                    .create(createChannelUID(rsp.getName(), EntityTypes.COVER, CHANNEL_TOGGLE))
                    .withLabel(createChannelLabel(rsp.getName(), "Toggle")).withKind(ChannelKind.STATE)
                    .withType(channelTypeToggle.getUID()).withAcceptedItemType(SWITCH)
                    .withConfiguration(configuration(EntityTypes.COVER, rsp.getKey(), CHANNEL_TOGGLE)).build();
            super.registerChannel(channelToggle, channelTypeToggle);
        }

        // Operation status
        ChannelType channelTypeCurrentOperation = addChannelType("Current operation", STRING,
                createSemanticTags("Status", deviceClass), "motion", rsp.getEntityCategory(),
                rsp.getDisabledByDefault());
        stateDescription = optionListStateDescription(Set.of("IDLE", "IS_OPENING", "IS_CLOSING"), true);

        Channel channelCurrentOperation = ChannelBuilder
                .create(createChannelUID(rsp.getName(), EntityTypes.COVER, CHANNEL_CURRENT_OPERATION))
                .withLabel(createChannelLabel(rsp.getName(), "Current operation")).withKind(ChannelKind.STATE)
                .withType(channelTypeCurrentOperation.getUID()).withAcceptedItemType(STRING)
                .withConfiguration(configuration(EntityTypes.COVER, rsp.getKey(), CHANNEL_CURRENT_OPERATION)).build();
        super.registerChannel(channelCurrentOperation, channelTypeCurrentOperation, stateDescription);
    }

    public void handleState(CoverStateResponse rsp) {
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_POSITION).ifPresent(
                channel -> handler.updateState(channel.getUID(), toPercentState(invert(rsp.getPosition()), false)));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_TILT).ifPresent(
                channel -> handler.updateState(channel.getUID(), toPercentState(invert(rsp.getTilt()), false)));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_CURRENT_OPERATION).ifPresent(channel -> handler
                .updateState(channel.getUID(), new StringType(stripEnumPrefix(rsp.getCurrentOperation()))));
    }

    protected State toPercentState(float state, boolean missingState) {
        if (missingState || Float.isNaN(state)) {
            return UnDefType.UNDEF;
        } else {
            return new PercentType(BigDecimal.valueOf(state * 100));
        }
    }

    private static boolean supportsStopTilt(Channel channel) {
        // Channels created before stop_tilt support lack the property, they keep the old stop-everything behaviour
        return Boolean.parseBoolean(String.valueOf(channel.getConfiguration().get(CONFIG_SUPPORTS_STOP_TILT)));
    }

    private float invert(float value) {
        return 1f - value;
    }
}
