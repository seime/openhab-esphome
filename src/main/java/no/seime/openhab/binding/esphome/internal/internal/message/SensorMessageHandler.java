package no.seime.openhab.binding.esphome.internal.internal.message;

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
import io.esphome.api.SelectCommandRequest;
import io.esphome.api.SensorStateClass;
import io.esphome.api.SensorStateResponse;
import no.seime.openhab.binding.esphome.internal.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.internal.handler.ESPHomeHandler;

public class SensorMessageHandler extends AbstractMessageHandler<ListEntitiesSensorResponse, SensorStateResponse> {

    private final Logger logger = LoggerFactory.getLogger(SensorMessageHandler.class);

    public SensorMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        handler.sendMessage(SelectCommandRequest.newBuilder().setKey(key).setState(command.toString()).build());
    }

    public void buildChannels(ListEntitiesSensorResponse rsp) {
        Configuration configuration = configuration(rsp.getKey(), null, null);
        if (!"None".equals(rsp.getUnitOfMeasurement()) && !"".equals(rsp.getUnitOfMeasurement())) {
            configuration.put("unit", rsp.getUnitOfMeasurement());
        }
        String deviceClass = rsp.getDeviceClass();
        if (deviceClass != null) {
            configuration.put("deviceClass", deviceClass);
        } else if (rsp.getStateClass() != SensorStateClass.STATE_CLASS_NONE) {
            configuration.put("deviceClass", "generic_number");
        }

        SensorDeviceClass sensorDeviceClass = SensorDeviceClass.fromDeviceClass(deviceClass);

        Set<String> tags = new HashSet<>();
        tags.add("Measurement");
        if (sensorDeviceClass != null && sensorDeviceClass.getMeasurementType() != null) {
            tags.add(sensorDeviceClass.getMeasurementType());
        }

        // TOOD state pattern should be moved to SensorDeviceClass enum as current impl does not handle
        // strings/enums/timestamps
        ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(),
                itemType(rsp.getName(), rsp.getDeviceClass()), Collections.emptyList(),
                "%." + rsp.getAccuracyDecimals() + "f " + rsp.getUnitOfMeasurement(), tags, true,
                sensorDeviceClass != null ? sensorDeviceClass.getCategory() : null);

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withConfiguration(configuration).build();
        super.registerChannel(channel, channelType);
    }

    private String itemType(String name, String deviceClass) {
        if (deviceClass == null || "".equals(deviceClass)) {
            logger.warn(
                    "No device_class reported by sensor '{}'. Add device_class attribute to sensor configuration in ESPHome. Defaulting to plain Number without dimension",
                    name);
            return "Number";
        } else {
            String itemType = SensorDeviceClass.getItemTypeForDeviceClass(deviceClass);
            if (itemType == null) {
                itemType = "Number";
                logger.warn(
                        "No item type found for device class '{}' for sensor '{}'. Defaulting to Number. Check valid values at first enum string entry in https://github.com/seime/openhab-esphome/blob/master/src/main/java/no/seime/openhab/binding/esphome/internal/internal/message/SensorDeviceClass.java",
                        deviceClass, name);
            }
            return itemType;
        }
    }

    public void handleState(SensorStateResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(channel -> handler.updateState(channel.getUID(),
                toNumericState(channel.getConfiguration(), rsp.getState(), rsp.getMissingState())));
    }
}
