package no.seime.openhab.binding.esphome.internal.message;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StopMoveType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingUID;

import com.google.protobuf.GeneratedMessage;

import io.esphome.api.CoverCommandRequest;
import io.esphome.api.ListEntitiesCoverResponse;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

class CoverMessageHandlerTest {

    private static final int KEY = 42;

    private final ESPHomeHandler handler = mock(ESPHomeHandler.class);
    private final List<Channel> channels = new ArrayList<>();
    private CoverMessageHandler coverHandler;

    @BeforeEach
    void setUp() {
        Thing thing = mock(Thing.class);
        when(thing.getUID()).thenReturn(new ThingUID("esphome", "device", "test"));
        when(thing.getProperties()).thenReturn(Map.of());
        when(handler.getThing()).thenReturn(thing);
        when(handler.getDynamicChannels()).thenReturn(channels);
        when(handler.getLogPrefix()).thenReturn("test");
        doAnswer(invocation -> channels.add(invocation.getArgument(0))).when(handler).addChannel(any());
        coverHandler = new CoverMessageHandler(handler);
    }

    private void build(boolean supportsTilt, boolean supportsStopTilt, boolean supportsToggle) {
        coverHandler.buildChannels(ListEntitiesCoverResponse.newBuilder().setKey(KEY).setName("Blind")
                .setSupportsPosition(true).setSupportsStop(true).setSupportsTilt(supportsTilt)
                .setSupportsStopTilt(supportsStopTilt).setSupportsToggle(supportsToggle).build());
    }

    private Optional<Channel> channel(String field) {
        return channels.stream().filter(c -> c.getUID().getId().endsWith("#" + field)).findFirst();
    }

    private CoverCommandRequest sentCommand() throws Exception {
        ArgumentCaptor<GeneratedMessage> captor = ArgumentCaptor.forClass(GeneratedMessage.class);
        verify(handler).sendMessage(captor.capture());
        return (CoverCommandRequest) captor.getValue();
    }

    @Test
    void toggleChannelOnlyWhenSupported() {
        build(false, false, false);
        assertTrue(channel(CoverMessageHandler.CHANNEL_TOGGLE).isEmpty());

        channels.clear();
        build(false, false, true);
        assertTrue(channel(CoverMessageHandler.CHANNEL_TOGGLE).isPresent());
    }

    @Test
    void toggleSendsToggleAndResetsSwitch() throws Exception {
        build(false, false, true);
        Channel toggle = channel(CoverMessageHandler.CHANNEL_TOGGLE).orElseThrow();

        coverHandler.handleCommand(toggle, OnOffType.ON, KEY);

        CoverCommandRequest request = sentCommand();
        assertTrue(request.getToggle());
        assertFalse(request.getStop());
        assertFalse(request.getHasPosition());
        verify(handler).updateState(toggle.getUID(), OnOffType.OFF);
    }

    @Test
    void toggleIgnoresNonSwitchCommands() throws Exception {
        build(false, false, true);
        coverHandler.handleCommand(channel(CoverMessageHandler.CHANNEL_TOGGLE).orElseThrow(), new StringType("x"), KEY);
        verify(handler, never()).sendMessage(any());
    }

    @Test
    void stopOnTiltSendsStopTiltWhenSupported() throws Exception {
        build(true, true, false);

        coverHandler.handleCommand(channel(CoverMessageHandler.CHANNEL_TILT).orElseThrow(), StopMoveType.STOP, KEY);

        CoverCommandRequest request = sentCommand();
        assertTrue(request.getStopTilt());
        assertFalse(request.getStop());
    }

    @Test
    void stopOnTiltFallsBackToStopWhenStopTiltUnsupported() throws Exception {
        build(true, false, false);

        coverHandler.handleCommand(channel(CoverMessageHandler.CHANNEL_TILT).orElseThrow(), StopMoveType.STOP, KEY);

        CoverCommandRequest request = sentCommand();
        assertTrue(request.getStop());
        assertFalse(request.getStopTilt());
    }

    @Test
    void stopOnPositionStillStopsEverything() throws Exception {
        build(true, true, false);

        coverHandler.handleCommand(channel(CoverMessageHandler.CHANNEL_POSITION).orElseThrow(), StopMoveType.STOP, KEY);

        CoverCommandRequest request = sentCommand();
        assertTrue(request.getStop());
        assertFalse(request.getStopTilt());
    }
}
