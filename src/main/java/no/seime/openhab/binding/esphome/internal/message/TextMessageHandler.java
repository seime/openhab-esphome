package no.seime.openhab.binding.esphome.internal.message;

import java.util.Collections;
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

import io.esphome.api.ListEntitiesTextResponse;
import io.esphome.api.TextCommandRequest;
import io.esphome.api.TextStateResponse;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class TextMessageHandler extends AbstractMessageHandler<ListEntitiesTextResponse, TextStateResponse> {

    public TextMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        handler.sendMessage(TextCommandRequest.newBuilder().setKey(key).setState(command.toString()).build());
    }

    @Override
    public void buildChannels(ListEntitiesTextResponse rsp) {
        Configuration configuration = configuration(rsp.getKey(), null, "Text");

        String icon = getChannelIcon(rsp.getIcon(), "text");

        String itemType = "String";
        ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), itemType, Collections.emptySet(),
                null, Set.of("Status"), false, icon, null, null, null, rsp.getEntityCategory());

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType(itemType).withConfiguration(configuration).build();

        super.registerChannel(channel, channelType);
    }

    @Override
    public void handleState(TextStateResponse rsp) {
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
