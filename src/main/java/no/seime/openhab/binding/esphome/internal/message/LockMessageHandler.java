package no.seime.openhab.binding.esphome.internal.message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.*;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class LockMessageHandler extends AbstractMessageHandler<ListEntitiesLockResponse, LockStateResponse> {

    private final Logger logger = LoggerFactory.getLogger(LockMessageHandler.class);

    public LockMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {
        try {
            LockCommand lockCommand = toLockCommand(command.toString());
            handler.sendMessage(LockCommandRequest.newBuilder().setKey(key).setCommand(lockCommand).build());
        } catch (IllegalArgumentException e) {
            logger.warn(
                    "Cannot send command '{}' to lock channel {}, invalid command. Valid values are LOCK, UNLOCK and if available, OPEN",
                    command, channel.getUID());
        }
    }

    public void buildChannels(ListEntitiesLockResponse rsp) {
        Configuration configuration = configuration(rsp.getKey(), null, "Lock");

        String icon = getChannelIcon(rsp.getIcon(), "lock");

        List<String> validOptions = new ArrayList<>();
        validOptions.addAll(Arrays.stream(LockState.values()).map(LockMessageHandler::stripEnumPrefix)
                .collect(Collectors.toList()));
        validOptions.addAll(Arrays.stream(LockCommand.values()).map(LockMessageHandler::stripEnumPrefix)
                .collect(Collectors.toList()));

        if (!rsp.getSupportsOpen()) {
            validOptions.remove(stripEnumPrefix(LockCommand.LOCK_OPEN));
        }

        ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), "String", validOptions, null,
                Set.of("Lock"), false, icon, null, null, null, rsp.getEntityCategory(), rsp.getDisabledByDefault());

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType("String").withConfiguration(configuration).build();

        super.registerChannel(channel, channelType);
    }

    public void handleState(LockStateResponse rsp) {
        findChannelByKey(rsp.getKey()).ifPresent(
                channel -> handler.updateState(channel.getUID(), new StringType(stripEnumPrefix(rsp.getState()))));
    }

    public static String stripEnumPrefix(LockState lockState) {
        String toRemove = "LOCK_STATE";
        return lockState.toString().substring(toRemove.length() + 1);
    }

    public static String stripEnumPrefix(LockCommand lockState) {
        String toRemove = "LOCK";
        return lockState.toString().substring(toRemove.length() + 1);
    }

    public static LockCommand toLockCommand(String lockCommand) {
        return LockCommand.valueOf("LOCK_" + lockCommand.toUpperCase());
    }
}
