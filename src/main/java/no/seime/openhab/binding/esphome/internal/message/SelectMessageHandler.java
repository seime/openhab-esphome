package no.seime.openhab.binding.esphome.internal.message;

import static org.openhab.core.library.CoreItemFactory.STRING;

import java.util.Set;

import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.openhab.core.types.StateDescription;
import org.openhab.core.types.UnDefType;

import io.esphome.api.ListEntitiesSelectResponse;
import io.esphome.api.SelectCommandRequest;
import io.esphome.api.SelectStateResponse;
import no.seime.openhab.binding.esphome.internal.EntityTypes;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class SelectMessageHandler extends AbstractMessageHandler<ListEntitiesSelectResponse, SelectStateResponse> {

    public SelectMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        handler.sendMessage(SelectCommandRequest.newBuilder().setKey(key).setState(command.toString()).build());
    }

    public void buildChannels(ListEntitiesSelectResponse rsp) {

        String icon = getChannelIcon(rsp.getIcon(), null);

        ChannelType channelType = addChannelType(rsp.getObjectId(), rsp.getName(), STRING, Set.of("Control"), icon,
                rsp.getEntityCategory(), rsp.getDisabledByDefault());
        StateDescription stateDescription = optionListStateDescription(rsp.getOptionsList());

        Channel channel = ChannelBuilder.create(createChannelUID(handler, rsp.getObjectId(), EntityTypes.SELECT))
                .withLabel(createChannelLabel(rsp.getName())).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType(STRING).withConfiguration(configuration(EntityTypes.SELECT, rsp.getKey(), null))
                .build();

        super.registerChannel(channel, channelType, stateDescription);
    }

    @Override
    public void handleState(SelectStateResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(channel -> handler.updateState(channel.getUID(),
                rsp.getMissingState() ? UnDefType.UNDEF : new StringType(rsp.getState())));
    }
}
