package no.seime.openhab.binding.esphome.internal.message;

import io.esphome.api.DateCommandRequest;
import io.esphome.api.DateStateResponse;
import io.esphome.api.ListEntitiesDateResponse;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Set;

public class DateMessageHandler extends AbstractMessageHandler<ListEntitiesDateResponse, DateStateResponse> {

    private final Logger logger = LoggerFactory.getLogger(DateMessageHandler.class);

    public DateMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        if (command instanceof DateTimeType date) {
            ZonedDateTime zonedDateTime = date.getZonedDateTime();
            handler.sendMessage(DateCommandRequest.newBuilder().setKey(key).setYear(zonedDateTime.getYear())
                    .setMonth(zonedDateTime.getMonthValue()).setDay(zonedDateTime.getDayOfMonth()).build());
        } else {
            logger.warn("Unsupported command type: {}", command);
        }
    }

    @Override
    public void buildChannels(ListEntitiesDateResponse rsp) {
        Configuration configuration = configuration(rsp.getKey(), null, "Date");

        String icon = getChannelIcon(rsp.getIcon(), "time");

        String itemType = "DateTime";
        ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), itemType, Collections.emptySet(),
                "%1$tY-%1$tm-%1$td", Set.of("Status"), false, icon, null, null, null, rsp.getEntityCategory());

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType(itemType).withConfiguration(configuration).build();

        super.registerChannel(channel, channelType);
    }

    @Override
    public void handleState(DateStateResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(channel -> handler.updateState(channel.getUID(),
                toDateState(rsp.getYear(), rsp.getMonth(), rsp.getDay(), rsp.getMissingState())));
    }

    protected State toDateState(int year, int month, int date, boolean missingState) {
        if (missingState) {
            return UnDefType.UNDEF;
        } else {
            return new DateTimeType(ZonedDateTime.of(year, month, date, 0, 0, 0, 0, ZoneId.systemDefault()));
        }
    }
}
