package no.seime.openhab.binding.esphome.internal.message;

import static org.openhab.core.library.CoreItemFactory.SWITCH;

import java.util.Set;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;

import io.esphome.api.ListEntitiesSwitchResponse;
import io.esphome.api.SwitchCommandRequest;
import io.esphome.api.SwitchStateResponse;
import no.seime.openhab.binding.esphome.internal.EntityTypes;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class SwitchMessageHandler extends AbstractMessageHandler<ListEntitiesSwitchResponse, SwitchStateResponse> {

    public SwitchMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        handler.sendMessage(SwitchCommandRequest.newBuilder().setKey(key).setState(command == OnOffType.ON).build());
    }

    public void buildChannels(ListEntitiesSwitchResponse rsp) {
        Configuration configuration = configuration(EntityTypes.SWITCH, rsp.getKey(), null);

        String icon = getChannelIcon(rsp.getIcon(), "switch");

        ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), SWITCH, Set.of("Switch"), icon,
                rsp.getEntityCategory(), rsp.getDisabledByDefault());

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType(SWITCH).withConfiguration(configuration).build();

        super.registerChannel(channel, channelType);
    }

    public void handleState(SwitchStateResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(
                channel -> handler.updateState(channel.getUID(), rsp.getState() ? OnOffType.ON : OnOffType.OFF));
    }
}
