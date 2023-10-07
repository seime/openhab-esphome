package no.seime.openhab.binding.esphome.internal.comm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.protobuf.GeneratedMessageV3;

import no.seime.openhab.binding.esphome.internal.internal.comm.MessageTypeToClassConverter;

public class LogParser {

    private MessageTypeToClassConverter messageTypeToClassConverter = new MessageTypeToClassConverter();

    public List<GeneratedMessageV3> parseLog(File log)
            throws IOException, InvocationTargetException, IllegalAccessException {
        List<GeneratedMessageV3> messages = new ArrayList<>();

        FileReader reader = new FileReader(log);
        BufferedReader bufferedReader = new BufferedReader(reader);

        Pattern p = Pattern.compile(".*Received packet of type ([0-9]+) with data (\\[.*\\])");

        String line;
        while ((line = bufferedReader.readLine()) != null) {

            Matcher m = p.matcher(line);
            if (m.matches()) {
                String messageType = m.group(1);
                String messageData = m.group(2);

                GeneratedMessageV3 generatedMessageV3 = parseMessage(messageType, messageData);
                if (generatedMessageV3 != null) {
                    messages.add(generatedMessageV3);
                }
            }
        }

        return messages;
    }

    private GeneratedMessageV3 parseMessage(String messageType, String messageData)
            throws InvocationTargetException, IllegalAccessException {
        Integer type = Integer.parseInt(messageType);
        Method parseMethod = messageTypeToClassConverter.getMethod(type);

        if (parseMethod != null) {
            GeneratedMessageV3 invoke = (GeneratedMessageV3) parseMethod.invoke(null, fromString(messageData));
            if (invoke != null) {
                return invoke;

            }
        }
        return null;
    }

    private static byte[] fromString(String string) {
        if ("[]".equals(string)) {
            return new byte[0];
        }

        String[] strings = string.replace("[", "").replace("]", "").split(", ");
        byte result[] = new byte[strings.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(strings[i]);
        }
        return result;
    }
}
