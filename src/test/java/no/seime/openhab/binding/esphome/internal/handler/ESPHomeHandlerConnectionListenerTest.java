package no.seime.openhab.binding.esphome.internal.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import io.esphome.api.HomeassistantActionRequest;
import io.esphome.api.PingRequest;
import io.esphome.api.PingResponse;
import io.esphome.api.SubscribeHomeAssistantStateResponse;
import no.seime.openhab.binding.esphome.internal.*;
import no.seime.openhab.binding.esphome.internal.comm.CommunicationError;

class ESPHomeHandlerConnectionListenerTest extends AbstractESPHomeHandlerTest {

    @Test
    void closedBootstrapCallbacksCannotAffectReplacementConnection() throws Exception {
        setConnected();
        setField("provisioningConnection", true);
        CommunicationListener stale = (CommunicationListener) invokeMethod("createConnectionListener",
                new Class<?>[] { long.class }, getField("connectionGeneration"));
        handler.onEndOfStream("bootstrap closed");
        invokeMethod("cancelConnectFuture", new Class<?>[0]);
        setConnected();
        setField("provisioningConnection", false);
        setField("frameHelper", frameHelper);
        clearInvocations(frameHelper, callback, eventSubscriber);

        stale.onConnect();
        stale.onPacket(HomeassistantActionRequest.newBuilder().setService("test.event").setIsEvent(true).build());
        stale.onPacket(SubscribeHomeAssistantStateResponse.getDefaultInstance());
        stale.onPacket(PingRequest.getDefaultInstance());
        stale.onEndOfStream("stale EOF");
        stale.onParseError(CommunicationError.PACKET_ERROR);

        verifyNoInteractions(frameHelper, callback, eventSubscriber, eventPublisher);
        CommunicationListener current = (CommunicationListener) invokeMethod("createConnectionListener",
                new Class<?>[] { long.class }, getField("connectionGeneration"));
        current.onPacket(PingRequest.getDefaultInstance());
        verify(frameHelper).send(PingResponse.getDefaultInstance());
    }

    @Test
    void replacedConnectionCallbacksAreIgnoredEvenWithoutDisconnect() throws Exception {
        CommunicationListener stale = (CommunicationListener) invokeMethod("createConnectionListener",
                new Class<?>[] { long.class }, getField("connectionGeneration"));
        setField("connectionGeneration", (long) getField("connectionGeneration") + 1);
        setConnected();
        stale.onConnect();
        stale.onPacket(PingRequest.getDefaultInstance());
        stale.onEndOfStream("stale EOF");
        stale.onParseError(CommunicationError.PACKET_ERROR);
        verifyNoInteractions(frameHelper, callback);
    }

    @Test
    void disconnectResponseInvalidatesConnectionCallbacks() throws Exception {
        setConnected();
        CommunicationListener listener = (CommunicationListener) invokeMethod("createConnectionListener",
                new Class<?>[] { long.class }, getField("connectionGeneration"));
        listener.onPacket(io.esphome.api.DisconnectResponse.getDefaultInstance());
        verify(frameHelper).close();
        clearInvocations(frameHelper);
        listener.onConnect();
        listener.onPacket(PingRequest.getDefaultInstance());
        listener.onParseError(CommunicationError.PACKET_ERROR);
        listener.onEndOfStream("closed");
        verifyNoInteractions(frameHelper, callback);
    }
}
