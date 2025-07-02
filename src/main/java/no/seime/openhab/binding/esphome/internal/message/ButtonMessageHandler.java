package no.seime.openhab.binding.esphome.internal.message;

import java.util.Set;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.ButtonCommandRequest;
import io.esphome.api.ListEntitiesButtonResponse;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

/**
 * Note the second parameter to AbstractMessageHandler is a type that will never be used. Just here to fit the existing
 * code pattern
 */
public class ButtonMessageHandler extends AbstractMessageHandler<ListEntitiesButtonResponse, ButtonCommandRequest> {

    private final Logger logger = LoggerFactory.getLogger(ButtonMessageHandler.class);

    public ButtonMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        if (command instanceof OnOffType) {
            handler.updateState(channel.getUID(), OnOffType.OFF);
            handler.sendMessage(ButtonCommandRequest.newBuilder().setKey(key).build());
        } else {
            logger.warn("[{}] Unsupported command type: {}, use OnOffType instead", handler.getLogPrefix(), command);
        }
    }

    public void buildChannels(ListEntitiesButtonResponse rsp) {
        Configuration configuration = configuration(rsp.getKey(), null, "Button");

        String icon = getChannelIcon(rsp.getIcon(), "switch");

        ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), "Switch", Set.of("Switch"), icon,
                rsp.getEntityCategory(), rsp.getDisabledByDefault());

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType("Switch").withConfiguration(configuration).build();

        super.registerChannel(channel, channelType);
    }

    public void handleState(ButtonCommandRequest rsp) {
        // NOOP
    }
}
