package no.seime.openhab.binding.esphome.internal.message;

import java.util.Collections;
import java.util.HashSet;
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

import io.esphome.api.ListEntitiesSensorResponse;
import io.esphome.api.SensorStateClass;
import io.esphome.api.SensorStateResponse;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class SensorMessageHandler extends AbstractMessageHandler<ListEntitiesSensorResponse, SensorStateResponse> {

    private final Logger logger = LoggerFactory.getLogger(SensorMessageHandler.class);

    public SensorMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        logger.warn("Cannot send command to sensor channel {}, read only", channel.getUID());
    }

    @Override
    public void buildChannels(ListEntitiesSensorResponse rsp) {
        Configuration configuration = configuration(rsp.getKey(), null, null);
        String unitOfMeasurement = rsp.getUnitOfMeasurement();
        if (!"None".equals(unitOfMeasurement) && !"".equals(unitOfMeasurement)) {
            configuration.put("unit", unitOfMeasurement);
        }
        String deviceClass = rsp.getDeviceClass();
        if (deviceClass != null && !"".equals(deviceClass)) {
            configuration.put("deviceClass", deviceClass);
        } else if (rsp.getStateClass() != SensorStateClass.STATE_CLASS_NONE) {
            configuration.put("deviceClass", "generic_number");
        }

        SensorNumberDeviceClass sensorDeviceClass = SensorNumberDeviceClass.fromDeviceClass(deviceClass);

        Set<String> tags = new HashSet<>();
        tags.add("Measurement");
        if (sensorDeviceClass != null && sensorDeviceClass.getSemanticType() != null) {
            tags.add(sensorDeviceClass.getSemanticType());
        }

        // TOOD state pattern should be moved to SensorNumberDeviceClass enum as current impl does not handle
        // strings/enums/timestamps

        String icon = getChannelIcon(rsp.getIcon(), sensorDeviceClass != null ? sensorDeviceClass.getCategory() : null);

        String itemType;
        ChannelType channelType;

        if (sensorDeviceClass.getItemType().equals("DateTime")) {
            itemType = "DateTime";
            channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), itemType, Collections.emptySet(),
                    "%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS", Set.of("Status"), true, icon, null, null, null,
                    rsp.getEntityCategory());
        } else {

            itemType = resolveNumericItemType(unitOfMeasurement, rsp.getName(), sensorDeviceClass);
            channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), itemType, Collections.emptyList(),
                    "%." + rsp.getAccuracyDecimals() + "f "
                            + (unitOfMeasurement.equals("%") ? "%unit%" : unitOfMeasurement),
                    tags, true, icon, null, null, null, rsp.getEntityCategory());
        }
        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType(itemType).withConfiguration(configuration).build();
        super.registerChannel(channel, channelType);
    }

    @Override
    public void handleState(SensorStateResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(channel -> handler.updateState(channel.getUID(),
                toNumericState(channel, rsp.getState(), rsp.getMissingState())));
    }
}
