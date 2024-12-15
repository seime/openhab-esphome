package no.seime.openhab.binding.esphome.internal.message;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.measure.Unit;
import javax.validation.constraints.NotNull;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.type.*;
import org.openhab.core.types.*;
import org.openhab.core.types.util.UnitUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.GeneratedMessage;

import io.esphome.api.EntityCategory;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;
import no.seime.openhab.binding.esphome.internal.util.Debug;

public abstract class AbstractMessageHandler<S extends GeneratedMessage, T extends GeneratedMessage> {

    private final Logger logger = LoggerFactory.getLogger(AbstractMessageHandler.class);
    protected final ESPHomeHandler handler;

    protected AbstractMessageHandler(ESPHomeHandler handler) {
        this.handler = handler;
    }

    protected ChannelType addChannelType(final String channelTypePrefix, final String label, final String itemType,
            final Collection<?> options, @Nullable final String pattern, @Nullable final Set<String> tags,
            boolean readOnly, String category, BigDecimal stateDescriptionStep, BigDecimal stateDescriptionMin,
            BigDecimal stateDescriptionMax, EntityCategory entityCategory) {
        final ChannelTypeUID channelTypeUID = new ChannelTypeUID(BindingConstants.BINDING_ID,
                channelTypePrefix + handler.getThing().getUID().getId());
        final List<StateOption> stateOptions = options.stream().map(e -> new StateOption(e.toString(), e.toString()))
                .collect(Collectors.toList());

        StateDescriptionFragmentBuilder stateDescription = StateDescriptionFragmentBuilder.create()
                .withReadOnly(readOnly).withOptions(stateOptions);
        if (pattern != null) {
            stateDescription = stateDescription.withPattern(pattern);
        }

        if (stateDescriptionStep != null) {
            stateDescription = stateDescription.withStep(stateDescriptionStep);
        }

        if (stateDescriptionMin != null) {
            stateDescription = stateDescription.withMinimum(stateDescriptionMin);
        }

        if (stateDescriptionMax != null) {
            stateDescription = stateDescription.withMaximum(stateDescriptionMax);
        }

        final StateChannelTypeBuilder channelTypeBuilder = ChannelTypeBuilder.state(channelTypeUID, label, itemType)
                .withStateDescriptionFragment(stateDescription.build());
        if (tags != null && !tags.isEmpty()) {
            channelTypeBuilder.withTags(tags);
        }

        if (category != null) {
            channelTypeBuilder.withCategory(category);
        }

        channelTypeBuilder.withAutoUpdatePolicy(AutoUpdatePolicy.VETO);
        channelTypeBuilder.isAdvanced(entityCategory != EntityCategory.ENTITY_CATEGORY_NONE);

        ChannelType channelType = channelTypeBuilder.build();

        logger.trace("Created new channel type {}", channelType.getUID());

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

    protected String resolveNumericItemType(String unitOfMeasurement, String name,
            SensorNumberDeviceClass deviceClass) {

        String itemTypeFromUnit = getItemTypeBaseOnUnit(unitOfMeasurement);

        String itemTypeToUse;

        if (itemTypeFromUnit != null && deviceClass != null) {
            if (!deviceClass.getItemType().equals(itemTypeFromUnit)) {
                // Verify that unit matches device_class as well
                itemTypeToUse = itemTypeFromUnit;
                logger.warn(
                        "Unexpected combination of device_class '{}' and unit '{}'. Returning item type '{}' based on unit",
                        deviceClass.getDeviceClass(), unitOfMeasurement, itemTypeToUse);

            } else {
                itemTypeToUse = deviceClass.getItemType();
                logger.debug("Using item type '{}' based on device_class '{}' and unit '{}'", itemTypeToUse,
                        deviceClass.getDeviceClass(), unitOfMeasurement);
            }

        } else if (itemTypeFromUnit != null) {
            itemTypeToUse = itemTypeFromUnit;
            logger.debug(
                    "Using item type '{}' based on unit '{}' since device_class is either missing from ESPHome device configuration or openhab mapping is incomplete",
                    itemTypeToUse, unitOfMeasurement);
        } else if (deviceClass != null) {
            itemTypeToUse = deviceClass.getItemType();
            logger.debug("Using item type '{}' based on device_class '{}' ", itemTypeToUse,
                    deviceClass.getDeviceClass());
        } else {
            logger.info(
                    "Could not determine item type for sensor '{}' as neither device_class nor unit_of_measurement is present. Consider augmenting your ESPHome configuration. Using default 'Number'",
                    name);
            itemTypeToUse = "Number";
        }
        return itemTypeToUse;
    }

    private String getItemTypeBaseOnUnit(String unitOfMeasurement) {

        unitOfMeasurement = transformUnit(unitOfMeasurement);

        Unit<?> unit = UnitUtils.parseUnit(unitOfMeasurement);
        if (unit != null) {
            String dimensionName = UnitUtils.getDimensionName(unit);
            if (dimensionName != null) {
                return "Number:" + dimensionName;
            }
        }
        return null;
    }

    private String transformUnit(String unitOfMeasurement) {
        return switch (unitOfMeasurement) {
            case "seconds" -> "s";
            default -> unitOfMeasurement;
        };
    }

    public abstract void handleState(T rsp);

    protected void registerChannel(@NotNull Channel channel, @NotNull ChannelType channelType) {
        if (logger.isDebugEnabled()) {
            logger.debug("Registering channel {} with channel type {}", channel.getUID(), channelType.getUID());
        }
        if (logger.isTraceEnabled()) {
            logger.trace("Registering channel: {}", Debug.channelToString(channel));
            logger.trace("Channel type:        {}", Debug.channelTypeToString(channelType));
        }
        handler.addChannelType(channelType);
        handler.addChannel(channel);
    }

    protected State toNumericState(Channel channel, float state, boolean missingState) {
        if (missingState || Float.isNaN(state)) {
            return UnDefType.UNDEF;
        } else {
            Configuration configuration = channel.getConfiguration();
            String deviceClass = (String) configuration.get("deviceClass");
            if (deviceClass != null) {
                SensorNumberDeviceClass sensorDeviceClass = SensorNumberDeviceClass.fromDeviceClass(deviceClass);
                if (sensorDeviceClass != null) {
                    if (sensorDeviceClass.getItemType().startsWith("Number")) {
                        return toNumericState(channel, state);
                    } else if (sensorDeviceClass.getItemType().startsWith("DateTime")) {
                        return toDateTimeState((int) state, missingState);
                    } else {
                        logger.warn(
                                "Expected SensorNumberDeviceClass '{}' to be of item type Number[:Dimension]. Returning undef",
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
            unitString = transformUnit(unitString);
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
        return handler.getThing().getChannels().stream().filter(
                channel -> BigDecimal.valueOf(key).equals(channel.getConfiguration().get(BindingConstants.COMMAND_KEY))
                        && field.equals(channel.getConfiguration().get(BindingConstants.COMMAND_FIELD)))
                .findFirst();
    }

    public void handleMessage(GeneratedMessage message) {
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

    protected String getChannelIcon(String icon, String defaultCategoryIcon) {
        if (icon.isEmpty()) {
            return defaultCategoryIcon;
        } else {
            return icon.replace(":", "_"); // ie mdi:thermometer -> mdi_thermometer
        }
    }

    protected String createLabel(String componentName, String channelName) {
        return String.format("%s %s", componentName, channelName).trim();
    }

    protected ChannelUID createChannelUID(String componentName, String channelName) {
        return new ChannelUID(handler.getThing().getUID(), String.format("%s#%s", componentName, channelName));
    }

    protected State toDateTimeState(int epochSeconds, boolean missingState) {
        if (missingState) {
            return UnDefType.UNDEF;
        } else {
            return new DateTimeType(
                    ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneId.systemDefault()));
        }
    }
}
