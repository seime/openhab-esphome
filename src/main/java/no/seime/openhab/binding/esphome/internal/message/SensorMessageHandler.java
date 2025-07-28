package no.seime.openhab.binding.esphome.internal.message;

import static org.openhab.core.library.CoreItemFactory.*;

import java.util.HashSet;
import java.util.Set;

import org.openhab.core.config.core.Configuration;
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

import io.esphome.api.ListEntitiesSensorResponse;
import io.esphome.api.SensorStateResponse;
import no.seime.openhab.binding.esphome.internal.EntityTypes;
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
        Configuration configuration = configuration(EntityTypes.SENSOR, rsp.getKey(), null);

        SensorNumberDeviceClass deviceClass = SensorNumberDeviceClass.fromDeviceClass(rsp.getDeviceClass());
        if (deviceClass == null) {
            logger.info(
                    "[{}] Device class `{}` unknown, assuming 'None' for entity '{}'. To get rid of this log message, add a device_class attribute with a value from this list: https://www.home-assistant.io/integrations/sensor#device-class",
                    handler.getLogPrefix(), rsp.getDeviceClass(), rsp.getName());
            deviceClass = SensorNumberDeviceClass.GENERIC_NUMBER;
        }

        Set<String> tags = new HashSet<>();
        tags.add("Measurement");
        if (deviceClass.getSemanticType() != null) {
            tags.add(deviceClass.getSemanticType());
        }

        String itemType = deviceClass.getItemType();
        String icon = getChannelIcon(rsp.getIcon(), deviceClass.getCategory());

        ChannelType channelType;
        StateDescription stateDescription;

        if (deviceClass.getItemType().equals(DATETIME)) {
            channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), itemType, Set.of("Status"), icon,
                    rsp.getEntityCategory(), rsp.getDisabledByDefault());
            stateDescription = patternStateDescription("%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS", true);
        } else if (deviceClass.getItemType().equals(STRING)) {
            channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), itemType, Set.of("Status"), icon,
                    rsp.getEntityCategory(), rsp.getDisabledByDefault());
            stateDescription = patternStateDescription("%s", true);
        } else {
            String unitOfMeasurement = rsp.getUnitOfMeasurement();
            itemType = resolveNumericItemType(unitOfMeasurement, rsp.getName(), deviceClass);

            if (!"None".equals(unitOfMeasurement) && !"".equals(unitOfMeasurement)) {
                if (isOHSupportedUnit(unitOfMeasurement)) {
                    configuration.put("unit", unitOfMeasurement);
                } else {
                    logger.info(
                            "[{}] Unit of measurement '{}' is not supported by openHAB, ignoring and using plain 'Number' for entity '{}'",
                            handler.getLogPrefix(), unitOfMeasurement, rsp.getName());
                    itemType = NUMBER;
                }
            }

            channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), itemType, tags, icon,
                    rsp.getEntityCategory(), rsp.getDisabledByDefault());
            stateDescription = patternStateDescription("%." + rsp.getAccuracyDecimals() + "f "
                    + (unitOfMeasurement.equals("%") ? "%unit%" : unitOfMeasurement), true);
        }
        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType(itemType).withConfiguration(configuration).build();
        super.registerChannel(channel, channelType, stateDescription);
    }

    private boolean isOHSupportedUnit(String unitOfMeasurement) {
        return UnitUtils.parseUnit(unitOfMeasurement) != null;
    }

    @Override
    public void handleState(SensorStateResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(channel -> handler.updateState(channel.getUID(),
                toNumericState(channel, rsp.getState(), rsp.getMissingState())));
    }
}
