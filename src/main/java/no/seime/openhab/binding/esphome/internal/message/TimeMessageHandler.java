package no.seime.openhab.binding.esphome.internal.message;

import java.util.Set;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.StateDescription;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.ListEntitiesTimeResponse;
import io.esphome.api.TimeCommandRequest;
import io.esphome.api.TimeStateResponse;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class TimeMessageHandler extends AbstractMessageHandler<ListEntitiesTimeResponse, TimeStateResponse> {

    private final Logger logger = LoggerFactory.getLogger(TimeMessageHandler.class);

    public TimeMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        if (command instanceof QuantityType<?> time) {
            handler.sendMessage(TimeCommandRequest.newBuilder().setKey(key).setHour(time.intValue() / 3600)
                    .setMinute((time.intValue() % 3600) / 60).setSecond(time.intValue() % 60).build());
        } else {
            logger.warn("[{}] Unsupported command type: {}", handler.getLogPrefix(), command);
        }
    }

    @Override
    public void buildChannels(ListEntitiesTimeResponse rsp) {
        Configuration configuration = configuration(rsp.getKey(), null, "Time");

        String icon = getChannelIcon(rsp.getIcon(), "time");

        String itemType = "Number:Time";
        ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), itemType, Set.of("Status"), icon,
                rsp.getEntityCategory(), rsp.getDisabledByDefault());
        StateDescription stateDescription = addStateDescription("%1$tH:%1$tM:%1$tS");

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType(itemType).withConfiguration(configuration).build();

        super.registerChannel(channel, channelType, stateDescription);
    }

    @Override
    public void handleState(TimeStateResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(channel -> handler.updateState(channel.getUID(),
                toTimeState(rsp.getHour(), rsp.getMinute(), rsp.getSecond(), rsp.getMissingState())));
    }

    protected State toTimeState(int hour, int minute, int second, boolean missingState) {
        if (missingState) {
            return UnDefType.UNDEF;
        } else {
            return new QuantityType<>(hour * 3600 + minute * 60 + second, Units.SECOND);
        }
    }
}
