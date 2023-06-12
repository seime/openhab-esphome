package no.seime.openhab.binding.esphome.internal.message;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

import io.esphome.api.BinarySensorStateResponse;
import io.esphome.api.ListEntitiesBinarySensorResponse;
import io.esphome.api.SelectCommandRequest;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class BinarySensorMessageHandler
        extends AbstractMessageHandler<ListEntitiesBinarySensorResponse, BinarySensorStateResponse> {

    public BinarySensorMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        handler.sendMessage(SelectCommandRequest.newBuilder().setKey(key).setState(command.toString()).build());
    }

    @Override
    public void buildChannels(ListEntitiesBinarySensorResponse rsp) {
        Configuration configuration = configuration(rsp.getKey(), null, null);

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE)
                .withType(new ChannelTypeUID(BindingConstants.BINDING_ID, BindingConstants.CHANNEL_TYPE_CONTACT))
                .withConfiguration(configuration).build();

        super.registerChannel(channel, null);
    }

    public void handleState(BinarySensorStateResponse rsp) {

        findChannelByKey(rsp.getKey()).ifPresent(channel -> handler.updateState(channel.getUID(),
                toContactState(rsp.getState(), rsp.getMissingState())));
    }

    protected State toContactState(boolean state, boolean missingState) {
        if (missingState) {
            return UnDefType.UNDEF;
        } else {
            return state ? OpenClosedType.OPEN : OpenClosedType.CLOSED;
        }
    }
}
