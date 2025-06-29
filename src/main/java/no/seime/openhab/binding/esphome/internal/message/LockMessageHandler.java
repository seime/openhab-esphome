package no.seime.openhab.binding.esphome.internal.message;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.openhab.core.types.CommandDescription;
import org.openhab.core.types.State;
import org.openhab.core.types.StateDescription;
import org.openhab.core.types.UnDefType;
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
                    "[{}] Cannot send command '{}' to lock channel {}, invalid command. Valid values are LOCK, UNLOCK and if available, OPEN",
                    handler.getLogPrefix(), command, channel.getUID());
        }
    }

    public void buildChannels(ListEntitiesLockResponse rsp) {
        Configuration configuration = configuration(rsp.getKey(), null, "Lock");

        String icon = getChannelIcon(rsp.getIcon(), "lock");

        List<String> stateOptions = new ArrayList<>();
        stateOptions.add(LockMessageHandler.stripEnumPrefix(LockState.LOCK_STATE_LOCKED));
        stateOptions.add(LockMessageHandler.stripEnumPrefix(LockState.LOCK_STATE_UNLOCKED));
        stateOptions.add(LockMessageHandler.stripEnumPrefix(LockState.LOCK_STATE_JAMMED));
        stateOptions.add(LockMessageHandler.stripEnumPrefix(LockState.LOCK_STATE_LOCKING));
        stateOptions.add(LockMessageHandler.stripEnumPrefix(LockState.LOCK_STATE_UNLOCKING));

        List<String> commandOptions = new ArrayList<>();
        commandOptions.add(LockMessageHandler.stripEnumPrefix(LockCommand.LOCK_UNLOCK));
        commandOptions.add(LockMessageHandler.stripEnumPrefix(LockCommand.LOCK_LOCK));

        if (rsp.getSupportsOpen()) {
            commandOptions.add(stripEnumPrefix(LockCommand.LOCK_OPEN));
        }

        ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), "String", Set.of("Lock"), icon,
                rsp.getEntityCategory(), rsp.getDisabledByDefault());
        StateDescription stateDescription = addStateDescription(stateOptions);
        CommandDescription commandDescription = addCommandDescription(commandOptions);

        Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                .withAcceptedItemType("String").withConfiguration(configuration).build();

        super.registerChannel(channel, channelType, stateDescription, commandDescription);
    }

    public void handleState(LockStateResponse rsp) {
        LockState lockState = rsp.getState();
        State state;
        switch (lockState) {
            case LOCK_STATE_NONE:
                state = UnDefType.NULL;
                break;
            case UNRECOGNIZED:
                state = UnDefType.UNDEF;
                break;
            default:
                state = new StringType(stripEnumPrefix(lockState));
        }
        findChannelByKey(rsp.getKey()).ifPresent(channel -> handler.updateState(channel.getUID(), state));
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
