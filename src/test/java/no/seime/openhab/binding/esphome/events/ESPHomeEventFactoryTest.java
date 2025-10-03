package no.seime.openhab.binding.esphome.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class ESPHomeEventFactoryTest {

    @Test
    void createTagScannedEvent() {
        TagScannedEvent event = ESPHomeEventFactory.createTagScannedEvent("device123", "tag456");
        assertEquals("openhab/esphome/tag_scanned", event.getTopic());
        assertEquals("tag456", event.getTagId());
        assertEquals("device123", event.getSource());
    }

    @Test
    void createActionEvent() {
        Map<String, String> data = new HashMap<>();
        data.put("key1", "value1");
        data.put("key2", "value2");

        Map<String, String> dataTemplate = new HashMap<>();
        dataTemplate.put("template1", "templateValue1");

        Map<String, String> variables = new HashMap<>();
        variables.put("var1", "varValue1");

        ActionEvent event = ESPHomeEventFactory.createActionEvent("testDevice", "testAction", data, dataTemplate,
                variables);

        assertEquals("openhab/esphome/action/testAction", event.getTopic());
        assertEquals("testDevice", event.getSource());
        assertEquals("testAction", event.getAction());
        assertEquals(data, event.getData());
        assertEquals(dataTemplate, event.getDataTemplate());
        assertEquals(variables, event.getVariables());
    }

    @Test
    void createEventEvent() {
        Map<String, String> data = new HashMap<>();
        data.put("eventKey", "eventValue");

        Map<String, String> dataTemplate = new HashMap<>();
        dataTemplate.put("eventTemplate", "eventTemplateValue");

        Map<String, String> variables = new HashMap<>();
        variables.put("eventVar", "eventVarValue");

        EventEvent event = ESPHomeEventFactory.createEventEvent("testEventDevice", "testEventAction", data,
                dataTemplate, variables);

        assertEquals("openhab/esphome/event/testEventAction", event.getTopic());
        assertEquals("testEventDevice", event.getSource());
        assertEquals("testEventAction", event.getEvent());
        assertEquals(data, event.getData());
        assertEquals(dataTemplate, event.getDataTemplate());
        assertEquals(variables, event.getVariables());
    }
}
