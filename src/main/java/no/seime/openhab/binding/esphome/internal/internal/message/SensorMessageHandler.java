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
        if (!"None".equals(rsp.getUnitOfMeasurement())) {
            configuration.put("unit", rsp.getUnitOfMeasurement());
        }
        String deviceClass = rsp.getDeviceClass();
        if (deviceClass != null) {
            configuration.put("deviceClass", deviceClass);
        }

        SensorDeviceClass sensorDeviceClass = SensorDeviceClass.fromDeviceClass(deviceClass);

        Set<String> tags = new HashSet<>();
        tags.add("Measurement");
        if (sensorDeviceClass != null && sensorDeviceClass.getMeasurementType() != null) {
            tags.add(sensorDeviceClass.getMeasurementType());
        }

        // TOOD state pattern should be moved to SensorDeviceClass enum as current impl does not handle
        // strings/enums/timestamps
        ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), itemType(rsp.getDeviceClass()),
                Collections.emptyList(), "%." + rsp.getAccuracyDecimals() + "f " + rsp.getUnitOfMeasurement(), tags,
                true, sensorDeviceClass != null ? sensorDeviceClass.getCategory() : null);

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withConfiguration(configuration).build();
        super.registerChannel(channel, channelType);
    }

    private String itemType(String deviceClass) {
        String itemType = SensorDeviceClass.getItemTypeForDeviceClass(deviceClass);
        if (itemType == null) {
            itemType = "String";
            logger.warn(
                    "No item type found for device class {}. Defaulting to String. Create a PR or create an issue at https://github.com/seime/openhab-esphome/issues. Stack-trace to aid where to add support. ");
        }

        return itemType;
    }

    public void handleState(SensorStateResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(channel -> handler.updateState(channel.getUID(),
                toNumericState(channel.getConfiguration(), rsp.getState(), rsp.getMissingState())));
    }
}
