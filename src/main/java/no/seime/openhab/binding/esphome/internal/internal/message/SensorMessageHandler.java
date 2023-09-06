package no.seime.openhab.binding.esphome.internal.internal.message;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.measure.Unit;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.openhab.core.types.util.UnitUtils;
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

        SensorDeviceClass sensorDeviceClass = SensorDeviceClass.fromDeviceClass(deviceClass);

        Set<String> tags = new HashSet<>();
        tags.add("Measurement");
        if (sensorDeviceClass != null && sensorDeviceClass.getMeasurementType() != null) {
            tags.add(sensorDeviceClass.getMeasurementType());
        }

        // TOOD state pattern should be moved to SensorDeviceClass enum as current impl does not handle
        // strings/enums/timestamps

        String itemType = itemType(unitOfMeasurement, rsp.getName(), sensorDeviceClass);
        ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), itemType, Collections.emptyList(),
                "%." + rsp.getAccuracyDecimals() + "f "
                        + (unitOfMeasurement.equals("%") ? "%unit%" : unitOfMeasurement),
                tags, true, sensorDeviceClass != null ? sensorDeviceClass.getCategory() : null);

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType(itemType).withConfiguration(configuration).build();
        super.registerChannel(channel, channelType);
    }

    private String itemType(String unitOfMeasurement, String name, SensorDeviceClass deviceClass) {

        String itemTypeFromUnit = getItemTypeBaseOnUnit(unitOfMeasurement);

        String itemTypeToUse;

        if (itemTypeFromUnit != null && deviceClass != null) {
            if (!deviceClass.getItemType().equals(itemTypeFromUnit)) {
                // Verify that unit matches device_class as well
                itemTypeToUse = itemTypeFromUnit;
                logger.warn(
                        "Unexpected combination of device_class '{}' and unit '{}'. Returning item type '{}' based on unit",
                        deviceClass.getDeviceClass(), unitOfMeasurement, itemTypeToUse);

            } else {
                itemTypeToUse = deviceClass.getItemType();
                logger.debug("Using item type '{}' based on device_class '{}' and unit '{}'", itemTypeToUse,
                        deviceClass.getDeviceClass(), unitOfMeasurement);
            }

        } else if (itemTypeFromUnit != null) {
            itemTypeToUse = itemTypeFromUnit;
            logger.debug(
                    "Using item type '{}' based on unit '{}' since device_class is either missing from ESPHome device configuration or openhab mapping is incomplete",
                    itemTypeToUse, unitOfMeasurement);
        } else if (deviceClass != null) {
            itemTypeToUse = deviceClass.getItemType();
            logger.debug("Using item type '{}' based on device_class '{}' ", itemTypeToUse,
                    deviceClass.getDeviceClass());
        } else {
            logger.warn(
                    "Could not determine item type for sensor '{}' as neither device_class nor unit_of_measurement is present. Consider augmenting your ESPHome configuration. Using default 'Number'",
                    name);
            itemTypeToUse = "Number";
        }
        return itemTypeToUse;
    }

    private String getItemTypeBaseOnUnit(String unitOfMeasurement) {
        Unit<?> unit = UnitUtils.parseUnit(unitOfMeasurement);
        if (unit != null) {
            String dimensionName = UnitUtils.getDimensionName(unit);
            if (dimensionName != null) {
                return "Number:" + dimensionName;
            }
        }
        return null;
    }

    public void handleState(SensorStateResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(channel -> handler.updateState(channel.getUID(),
                toNumericState(channel, rsp.getState(), rsp.getMissingState())));
    }
}
