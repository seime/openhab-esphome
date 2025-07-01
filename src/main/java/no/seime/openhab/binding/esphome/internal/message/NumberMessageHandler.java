package no.seime.openhab.binding.esphome.internal.message;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.measure.Unit;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.openhab.core.types.util.UnitUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.ListEntitiesNumberResponse;
import io.esphome.api.NumberCommandRequest;
import io.esphome.api.NumberStateResponse;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

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
        } else if (command instanceof QuantityType<?> qt) {
            Configuration configuration = channel.getConfiguration();
            String unitString = (String) configuration.get("unit");
            if (unitString != null) {
                unitString = transformUnit(unitString);
                Unit<?> unit = UnitUtils.parseUnit(unitString);
                if (unit != null) {
                    QuantityType<?> newQt = qt.toUnit(unit);
                    if (newQt == null) {
                        logger.warn("[{}] Quantity {} incompatible with unit {} on channel '{}'",
                                handler.getLogPrefix(), qt, unit, channel.getUID());
                        return;
                    }
                    qt = newQt;
                }
            }
            value = qt.floatValue();
        }
        if (value != null) {
            handler.sendMessage(NumberCommandRequest.newBuilder().setKey(key).setState(value).build());
        } else {
            logger.warn("[{}] Cannot send command to number channel {}, invalid type {}", handler.getLogPrefix(),
                    channel.getUID(), command.getClass().getSimpleName());
        }
    }

    @Override
    public void buildChannels(ListEntitiesNumberResponse rsp) {
        Configuration configuration = configuration(rsp.getKey(), null, "Number");
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

        String icon = getChannelIcon(rsp.getIcon(), numberDeviceClass != null ? numberDeviceClass.getCategory() : null);

        ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), itemType, Collections.emptyList(),
                "%." + accurracyDecimals + "f " + (unitOfMeasurement.equals("%") ? "%unit%" : unitOfMeasurement), tags,
                false, icon, rsp.getStep() != 0f ? BigDecimal.valueOf(rsp.getStep()) : null,
                rsp.getMinValue() != 0f ? BigDecimal.valueOf(rsp.getMinValue()) : null,
                rsp.getMaxValue() != 0f ? BigDecimal.valueOf(rsp.getMaxValue()) : null, rsp.getEntityCategory(),
                rsp.getDisabledByDefault());

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
