package no.seime.openhab.binding.esphome.internal.message;

import java.math.BigDecimal;
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
import org.openhab.core.types.StateDescription;
import org.openhab.core.types.util.UnitUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.ListEntitiesNumberResponse;
import io.esphome.api.NumberCommandRequest;
import io.esphome.api.NumberStateResponse;
import no.seime.openhab.binding.esphome.internal.EntityTypes;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;
import no.seime.openhab.binding.esphome.internal.message.deviceclass.DeviceClass;
import no.seime.openhab.binding.esphome.internal.message.deviceclass.SensorNumberDeviceClass;

public class NumberMessageHandler extends AbstractMessageHandler<ListEntitiesNumberResponse, NumberStateResponse> {

    public NumberMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    private final Logger logger = LoggerFactory.getLogger(NumberMessageHandler.class);

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        Float value = null;
        if (command instanceof DecimalType decimalType) {
            value = decimalType.floatValue();
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
        Configuration configuration = configuration(EntityTypes.NUMBER, rsp.getKey(), null);

        DeviceClass deviceClass = resolveDeviceClassAndSetInConfiguration(configuration,
                SensorNumberDeviceClass.fromDeviceClass(rsp.getDeviceClass()), SensorNumberDeviceClass.NONE,
                rsp.getDeviceClass(), rsp.getName(), "https://www.home-assistant.io/integrations/number/#device-class");

        Set<String> semanticTags = createSemanticTags("Setpoint", deviceClass);
        String unit = rsp.getUnitOfMeasurement();
        String itemType = resolveNumericItemType(unit, rsp.getName(), deviceClass);
        String step = "" + rsp.getStep();
        int accuracyDecimals = step.indexOf('.') > 0 ? step.length() - step.indexOf('.') - 1 : 0;

        String icon = getChannelIcon(rsp.getIcon(), deviceClass.getCategory());

        ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), itemType, semanticTags, icon,
                rsp.getEntityCategory(), rsp.getDisabledByDefault());

        StateDescription stateDescription = numericStateDescription(
                "%." + accuracyDecimals + "f " + (unit.equals("%") ? "%unit%" : unit),
                rsp.getStep() != 0f ? BigDecimal.valueOf(rsp.getStep()) : null,
                rsp.getMinValue() != 0f ? BigDecimal.valueOf(rsp.getMinValue()) : null,
                rsp.getMaxValue() != 0f ? BigDecimal.valueOf(rsp.getMaxValue()) : null);

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType(itemType).withConfiguration(configuration).build();
        super.registerChannel(channel, channelType, stateDescription);
    }

    @Override
    public void handleState(NumberStateResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(channel -> handler.updateState(channel.getUID(),
                toNumericState(channel, rsp.getState(), rsp.getMissingState())));
    }
}
