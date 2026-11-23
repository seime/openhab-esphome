package no.seime.openhab.binding.esphome.internal.message;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventPublisher;

import io.esphome.api.HomeassistantActionRequest;
import io.esphome.api.HomeassistantServiceMap;
import no.seime.openhab.binding.esphome.events.ActionEvent;
import no.seime.openhab.binding.esphome.events.EventEvent;
import no.seime.openhab.binding.esphome.events.TagScannedEvent;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.ESPHomeEventSubscriber;

@ExtendWith(MockitoExtension.class)
class HomeAssistantBridgeTest {

    @Mock
    private ESPHomeHandler handler;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private ESPHomeEventSubscriber eventSubscriber;

    private HomeAssistantBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new HomeAssistantBridge(handler, eventPublisher, eventSubscriber);
    }

    @Test
    void postsTagScannedEventForPlainTagScan() {
        TagScannedEvent event = (TagScannedEvent) post(HomeassistantActionRequest.newBuilder()
                .setService("esphome.tag_scanned").setIsEvent(true).addData(entry("tag_id", "abc")).build());

        assertEquals("abc", event.getTagId());
    }

    @Test
    void postsGenericEventForTagScanWithExtraData() {
        Event event = post(HomeassistantActionRequest.newBuilder().setService("esphome.tag_scanned").setIsEvent(true)
                .addData(entry("tag_id", "abc")).addVariables(entry("x", "1")).build());

        assertInstanceOf(EventEvent.class, event);
    }

    @Test
    void postsActionEventForActions() {
        ActionEvent event = (ActionEvent) post(
                HomeassistantActionRequest.newBuilder().setService("light.turn_on").build());

        assertEquals("light.turn_on", event.getAction());
    }

    private Event post(HomeassistantActionRequest request) {
        bridge.onActionRequest(request, "device");
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher).post(captor.capture());
        return captor.getValue();
    }

    private static HomeassistantServiceMap entry(String key, String value) {
        return HomeassistantServiceMap.newBuilder().setKey(key).setValue(value).build();
    }
}
