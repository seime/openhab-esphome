package no.seime.openhab.binding.esphome.internal.message;

import static org.openhab.core.library.CoreItemFactory.STRING;

import java.util.Set;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.ListEntitiesTextSensorResponse;
import io.esphome.api.TextSensorStateResponse;
import no.seime.openhab.binding.esphome.internal.EntityTypes;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class TextSensorMessageHandler
        extends AbstractMessageHandler<ListEntitiesTextSensorResponse, TextSensorStateResponse> {

    private final Logger logger = LoggerFactory.getLogger(TextSensorMessageHandler.class);

    public TextSensorMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        // No command support
        logger.warn(
                "[{}] Cannot send command '{}' to text sensor channel {}, read only. Use a `text` component instead",
                handler.getLogPrefix(), command, channel.getUID());
    }

    @Override
    public void buildChannels(ListEntitiesTextSensorResponse rsp) {
        Configuration configuration = configuration(EntityTypes.TEXT_SENSOR, rsp.getKey(), null);

        String icon = getChannelIcon(rsp.getIcon(), "text");

        ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), STRING, Set.of("Status"), icon,
                rsp.getEntityCategory(), rsp.getDisabledByDefault());

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType(STRING).withConfiguration(configuration).build();

        super.registerChannel(channel, channelType, readOnlyStateDescription());
    }

    @Override
    public void handleState(TextSensorStateResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(
                channel -> handler.updateState(channel.getUID(), toTextState(rsp.getState(), rsp.getMissingState())));
    }

    protected State toTextState(String state, boolean missingState) {
        if (missingState) {
            return UnDefType.UNDEF;
        } else {
            return new StringType(state);
        }
    }
}
