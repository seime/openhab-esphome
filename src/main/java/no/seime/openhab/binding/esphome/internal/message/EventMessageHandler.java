package no.seime.openhab.binding.esphome.internal.message;

import java.util.Collections;
import java.util.Set;

import org.openhab.core.library.CoreItemFactory;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.EventResponse;
import io.esphome.api.ListEntitiesEventResponse;
import no.seime.openhab.binding.esphome.internal.EntityTypes;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class EventMessageHandler extends AbstractMessageHandler<ListEntitiesEventResponse, EventResponse> {

    private final Logger logger = LoggerFactory.getLogger(EventMessageHandler.class);

    public EventMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        logger.warn("Events are read-only. Ignoring command {} for channel {}", command, channel.getUID());
    }

    @Override
    public void buildChannels(ListEntitiesEventResponse rsp) {
        String itemType = CoreItemFactory.STRING;
        Set<String> tags = Collections.singleton("Status");
        String icon = getChannelIcon(rsp.getIcon(), "text");

        ChannelType channelType = addChannelType(rsp.getName(), itemType, tags, icon, rsp.getEntityCategory(),
                rsp.getDisabledByDefault());

        Channel channel = ChannelBuilder.create(createChannelUID(rsp.getName(), EntityTypes.EVENT))
                .withLabel(createChannelLabel(rsp.getName(), rsp.getName())).withKind(ChannelKind.TRIGGER)
                .withType(channelType.getUID()).withAcceptedItemType(itemType)
                .withConfiguration(configuration(EntityTypes.EVENT, rsp.getKey(), null)).build();

        super.registerChannel(channel, channelType);
    }

    @Override
    public void handleState(EventResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(channel -> {
            handler.triggerChannel(channel.getUID(), rsp.getEventType());
        });
    }
}
