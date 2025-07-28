package no.seime.openhab.binding.esphome.internal.message;

import static org.openhab.core.library.CoreItemFactory.DATETIME;
import static org.openhab.core.library.CoreItemFactory.NUMBER;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

import javax.measure.Unit;
import javax.validation.constraints.NotNull;

import org.eclipse.jdt.annotation.NonNull;
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
import no.seime.openhab.binding.esphome.internal.message.deviceclass.DeviceClass;
import no.seime.openhab.binding.esphome.internal.message.deviceclass.SensorNumberDeviceClass;
import no.seime.openhab.binding.esphome.internal.util.Debug;

public abstract class AbstractMessageHandler<S extends GeneratedMessage, T extends GeneratedMessage> {

    private final Logger logger = LoggerFactory.getLogger(AbstractMessageHandler.class);
    protected final ESPHomeHandler handler;

    protected AbstractMessageHandler(ESPHomeHandler handler) {
        this.handler = handler;
    }

    protected ChannelType addChannelType(final String channelTypePrefix, final String label, final String itemType,
            @Nullable final Set<String> tags, String category, EntityCategory entityCategory,
            boolean disabledByDefault) {
        final ChannelTypeUID channelTypeUID = new ChannelTypeUID(BindingConstants.BINDING_ID,
                channelTypePrefix + handler.getThing().getUID().getId());

        final StateChannelTypeBuilder channelTypeBuilder = ChannelTypeBuilder.state(channelTypeUID, label, itemType);
        if (tags != null && !tags.isEmpty()) {
            channelTypeBuilder.withTags(tags);
        }

        if (category != null) {
            channelTypeBuilder.withCategory(category);
        }

        channelTypeBuilder.withAutoUpdatePolicy(AutoUpdatePolicy.VETO);
        channelTypeBuilder.isAdvanced(disabledByDefault || entityCategory != EntityCategory.ENTITY_CATEGORY_NONE);

        ChannelType channelType = channelTypeBuilder.build();

        logger.trace("[{}] Created new channel type {}", handler.getLogPrefix(), channelType.getUID());

        return channelType;
    }

    protected static StateDescription readOnlyStateDescription() {
        return StateDescriptionFragmentBuilder.create().withReadOnly(true).build().toStateDescription();
    }

    protected static StateDescription patternStateDescription(String pattern) {
        return patternStateDescription(pattern, false);
    }

    protected static StateDescription patternStateDescription(String pattern, boolean readOnly) {
        return StateDescriptionFragmentBuilder.create().withPattern(pattern).withReadOnly(readOnly).build()
                .toStateDescription();
    }

    protected static StateDescription numericStateDescription(@Nullable String pattern, @Nullable BigDecimal step,
            @Nullable BigDecimal min, @Nullable BigDecimal max) {
        return numericStateDescription(pattern, step, min, max, false);
    }

    protected static StateDescription numericStateDescription(@Nullable String pattern, @Nullable BigDecimal step,
            @Nullable BigDecimal min, @Nullable BigDecimal max, boolean readOnly) {
        StateDescriptionFragmentBuilder builder = StateDescriptionFragmentBuilder.create().withReadOnly(readOnly);

        if (pattern != null) {
            builder.withPattern(pattern);
        }

        if (step != null) {
            builder.withStep(step);
        }

        if (min != null) {
            builder.withMinimum(min);
        }

        if (max != null) {
            builder.withMaximum(max);
        }

        return builder.build().toStateDescription();
    }

    protected static StateDescription optionListStateDescription(final Collection<?> options) {
        return optionListStateDescription(options, false);
    }

    protected static StateDescription optionListStateDescription(final Collection<?> options, boolean readOnly) {

        StateDescriptionFragmentBuilder builder = StateDescriptionFragmentBuilder.create().withPattern("%s")
                .withReadOnly(readOnly);

        final List<StateOption> stateOptions = options.stream().map(e -> new StateOption(e.toString(), e.toString()))
                .collect(Collectors.toList());
        builder.withOptions(stateOptions);

        return builder.build().toStateDescription();
    }

