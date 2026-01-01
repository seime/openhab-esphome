package no.seime.openhab.binding.esphome.internal.message;

import static org.openhab.core.library.CoreItemFactory.*;

import java.util.Set;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.openhab.core.types.StateDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.ListEntitiesSensorResponse;
import io.esphome.api.SensorStateResponse;
import no.seime.openhab.binding.esphome.internal.EntityTypes;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;
import no.seime.openhab.binding.esphome.internal.message.deviceclass.DeviceClass;
import no.seime.openhab.binding.esphome.internal.message.deviceclass.SensorNumberDeviceClass;

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

        DeviceClass deviceClass = resolveDeviceClassAndSetInConfiguration(configuration,
                SensorNumberDeviceClass.fromDeviceClass(rsp.getDeviceClass()), SensorNumberDeviceClass.NONE,
                rsp.getDeviceClass(), rsp.getName(), "https://www.home-assistant.io/integrations/sensor/#device-class");

        Set<String> semanticTags = createSemanticTags("Measurement", deviceClass);
        String itemType = deviceClass.getItemType();
        String icon = getChannelIcon(rsp.getIcon(), deviceClass.getCategory());

        ChannelType channelType;
        StateDescription stateDescription;

        if (deviceClass.getItemType().equals(DATETIME)) {
            channelType = addChannelType(rsp.getObjectId(), rsp.getName(), itemType, Set.of("Status"), icon,
                    rsp.getEntityCategory(), rsp.getDisabledByDefault());
            stateDescription = patternStateDescription("%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS", true);
        } else if (deviceClass.getItemType().equals(STRING)) {
            channelType = addChannelType(rsp.getObjectId(), rsp.getName(), itemType, Set.of("Status"), icon,
                    rsp.getEntityCategory(), rsp.getDisabledByDefault());
            stateDescription = patternStateDescription("%s", true);
        } else {
            String unitOfMeasurement = rsp.getUnitOfMeasurement();
            itemType = resolveNumericItemType(unitOfMeasurement, rsp.getName(), deviceClass, configuration);

            channelType = addChannelType(rsp.getObjectId(), rsp.getName(), itemType, semanticTags, icon,
                    rsp.getEntityCategory(), rsp.getDisabledByDefault());
            stateDescription = patternStateDescription("%." + rsp.getAccuracyDecimals() + "f "
                    + (unitOfMeasurement.equals("%") ? "%unit%" : unitOfMeasurement), true);
        }
        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType(itemType).withConfiguration(configuration).build();
        super.registerChannel(channel, channelType, stateDescription);
    }

    @Override
    public void handleState(SensorStateResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(channel -> handler.updateState(channel.getUID(),
                toNumericState(channel, rsp.getState(), rsp.getMissingState())));
    }
}
