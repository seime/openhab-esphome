package no.seime.openhab.binding.esphome.internal.internal.message;

import java.util.ArrayList;
import java.util.Set;

import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.openhab.core.types.UnDefType;

import io.esphome.api.ListEntitiesSelectResponse;
import io.esphome.api.SelectCommandRequest;
import io.esphome.api.SelectStateResponse;
import no.seime.openhab.binding.esphome.internal.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.internal.handler.ESPHomeHandler;

public class SelectMessageHandler extends AbstractMessageHandler<ListEntitiesSelectResponse, SelectStateResponse> {

    public SelectMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        handler.sendMessage(SelectCommandRequest.newBuilder().setKey(key).setState(command.toString()).build());
    }

    public void buildChannels(ListEntitiesSelectResponse rsp) {
        String itemType = "String";
        ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), itemType,
                new ArrayList<>(rsp.getOptionsList()), "%s", Set.of("Setpoint"), false, null, null, null, null);

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType(itemType).withConfiguration(configuration(rsp.getKey(), null, "Select")).build();

        super.registerChannel(channel, channelType);
    }

    @Override
    public void handleState(SelectStateResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(channel -> handler.updateState(channel.getUID(),
                rsp.getMissingState() ? UnDefType.UNDEF : new StringType(rsp.getState())));
    }
}
