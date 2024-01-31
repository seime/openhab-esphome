package no.seime.openhab.binding.esphome.internal.util;

import org.openhab.core.thing.Channel;
import org.openhab.core.thing.type.ChannelType;

public class Debug {
    public static String channelToString(Channel channel) {
        var sb = beginObject(channel);
        field(sb, channel.getUID(), "UID");
        field(sb, channel.getChannelTypeUID(), "typeUID");
        field(sb, channel.getLabel(), "Label");
        field(sb, channel.getAcceptedItemType(), "acceptedItemType");
        field(sb, channel.getConfiguration(), "configuration");
        return endObject(sb, channel.getProperties(), "props");
    }

    public static String channelTypeToString(ChannelType channelType) {
        var sb = beginObject(channelType);
        field(sb, channelType.getUID(), "UID");
        field(sb, channelType.getItemType(), "itemType");
        return endObject(sb, channelType.getCommandDescription(), "commandDescr");
    }

    private static StringBuilder beginObject(Object obj) {
        StringBuilder sb = new StringBuilder();
        sb.append(obj.getClass().getSimpleName()).append(" {");
        return sb;
    }

    private static String endObject(StringBuilder sb, Object value, String name) {
        return field(sb, value, name, false).append("}").toString();
    }

    private static void field(StringBuilder sb, Object value, String name) {
        field(sb, value, name, true);
    }

    private static StringBuilder field(StringBuilder sb, Object value, String name, boolean appendComma) {
        sb.append(name).append("=").append(value);
        if (appendComma) {
            sb.append(", ");
        }
        return sb;
    }
}
