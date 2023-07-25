package no.seime.openhab.binding.esphome.internal.internal.message;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;

import io.esphome.api.ListEntitiesSensorResponse;
import io.esphome.api.SelectCommandRequest;
import io.esphome.api.SensorStateResponse;
import no.seime.openhab.binding.esphome.internal.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.internal.handler.ESPHomeHandler;

public class SensorMessageHandler extends AbstractMessageHandler<ListEntitiesSensorResponse, SensorStateResponse> {

    public SensorMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        handler.sendMessage(SelectCommandRequest.newBuilder().setKey(key).setState(command.toString()).build());
    }

    public void buildChannels(ListEntitiesSensorResponse rsp) {
        Configuration configuration = configuration(rsp.getKey(), null, null);
        configuration.put("unit", rsp.getUnitOfMeasurement());

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE)
                .withType(new ChannelTypeUID(BindingConstants.BINDING_ID, channelType(rsp.getDeviceClass())))
                .withConfiguration(configuration).build();

        super.registerChannel(channel, null);
    }

    public void handleState(SensorStateResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(channel -> handler.updateState(channel.getUID(),
                toNumberState(channel.getConfiguration(), rsp.getState(), rsp.getMissingState())));
    }
}
