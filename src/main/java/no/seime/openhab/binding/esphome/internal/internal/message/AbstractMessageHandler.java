package no.seime.openhab.binding.esphome.internal.internal.message;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.measure.Unit;
import javax.validation.constraints.NotNull;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.type.AutoUpdatePolicy;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.thing.type.ChannelTypeBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.thing.type.StateChannelTypeBuilder;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.StateDescriptionFragmentBuilder;
import org.openhab.core.types.StateOption;
import org.openhab.core.types.UnDefType;
import org.openhab.core.types.util.UnitUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.GeneratedMessageV3;

import no.seime.openhab.binding.esphome.internal.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.internal.handler.ESPHomeHandler;

public abstract class AbstractMessageHandler<S extends GeneratedMessageV3, T extends GeneratedMessageV3> {

    private final Logger logger = LoggerFactory.getLogger(AbstractMessageHandler.class);
    protected final ESPHomeHandler handler;

    protected AbstractMessageHandler(ESPHomeHandler handler) {
        this.handler = handler;
    }

    protected ChannelType addChannelType(final String channelTypePrefix, final String label, final String itemType,
            final Collection<?> options, @Nullable final String pattern, @Nullable final Set<String> tags,
            boolean readOnly, String category) {
        final ChannelTypeUID channelTypeUID = new ChannelTypeUID(BindingConstants.BINDING_ID,
                channelTypePrefix + handler.getThing().getUID().getId());
        final List<StateOption> stateOptions = options.stream().map(e -> new StateOption(e.toString(), e.toString()))
                .collect(Collectors.toList());

        StateDescriptionFragmentBuilder stateDescription = StateDescriptionFragmentBuilder.create()
                .withReadOnly(readOnly).withOptions(stateOptions);
        if (pattern != null) {
            stateDescription = stateDescription.withPattern(pattern);
        }
        final StateChannelTypeBuilder channelTypeBuilder = ChannelTypeBuilder.state(channelTypeUID, label, itemType)
                .withStateDescriptionFragment(stateDescription.build());
        if (tags != null && !tags.isEmpty()) {
            channelTypeBuilder.withTags(tags);
        }

        if (category != null) {
            channelTypeBuilder.withCategory(category);
        }
        ChannelType channelType = channelTypeBuilder.build();

        logger.trace("Created new channel type {}", channelType.getUID());

        channelTypeBuilder.withAutoUpdatePolicy(AutoUpdatePolicy.VETO);

        return channelType;
    }

    protected Configuration configuration(int key, String subCommand, String commandClass) {
        Configuration configuration = new Configuration();
        configuration.put(BindingConstants.COMMAND_KEY, key);
        if (subCommand != null) {
            configuration.put(BindingConstants.COMMAND_FIELD, subCommand);
        }

        if (commandClass != null) {
            configuration.put(BindingConstants.COMMAND_CLASS, commandClass);
        }

        return configuration;
    }

    public abstract void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError;

    public abstract void buildChannels(S rsp);

    public abstract void handleState(T rsp);

    protected void registerChannel(@NotNull Channel channel, @NotNull ChannelType channelType) {
        logger.trace("Registering channel {} with channel type {}", channel.getUID(), channelType.getUID());
        handler.addChannelType(channelType);
        handler.addChannel(channel);
    }

    protected State toNumericState(Channel channel, float state, boolean missingState) {
        if (missingState) {
            return UnDefType.UNDEF;
        } else {
            Configuration configuration = channel.getConfiguration();
            String deviceClass = (String) configuration.get("deviceClass");
            if (deviceClass != null) {
                SensorDeviceClass sensorDeviceClass = SensorDeviceClass.fromDeviceClass(deviceClass);
                if (sensorDeviceClass != null) {
                    if (sensorDeviceClass.getItemType().startsWith("Number")) {
                        return toNumericState(channel, state);
                    } else {
                        logger.warn(
                                "Expected SensorDeviceClass '{}' to be of item type Number[:Dimension]. Returning undef",
                                deviceClass);
                        return UnDefType.UNDEF;
                    }
                } else {
                    return toNumericState(channel, state);
                }
            } else {
                return toNumericState(channel, state);
            }
        }
    }

    private State toNumericState(Channel channel, float state) {

        Configuration configuration = channel.getConfiguration();
        String unitString = (String) configuration.get("unit");
        if (unitString != null) {
            /*
             * if ("%".equals(unitString)) {
             * // TODO PercentType does not seem to work well
             * return new DecimalType(state);
             * }
             */
            Unit<?> unit = UnitUtils.parseUnit(unitString);
            if (unit != null) {
                return new QuantityType<>(state, unit);
            } else {
                logger.warn("Unit '{}' unknown to openHAB, returning DecimalType for state '{}' on channel '{}'",
                        unitString, state, channel.getUID());
                return new DecimalType(state);

            }
        } else {
            return new DecimalType(state);
        }
    }

    public Optional<Channel> findChannelByKey(int key) {
        return handler.getThing().getChannels().stream()
                .filter(e -> BigDecimal.valueOf(key).equals(e.getConfiguration().get(BindingConstants.COMMAND_KEY)))
                .findFirst();
    }

    public Optional<Channel> findChannelByKeyAndField(int key, String field) {
        return handler.getThing().getChannels().stream()
                .filter(e -> BigDecimal.valueOf(key).equals(e.getConfiguration().get(BindingConstants.COMMAND_KEY))
                        && field.equals(e.getConfiguration().get(BindingConstants.COMMAND_FIELD)))
                .findFirst();
    }

    public void handleMessage(GeneratedMessageV3 message) {
        if (message.getClass().getSimpleName().startsWith("ListEntities")) {
            buildChannels((S) message);
        } else {

            try {
                handleState((T) message);
            } catch (Exception e) {
                logger.warn("Error updating OH state", e);
            }
        }
    }
}