    protected static CommandDescription optionListCommandDescription(Collection<?> options) {
        CommandDescriptionBuilder builder = CommandDescriptionBuilder.create();

        final List<CommandOption> commandOptions = options.stream()
                .map(e -> new CommandOption(e.toString(), e.toString())).collect(Collectors.toList());
        builder.withCommandOptions(commandOptions);

        return builder.build();
    }

    protected Configuration configuration(String entityType, int entityKey, String entityField) {
        Configuration configuration = new Configuration();
        configuration.put(BindingConstants.CHANNEL_CONFIGURATION_ENTITY_KEY, entityKey);
        if (entityField != null) {
            configuration.put(BindingConstants.CHANNEL_CONFIGURATION_ENTITY_FIELD, entityField);
        }

        if (entityType != null) {
            configuration.put(BindingConstants.CHANNEL_CONFIGURATION_ENTITY_TYPE, entityType);
        }

        return configuration;
    }

    public abstract void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError;

    public abstract void buildChannels(S rsp);

    protected String resolveNumericItemType(String unit, String name, @NonNull DeviceClass deviceClass) {

        String itemTypeFromUnit = getItemTypeBaseOnUnit(unit);
        String itemTypeToUse;

        if (itemTypeFromUnit != null && deviceClass != null) {
            if (!deviceClass.getItemType().equals(itemTypeFromUnit)) {
                // Verify that unit matches device_class as well
                itemTypeToUse = itemTypeFromUnit;
                if (!deviceClass.isDefault()) {
                    logger.warn(
                            "[{}] Unexpected combination of device_class '{}' and unit '{}' for entity '{}'. Returning item type '{}' based on unit",
                            handler.getLogPrefix(), deviceClass.getDeviceClass(), unit, name, itemTypeToUse);
                }
            } else {
                itemTypeToUse = deviceClass.getItemType();
                logger.debug("[{}] Using item type '{}' based on device_class '{}' and unit '{}'",
                        handler.getLogPrefix(), itemTypeToUse, deviceClass.getDeviceClass(), unit);
            }

        } else if (itemTypeFromUnit != null) {
            itemTypeToUse = itemTypeFromUnit;
            logger.debug(
                    "[{}] Using item type '{}' based on unit '{}' for entity '{}' since device_class is either missing from ESPHome device configuration or openhab mapping is incomplete",
                    handler.getLogPrefix(), itemTypeToUse, unit, name);
        } else {
            itemTypeToUse = deviceClass.getItemType();
            logger.debug("[{}] Using item type '{}' based on device_class '{}' ", handler.getLogPrefix(), itemTypeToUse,
                    deviceClass.getDeviceClass());
        }
        return itemTypeToUse;
    }

    private String getItemTypeBaseOnUnit(String unitOfMeasurement) {

        unitOfMeasurement = transformUnit(unitOfMeasurement);

        Unit<?> unit = UnitUtils.parseUnit(unitOfMeasurement);
        if (unit != null) {
            String dimension = UnitUtils.getDimensionName(unit);
            if (dimension != null) {
                return String.format("%s:%s", NUMBER, dimension);
            }
        }
        return null;
    }

    protected String transformUnit(String unitOfMeasurement) {
        return switch (unitOfMeasurement) {
            case "seconds" -> "s";
            default -> unitOfMeasurement;
        };
    }

    public abstract void handleState(T rsp);

    protected void registerChannel(@NotNull Channel channel, @NotNull ChannelType channelType) {
        registerChannel(channel, channelType, null, null);
    }

    protected void registerChannel(@NotNull Channel channel, @NotNull ChannelType channelType,
            @NotNull StateDescription stateDescription) {
        registerChannel(channel, channelType, stateDescription, null);
    }

