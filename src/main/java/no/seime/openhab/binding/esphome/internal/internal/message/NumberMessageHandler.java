package no.seime.openhab.binding.esphome.internal.internal.message;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.ListEntitiesNumberResponse;
import io.esphome.api.NumberCommandRequest;
import io.esphome.api.NumberStateResponse;
import no.seime.openhab.binding.esphome.internal.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.internal.handler.ESPHomeHandler;

public class NumberMessageHandler extends AbstractMessageHandler<ListEntitiesNumberResponse, NumberStateResponse> {

    public NumberMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    private final Logger logger = LoggerFactory.getLogger(NumberMessageHandler.class);

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        Float value = null;
        if (command instanceof DecimalType) {
            value = ((DecimalType) command).floatValue();
        } else if (command instanceof QuantityType<?>) {
            value = ((QuantityType<?>) command).floatValue();
        }
        if (value != null) {
            handler.sendMessage(NumberCommandRequest.newBuilder().setKey(key).setState(value).build());
        } else {
            logger.warn("Cannot send command to number channel {}, invalid type {}", channel.getUID(),
                    command.getClass().getSimpleName());
        }
    }

    @Override
    public void buildChannels(ListEntitiesNumberResponse rsp) {
        Configuration configuration = configuration(rsp.getKey(), null, null);
        String unitOfMeasurement = rsp.getUnitOfMeasurement();
        if (!"None".equals(unitOfMeasurement) && !"".equals(unitOfMeasurement)) {
            configuration.put("unit", unitOfMeasurement);
        }
        String deviceClass = rsp.getDeviceClass();
        if (deviceClass != null && !"".equals(deviceClass)) {
            configuration.put("deviceClass", deviceClass);
        } else {
            configuration.put("deviceClass", "generic_number");
        }

        SensorNumberDeviceClass numberDeviceClass = SensorNumberDeviceClass.fromDeviceClass(deviceClass);

        Set<String> tags = new HashSet<>();
        tags.add("Setpoint");
        if (numberDeviceClass != null && numberDeviceClass.getSemanticType() != null) {
            tags.add(numberDeviceClass.getSemanticType());
        }

        String itemType = resolveNumericItemType(unitOfMeasurement, rsp.getName(), numberDeviceClass);
        String step = "" + rsp.getStep();
        int accurracyDecimals = step.indexOf('.') > 0 ? step.length() - step.indexOf('.') - 1 : 0;

        ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), itemType, Collections.emptyList(),
                "%." + accurracyDecimals + "f " + (unitOfMeasurement.equals("%") ? "%unit%" : unitOfMeasurement), tags,
                false, numberDeviceClass != null ? numberDeviceClass.getCategory() : null,
                rsp.getStep() != 0f ? new BigDecimal(rsp.getStep()) : null,
                rsp.getMinValue() != 0f ? new BigDecimal(rsp.getMinValue()) : null,
                rsp.getMaxValue() != 0f ? new BigDecimal(rsp.getMaxValue()) : null);

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType(itemType).withConfiguration(configuration).build();
        super.registerChannel(channel, channelType);
    }

    @Override
    public void handleState(NumberStateResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(channel -> handler.updateState(channel.getUID(),
                toNumericState(channel, rsp.getState(), rsp.getMissingState())));
    }
}
