package no.seime.openhab.binding.esphome.internal.message;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.thing.type.ChannelTypeBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.thing.type.StateChannelTypeBuilder;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.StateDescriptionFragmentBuilder;
import org.openhab.core.types.StateOption;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.GeneratedMessageV3;

import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public abstract class AbstractMessageHandler<S extends GeneratedMessageV3, T extends GeneratedMessageV3> {

    protected ESPHomeHandler handler;

    private final Logger logger = LoggerFactory.getLogger(AbstractMessageHandler.class);

    public AbstractMessageHandler(ESPHomeHandler handler) {
        this.handler = handler;
    }

    protected ChannelType addChannelType(final String channelTypePrefix, final String label, final String itemType,
            final Collection<?> options, @Nullable final String pattern, @Nullable final Set<String> tags) {
        final ChannelTypeUID channelTypeUID = new ChannelTypeUID(BindingConstants.BINDING_ID,
                channelTypePrefix + handler.getThing().getUID().getId());
        final List<StateOption> stateOptions = options.stream().map(e -> new StateOption(e.toString(), e.toString()))
                .collect(Collectors.toList());

        StateDescriptionFragmentBuilder stateDescription = StateDescriptionFragmentBuilder.create().withReadOnly(false)
                .withOptions(stateOptions);
        if (pattern != null) {
            stateDescription = stateDescription.withPattern(pattern);
        }
        final StateChannelTypeBuilder builder = ChannelTypeBuilder.state(channelTypeUID, label, itemType)
                .withStateDescriptionFragment(stateDescription.build());
        if (tags != null && !tags.isEmpty()) {
            builder.withTags(tags);
        }
        final ChannelType channelType = builder.build();

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

    protected void registerChannel(Channel channel, ChannelType channelType) {
        if (channelType != null) {
            handler.addChannelType(channelType);
        }
        handler.addChannel(channel);
    }

    protected String channelType(String objectId) {
        switch (objectId) {
            case "humidity":
                return BindingConstants.CHANNEL_TYPE_HUMIDITY;
            case "temperature":
                return BindingConstants.CHANNEL_TYPE_TEMPERATURE;
            default:
                logger.warn(
                        "Not implemented channel type for {}. Defaulting to 'Number'. Create a PR or create an issue at https://github.com/seime/openhab-esphome/issues. Stack-trace to aid where to add support. Also remember to add appropriate channel-type in src/main/resources/thing/channel-types.xml: {}",
                        objectId, Thread.currentThread().getStackTrace());
                return BindingConstants.CHANNEL_TYPE_NUMBER;
        }
    }

    protected State toNumberState(Configuration configuration, float state, boolean missingState) {
        if (missingState) {
            return UnDefType.UNDEF;
        } else {
            String unit = (String) configuration.get("unit");
            if (unit != null) {
                return new QuantityType<>(state + unit);
            }
            return new DecimalType(state);
        }
    }

    protected Optional<Channel> findChannelByKey(int key) {
        return handler.getThing().getChannels().stream()
                .filter(e -> BigDecimal.valueOf(key).equals(e.getConfiguration().get(BindingConstants.COMMAND_KEY)))
                .findFirst();
    }

    protected Optional<Channel> findChannelByKeyAndField(int key, String field) {
        return handler.getThing().getChannels().stream()
                .filter(e -> BigDecimal.valueOf(key).equals(e.getConfiguration().get(BindingConstants.COMMAND_KEY))
                        && field.equals(e.getConfiguration().get(BindingConstants.COMMAND_FIELD)))
                .findFirst();
    }

    public void handleMessage(GeneratedMessageV3 message) {
        if (message.getClass().getSimpleName().startsWith("ListEntities")) {
            buildChannels((S) message);
        } else {
            handleState((T) message);
        }
    }
}
