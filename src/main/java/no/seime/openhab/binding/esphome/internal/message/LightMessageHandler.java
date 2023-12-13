package no.seime.openhab.binding.esphome.internal.message;

import java.util.Collections;
import java.util.Set;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.LightStateResponse;
import io.esphome.api.ListEntitiesLightResponse;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class LightMessageHandler extends AbstractMessageHandler<ListEntitiesLightResponse, LightStateResponse> {

    private final Logger logger = LoggerFactory.getLogger(LightMessageHandler.class);

    public LightMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        // TODO must figure out the HA light component modes and how to map them to openhab
        logger.warn(
                "Unhandled command {} for channel {} - in fact the Light component isn't really implemented yet. Contribution needed",
                command, channel);
        // handler.sendMessage(LightCommandRequest.newBuilder().setKey(key).setState().build());
    }

    public void buildChannels(ListEntitiesLightResponse rsp) {
        Configuration configuration = configuration(rsp.getKey(), null, "Light");

        ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), "Color", Collections.emptySet(),
                null, Set.of("Light"), false, "light", null, null, null);

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType("Color").withConfiguration(configuration).build();

        super.registerChannel(channel, channelType);
    }

    public void handleState(LightStateResponse rsp) {
        // TODO must figure out the HA light component modes and how to map them to openhab
        logger.warn(
                "Unhandled state for esp light {} - in fact the Light component isn't really implemented yet. Contribution needed",
                rsp.getKey());
        // findChannelByKey(rsp.getKey()).ifPresent(
        // channel -> handler.updateState(channel.getUID(), HSBType.fromRGB(rsp.getRed(),rsp.getGreen(),rsp.getBlue()));
    }
}
