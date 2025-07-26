package no.seime.openhab.binding.esphome.internal.message;

import static org.openhab.core.library.CoreItemFactory.DATETIME;

import java.time.ZonedDateTime;
import java.util.Set;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.openhab.core.types.StateDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.DateTimeCommandRequest;
import io.esphome.api.DateTimeStateResponse;
import io.esphome.api.ListEntitiesDateTimeResponse;
import no.seime.openhab.binding.esphome.internal.EntityTypes;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class DateTimeMessageHandler
        extends AbstractMessageHandler<ListEntitiesDateTimeResponse, DateTimeStateResponse> {

    private final Logger logger = LoggerFactory.getLogger(DateTimeMessageHandler.class);

    public DateTimeMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {

        if (command instanceof DateTimeType date) {
            ZonedDateTime zonedDateTime = date.getZonedDateTime();
            handler.sendMessage(DateTimeCommandRequest.newBuilder().setKey(key)
                    .setEpochSeconds((int) zonedDateTime.toInstant().getEpochSecond()).build());
        } else {
            logger.warn("[{}] Unsupported command type: {}", handler.getLogPrefix(), command);
        }
    }

    @Override
    public void buildChannels(ListEntitiesDateTimeResponse rsp) {
        Configuration configuration = configuration(EntityTypes.DATE_TIME, rsp.getKey(), null);

        String icon = getChannelIcon(rsp.getIcon(), "time");

        ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), DATETIME, Set.of("Status"), icon,
                rsp.getEntityCategory(), rsp.getDisabledByDefault());
        StateDescription stateDescription = patternStateDescription("%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS");

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType(DATETIME).withConfiguration(configuration).build();

        super.registerChannel(channel, channelType, stateDescription);
    }

    @Override
    public void handleState(DateTimeStateResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(channel -> handler.updateState(channel.getUID(),
                toDateTimeState(rsp.getEpochSeconds(), rsp.getMissingState())));
    }
}
