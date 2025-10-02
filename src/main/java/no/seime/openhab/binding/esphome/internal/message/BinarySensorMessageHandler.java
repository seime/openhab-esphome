package no.seime.openhab.binding.esphome.internal.message;

import static org.openhab.core.library.CoreItemFactory.CONTACT;

import java.util.HashSet;
import java.util.Set;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.OnOffType;
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
import no.seime.openhab.binding.esphome.internal.EntityTypes;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;
import no.seime.openhab.binding.esphome.internal.message.deviceclass.BinarySensorDeviceClass;
import no.seime.openhab.binding.esphome.internal.message.deviceclass.DeviceClass;

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
        Configuration configuration = configuration(EntityTypes.BINARY_SENSOR, rsp.getKey(), null);

        DeviceClass deviceClass = resolveDeviceClassAndSetInConfiguration(configuration,
                BinarySensorDeviceClass.fromDeviceClass(rsp.getDeviceClass()), BinarySensorDeviceClass.NONE,
                rsp.getDeviceClass(), rsp.getName(),
                "https://www.home-assistant.io/integrations/binary_sensor/#device-class");

        String icon = getChannelIcon(rsp.getIcon(), deviceClass.getCategory());

        Set<String> tags = new HashSet<>();
        if (deviceClass.getSemanticType() != null) {
            tags.add(deviceClass.getSemanticType());
        } else {
            tags.add("Status"); // default
        }

        ChannelType channelType = addChannelType(rsp.getObjectId(), rsp.getName(), deviceClass.getItemType(), tags,
                icon, rsp.getEntityCategory(), rsp.getDisabledByDefault());

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType(deviceClass.getItemType()).withConfiguration(configuration).build();

        super.registerChannel(channel, channelType, readOnlyStateDescription());
    }

    public void handleState(BinarySensorStateResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(channel -> handler.updateState(channel.getUID(),
                toBinaryState(channel, rsp.getState(), rsp.getMissingState())));
    }

    protected State toBinaryState(Channel channel, boolean state, boolean missingState) {
        if (missingState) {
            return UnDefType.UNDEF;
        }
        return convertStateBasedOnDeviceClass((String) channel.getConfiguration().get("deviceClass"), state);
    }

    private State convertStateBasedOnDeviceClass(String deviceClass, boolean state) {
        return isDeviceClassContact(deviceClass) ? toOpenClosedType(state) : toOnOffType(state);
    }

    private static boolean isDeviceClassContact(String deviceClass) {
        if (deviceClass != null) {
            BinarySensorDeviceClass binarySensorDeviceClass = BinarySensorDeviceClass.fromDeviceClass(deviceClass);
            return binarySensorDeviceClass != null && CONTACT.equals(binarySensorDeviceClass.getItemType());
        }
        return false;
    }

    private static State toOpenClosedType(boolean state) {
        return state ? OpenClosedType.OPEN : OpenClosedType.CLOSED;
    }

    private static State toOnOffType(boolean state) {
        return state ? OnOffType.ON : OnOffType.OFF;
    }
}
