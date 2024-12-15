package no.seime.openhab.binding.esphome.internal.comm;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import com.google.protobuf.GeneratedMessage;

import io.esphome.api.Api;

public class MessageTypeToClassConverter {

    private final Map<Integer, Method> messageTypeToMessageClass = new HashMap<>();

    public MessageTypeToClassConverter() {
        Api.getDescriptor().getMessageTypes().forEach(messageDescriptor -> {
            try {
                int id = messageDescriptor.getOptions().getExtension(io.esphome.api.ApiOptions.id);
                if (id > 0) {
                    Class<? extends GeneratedMessage> subclass = Class.forName(messageDescriptor.getFullName())
                            .asSubclass(GeneratedMessage.class);
                    Method parseMethod = subclass.getDeclaredMethod("parseFrom", byte[].class);

                    messageTypeToMessageClass.put(id, parseMethod);
                }
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public Method getMethod(int id) {
        return messageTypeToMessageClass.get(id);
    }
}
