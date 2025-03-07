package no.seime.openhab.binding.esphome.internal.comm;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.GeneratedMessage;

import io.esphome.api.*;
import no.seime.openhab.binding.esphome.deviceutil.ESPHomeLogReadingEmulator;
import no.seime.openhab.binding.esphome.internal.CommunicationListener;

public class LogReadingCommunicationListener implements CommunicationListener {

    private final Logger logger = LoggerFactory.getLogger(LogReadingCommunicationListener.class);
    private final ESPHomeLogReadingEmulator emulator;

    List<GeneratedMessage> responseMessages;

    public LogReadingCommunicationListener(ESPHomeLogReadingEmulator emulator, File logFile)
            throws IOException, InvocationTargetException, IllegalAccessException {
        this.emulator = emulator;
        LogParser logParser = new LogParser();
        responseMessages = logParser.parseLog(logFile);
    }

    @Override
    public void onPacket(GeneratedMessage message) throws IOException, ProtocolAPIError {
        if (message instanceof HelloRequest) {
            emulator.sendPacket(responseMessages.stream().filter(e -> e instanceof HelloResponse).findFirst().get());
        } else if (message instanceof DeviceInfoRequest) {
            emulator.sendPacket(
                    responseMessages.stream().filter(e -> e instanceof DeviceInfoResponse).findFirst().get());
        } else if (message instanceof ConnectRequest) {
            emulator.sendPacket(responseMessages.stream().filter(e -> e instanceof ConnectResponse).findFirst().get());
        } else if (message instanceof PingRequest) {
            emulator.sendPacket(PingResponse.getDefaultInstance());
        } else if (message instanceof DisconnectRequest) {
            emulator.sendPacket(DisconnectResponse.getDefaultInstance());
        } else if (message instanceof ListEntitiesRequest) {
            responseMessages.stream().filter(e -> e.getClass().getSimpleName().matches("ListEntities.*Response"))
                    .forEach(m -> {
                        try {
                            logger.debug("Sending list entities response {}", m);
                            emulator.sendPacket(m);
                        } catch (IOException | ProtocolAPIError e) {
                            throw new RuntimeException(e);
                        }
                    });
            logger.debug("Sending list entities done response");
            emulator.sendPacket(ListEntitiesDoneResponse.newBuilder().build());

        } else if (message instanceof SubscribeStatesRequest) {
            responseMessages.stream().filter(e -> e.getClass().getSimpleName().matches(".*StateResponse"))
                    .forEach(m -> {
                        try {
                            emulator.sendPacket(m);
                        } catch (IOException | ProtocolAPIError e) {
                            throw new RuntimeException(e);
                        }
                    });

        }
    }

    @Override
    public void onEndOfStream(String message) {
    }

    @Override
    public void onParseError(CommunicationError error) {
    }

    @Override
    public void onConnect() {
    }
}