    protected void registerChannel(@NotNull Channel channel, @NotNull ChannelType channelType,
            StateDescription stateDescription, CommandDescription commandDescription) {
        if (logger.isDebugEnabled()) {
            logger.debug("[{}] Registering channel {} with channel type {}", handler.getLogPrefix(), channel.getUID(),
                    channelType.getUID());
        }
        if (logger.isTraceEnabled()) {
            logger.trace("[{}] Registering channel: {}", handler.getLogPrefix(), Debug.channelToString(channel));
            logger.trace("[{}] Channel type:        {}", handler.getLogPrefix(),
                    Debug.channelTypeToString(channelType));
        }
        handler.addChannelType(channelType);
        handler.addChannel(channel);
        if (stateDescription != null) {
            handler.addDescription(channel.getUID(), stateDescription);
        }
        if (commandDescription != null) {
            handler.addDescription(channel.getUID(), commandDescription);
        }
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
                    if (sensorDeviceClass.getItemType().startsWith(NUMBER)) {
                        return toNumericState(channel, state);
                    } else if (sensorDeviceClass.getItemType().startsWith(DATETIME)) {
                        return toDateTimeState((int) state, missingState);
                    } else {
                        logger.warn(
                                "[{}] Expected SensorNumberDeviceClass '{}' to be of item type Number[:Dimension]. Returning undef",
                                handler.getLogPrefix(), deviceClass);
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
                return new DecimalType(state);
            }
        } else {
            return new DecimalType(state);
        }
    }

    public Optional<Channel> findChannelByKey(int key) {
        return handler.getThing().getChannels().stream()
                .filter(e -> BigDecimal.valueOf(key)
                        .equals(e.getConfiguration().get(BindingConstants.CHANNEL_CONFIGURATION_ENTITY_KEY)))
                .findFirst();
    }

    public Optional<Channel> findChannelByKeyAndField(int key, String field) {
        return handler.getThing().getChannels().stream().filter(channel -> BigDecimal.valueOf(key)
                .equals(channel.getConfiguration().get(BindingConstants.CHANNEL_CONFIGURATION_ENTITY_KEY))
                && field.equals(channel.getConfiguration().get(BindingConstants.CHANNEL_CONFIGURATION_ENTITY_FIELD)))
                .findFirst();
    }

    public void handleMessage(GeneratedMessage message) {
        if (message.getClass().getSimpleName().startsWith("ListEntities")) {
            buildChannels((S) message);
        } else {

            try {
                handleState((T) message);
            } catch (Exception e) {
                logger.warn("[{}] Error updating OH state", handler.getLogPrefix(), e);
            }
        }
    }

    protected String getChannelIcon(String icon, String defaultCategoryIcon) {
        if (icon.isEmpty()) {
            return defaultCategoryIcon;
        } else {
            return "if:" + icon;
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

    @NonNull
    protected DeviceClass resolveDeviceClassAndSetInConfiguration(Configuration configuration, DeviceClass deviceClass,
            DeviceClass defaultDeviceClass, String rawDeviceClass, String entity, String documentationLink) {

        if (deviceClass == null) {
            logger.warn(
                    "[{}] Device class '{}' unknown, using 'None' for entity '{}'. To get rid of this log message, set 'device_class' attribute to '' or a value from this list: {}",
                    handler.getLogPrefix(), rawDeviceClass, entity, documentationLink);
            deviceClass = defaultDeviceClass;
        }
        if (configuration != null)
            configuration.put("deviceClass", deviceClass.getDeviceClass());
        return defaultDeviceClass;
    }

    protected Set<String> createSemanticTags(String point, DeviceClass deviceClass) {
        Set<String> semanticTags = new HashSet<>();
        semanticTags.add(point);
        if (deviceClass.getSemanticType() != null) {
            semanticTags.add(deviceClass.getSemanticType());
        }

        return semanticTags;
    }
}
