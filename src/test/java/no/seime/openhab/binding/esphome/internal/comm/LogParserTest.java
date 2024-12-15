package no.seime.openhab.binding.esphome.internal.comm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.GeneratedMessage;

import io.esphome.api.ConnectResponse;

public class LogParserTest {

    private final Logger logger = LoggerFactory.getLogger(LogParserTest.class);

    @Test
    public void testParseLogfile() throws IOException, InvocationTargetException, IllegalAccessException {
        LogParser parser = new LogParser();
        List<GeneratedMessage> GeneratedMessages = parser
                .parseLog(new File("src/test/resources/logfiles/presence_sensor.log"));
        assertEquals(50, GeneratedMessages.size());
    }

    @Test
    public void testConnectResponse() {
        logger.debug("Received packet with data {}", ConnectResponse.newBuilder().build().toByteArray());
    }
}
