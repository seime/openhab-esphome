package no.seime.openhab.binding.esphome.devicetest;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.*;

import no.seime.openhab.binding.esphome.events.ESPHomeEventFactory;

public class ActionsEsphomeDeviceTest extends AbstractESPHomeDeviceTest {

    protected File getEspDeviceConfigurationYamlFileName() {
        return new File("src/test/resources/device_configurations/actions.yaml");
    }

    @Test
    public void testAction() throws ItemNotFoundException {
        deviceConfiguration.allowActions = true;
        thingHandler.initialize();
        await().until(() -> thingHandler.isInterrogated());

        thingHandler.handleCommand(new ChannelUID(thing.getUID(), "trigger_action"), OnOffType.ON);

        verify(eventPublisher, timeout(2000)).post(ESPHomeEventFactory.createActionEvent("virtual", "some.action",
                Map.of("entity_id", "Something"), Map.of(), Map.of()));

        thingHandler.dispose();
    }

    @Test
    public void testEvent() throws ItemNotFoundException {
        deviceConfiguration.allowActions = true;
        thingHandler.initialize();
        await().until(() -> thingHandler.isInterrogated());

        thingHandler.handleCommand(new ChannelUID(thing.getUID(), "trigger_event"), OnOffType.ON);

        verify(eventPublisher, timeout(2000)).post(
                ESPHomeEventFactory.createEventEvent("virtual", "esphome.something", Map.of(), Map.of(), Map.of()));

        thingHandler.dispose();
    }

    @Test
    public void testTag() throws ItemNotFoundException {
        deviceConfiguration.allowActions = true;
        thingHandler.initialize();
        await().until(() -> thingHandler.isInterrogated());

        thingHandler.handleCommand(new ChannelUID(thing.getUID(), "trigger_tag"), OnOffType.ON);

        verify(eventPublisher, timeout(2000)).post(ESPHomeEventFactory.createTagScannedEvent("virtual", "mytag"));

        thingHandler.dispose();
    }
}
