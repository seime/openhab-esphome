package no.seime.openhab.binding.esphome.internal.internal.message;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.OpenClosedType;
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

import io.esphome.api.BinarySensorStateResponse;
import io.esphome.api.ListEntitiesBinarySensorResponse;
import io.esphome.api.SelectCommandRequest;
import no.seime.openhab.binding.esphome.internal.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.internal.handler.ESPHomeHandler;

public class BinarySensorMessageHandler
        extends AbstractMessageHandler<ListEntitiesBinarySensorResponse, BinarySensorStateResponse> {
    private final Logger logger = LoggerFactory.getLogger(BinarySensorMessageHandler.class);

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

        String deviceClass = rsp.getDeviceClass();
        if (!deviceClass.isEmpty()) {
            configuration.put("deviceClass", deviceClass);
        } else {
            configuration.put("deviceClass", "generic");
        }

        BinarySensorDeviceClass binarySensorDeviceClass = BinarySensorDeviceClass.fromDeviceClass(deviceClass);
        if (binarySensorDeviceClass == null) {
            logger.warn(
                    "ESPHome Binary Sensor Device class `{}` not know to the ESPHome Native API Binding using GENERIC for {}",
                    deviceClass, rsp.getUniqueId());
            binarySensorDeviceClass = BinarySensorDeviceClass.GENERIC;
        }

        Set<String> tags = new HashSet<>();
        if (binarySensorDeviceClass.getSemanticType() != null) {
            tags.add(binarySensorDeviceClass.getSemanticType());
        } else {
            tags.add("Status"); // default
        }

        ChannelType channelType = addChannelType(rsp.getObjectId(), rsp.getName(),
                binarySensorDeviceClass.getItemType(), Collections.emptySet(), null,
                tags, true, binarySensorDeviceClass.getCategory(), null, null, null);

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withConfiguration(configuration).build();

        super.registerChannel(channel, channelType);
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
