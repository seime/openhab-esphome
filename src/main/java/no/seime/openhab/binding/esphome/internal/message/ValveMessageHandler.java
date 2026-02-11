package no.seime.openhab.binding.esphome.internal.message;

import static org.openhab.core.library.CoreItemFactory.STRING;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;

import org.openhab.core.library.types.*;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.StateDescription;
import org.openhab.core.types.UnDefType;

import io.esphome.api.ListEntitiesValveResponse;
import io.esphome.api.ValveCommandRequest;
import io.esphome.api.ValveOperation;
import io.esphome.api.ValveStateResponse;
import no.seime.openhab.binding.esphome.internal.EntityTypes;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;
import no.seime.openhab.binding.esphome.internal.message.deviceclass.DeviceClass;
import no.seime.openhab.binding.esphome.internal.message.deviceclass.ValveDeviceClass;

public class ValveMessageHandler extends AbstractMessageHandler<ListEntitiesValveResponse, ValveStateResponse> {

    public static final String CHANNEL_POSITION = "position";
    public static final String CHANNEL_CURRENT_OPERATION = "current_operation";

    public ValveMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    public static String stripEnumPrefix(ValveOperation valveOperation) {
        String toRemove = "VALVE_OPERATION";
        return valveOperation.toString().substring(toRemove.length() + 1);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        ValveCommandRequest.Builder builder = ValveCommandRequest.newBuilder().setKey(key);

        if (command == StopMoveType.STOP) {
            builder.setStop(true);
        } else {
            if (command instanceof QuantityType<?> number) {
                builder.setPosition(invert(number.floatValue() / 100));
            } else if (command instanceof PercentType number) {
                builder.setPosition(invert(number.floatValue() / 100));
            } else if (command instanceof DecimalType number) {
                builder.setPosition(invert(number.floatValue() / 100));
            } else if (command == UpDownType.UP) {
                builder.setPosition(1);
            } else if (command == UpDownType.DOWN) {
                builder.setPosition(0);
            }
            builder.setHasPosition(true);

        }
        handler.sendMessage(builder.build());
    }

    public void buildChannels(ListEntitiesValveResponse rsp) {

        DeviceClass deviceClass = resolveDeviceClassAndSetInConfiguration(null,
                ValveDeviceClass.fromDeviceClass(rsp.getDeviceClass()), ValveDeviceClass.NONE, rsp.getDeviceClass(),
                rsp.getName(), "https://www.home-assistant.io/integrations/valve/#device-class");

        String icon = getChannelIcon(rsp.getIcon(), deviceClass.getCategory());

        Set<String> semanticTags = createSemanticTags("OpenLevel", deviceClass);

        ChannelType channelTypePosition = addChannelType("Position", deviceClass.getItemType(), semanticTags, icon,
                rsp.getEntityCategory(), rsp.getDisabledByDefault());
        StateDescription stateDescription = patternStateDescription("%d %%");

        Channel channelPosition = ChannelBuilder
                .create(createChannelUID(rsp.getObjectId(), EntityTypes.VALVE, CHANNEL_POSITION))
                .withLabel(createChannelLabel(rsp.getName(), "Position")).withKind(ChannelKind.STATE)
                .withType(channelTypePosition.getUID()).withAcceptedItemType(deviceClass.getItemType())
                .withConfiguration(configuration(EntityTypes.VALVE, rsp.getKey(), CHANNEL_POSITION)).build();
        super.registerChannel(channelPosition, channelTypePosition, stateDescription);

        // Operation status
        ChannelType channelTypeCurrentOperation = addChannelType("Current operation", STRING,
                createSemanticTags("Status", deviceClass), "motion", rsp.getEntityCategory(),
                rsp.getDisabledByDefault());
        stateDescription = optionListStateDescription(Arrays.stream(ValveOperation.values())
                .filter(v -> v != ValveOperation.UNRECOGNIZED).map(v -> stripEnumPrefix(v)).toList(), true);

        Channel channelCurrentOperation = ChannelBuilder
                .create(createChannelUID(rsp.getObjectId(), EntityTypes.VALVE, CHANNEL_CURRENT_OPERATION))
                .withLabel(createChannelLabel(rsp.getName(), "Current operation")).withKind(ChannelKind.STATE)
                .withType(channelTypeCurrentOperation.getUID()).withAcceptedItemType(STRING)
                .withConfiguration(configuration(EntityTypes.VALVE, rsp.getKey(), CHANNEL_CURRENT_OPERATION)).build();
        super.registerChannel(channelCurrentOperation, channelTypeCurrentOperation, stateDescription);
    }

    public void handleState(ValveStateResponse rsp) {
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_POSITION).ifPresent(
                channel -> handler.updateState(channel.getUID(), toPercentState(invert(rsp.getPosition()), false)));
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_CURRENT_OPERATION).ifPresent(channel -> handler
                .updateState(channel.getUID(), new StringType(stripEnumPrefix(rsp.getCurrentOperation()))));
    }

    protected State toPercentState(float state, boolean missingState) {
        if (missingState || Float.isNaN(state)) {
            return UnDefType.UNDEF;
        } else {
            return new PercentType(BigDecimal.valueOf(state * 100));
        }
    }

    private float invert(float value) {
        return 1f - value;
    }
}
