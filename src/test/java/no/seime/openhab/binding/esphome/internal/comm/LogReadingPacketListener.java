package no.seime.openhab.binding.esphome.internal.comm;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.GeneratedMessageV3;

import io.esphome.api.ConnectRequest;
import io.esphome.api.ConnectResponse;
import io.esphome.api.DeviceInfoRequest;
import io.esphome.api.DeviceInfoResponse;
import io.esphome.api.DisconnectRequest;
import io.esphome.api.DisconnectResponse;
import io.esphome.api.HelloRequest;
import io.esphome.api.HelloResponse;
import io.esphome.api.ListEntitiesDoneResponse;
import io.esphome.api.ListEntitiesRequest;
import io.esphome.api.PingRequest;
import io.esphome.api.PingResponse;
import io.esphome.api.SubscribeStatesRequest;
import no.seime.openhab.binding.esphome.internal.ESPHomeEmulator;
import no.seime.openhab.binding.esphome.internal.internal.PacketListener;

public class LogReadingPacketListener implements PacketListener {

    private final Logger logger = LoggerFactory.getLogger(LogReadingPacketListener.class);
    private final ESPHomeEmulator emulator;

    List<GeneratedMessageV3> responseMessages;

    public LogReadingPacketListener(ESPHomeEmulator emulator, File logFile)
            throws IOException, InvocationTargetException, IllegalAccessException {
        this.emulator = emulator;
        LogParser logParser = new LogParser();
        responseMessages = logParser.parseLog(logFile);
    }

    @Override
    public void onPacket(GeneratedMessageV3 message) throws IOException {
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
                        } catch (IOException e) {
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
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });

        }
    }

    @Override
    public void onEndOfStream() {
    }

    @Override
    public void onParseError() {
    }
}
