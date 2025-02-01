package no.seime.openhab.binding.esphome.devicetest;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openhab.core.events.Event;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.events.ItemEvent;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.thing.*;
import org.openhab.core.thing.events.ThingEventFactory;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;

public class OpenhabStateToEsphomeDeviceTest extends AbstractESPHomeDeviceTest {

    protected File getEspDeviceConfigurationYamlFileName() {
        return new File("src/test/resources/device_configurations/sensor_homeassistant.yaml");
    }

    @Test
    public void testHomeAssistantSensor() throws ItemNotFoundException {

        /*
         * Expected sensors to exist in the esp device configuration file:
         * For each event type to test:
         *
         * - platform: homeassistant
         * id: <eventname_lowercase>
         * name: "<eventname>"
         * entity_id: <event type>.<eventname_lowercase>
         * device_class: temperature
         * unit_of_measurement: "°C"
         * - platform: copy
         * source_id: <event name>
         * name: "<eventname>_readback"
         *
         */

        // Item State to send initially
        State itemExistingState = new QuantityType<>(23.0, SIUnits.CELSIUS);
        // State to send via event
        State itemNewState = new QuantityType<>(24.0, SIUnits.CELSIUS);

        List<Parameter> testParameters = new ArrayList<>();

        // Items
        testParameters.add(new Parameter("ItemStateChanged_Default",
                ItemEventFactory.createStateChangedEvent("ItemStateChanged_Default", itemNewState, itemExistingState),
                itemExistingState, itemNewState));
        testParameters.add(new Parameter("ItemStateChanged",
                ItemEventFactory.createStateChangedEvent("ItemStateChanged", itemNewState, itemExistingState),
                itemExistingState, itemNewState));
        testParameters.add(new Parameter("ItemState", ItemEventFactory.createStateEvent("ItemState", itemNewState),
                itemExistingState, itemNewState));
        testParameters.add(new Parameter("ItemStateUpdated",
                ItemEventFactory.createStateUpdatedEvent("ItemStateUpdated", itemNewState), itemExistingState,
                itemNewState));
        testParameters.add(
                new Parameter("ItemCommand", ItemEventFactory.createCommandEvent("ItemCommand", (Command) itemNewState),
                        itemExistingState, itemNewState));
        testParameters.add(new Parameter("ItemStatePredicted",
                ItemEventFactory.createStatePredictedEvent("ItemStatePredicted", itemNewState, true), itemExistingState,
                itemNewState));

        // Group items
        testParameters.add(new Parameter("GroupItemStateChanged",
                ItemEventFactory.createGroupStateChangedEvent("GroupItemStateChanged", "ItemThatChanged", itemNewState,
                        itemExistingState),
                itemExistingState, itemNewState));
        testParameters.add(new Parameter("GroupStateUpdated", ItemEventFactory.createGroupStateUpdatedEvent(
                "GroupStateUpdated", "ItemThatChanged", itemNewState, "source"), itemExistingState, itemNewState));

        // Prepopulate item state so initial state can be transferred to the homeassistant sensor.
        for (Parameter parameter : testParameters) {
            if (parameter.event instanceof ItemEvent) {
                registerItem(new NumberItem(parameter.itemOrThingId), itemExistingState);
            }
        }

        // Things
        ThingStatusInfo thingNewStatusInfo = new ThingStatusInfo(ThingStatus.ONLINE, ThingStatusDetail.NONE, null);
        ThingStatusInfo thingExistingStatusInfo = new ThingStatusInfo(ThingStatus.OFFLINE, ThingStatusDetail.NONE,
                null);

        StringType thingExistingState = new StringType("OFFLINE");
        StringType thingNewState = new StringType("ONLINE");

        testParameters.add(new Parameter("astro_moon_local",
                ThingEventFactory.createStatusInfoChangedEvent(new ThingUID("astro", "moon", "local"),
                        thingNewStatusInfo, thingExistingStatusInfo),
                thingExistingState, thingNewState));
        registerThing(new ThingUID("astro", "moon", "local"), thingExistingStatusInfo);

        testParameters.add(new Parameter("astro_sun_local",
                ThingEventFactory.createStatusInfoEvent(new ThingUID("astro", "sun", "local"), thingNewStatusInfo),
                thingExistingState, thingNewState));
        registerThing(new ThingUID("astro", "sun", "local"), thingExistingStatusInfo);

        thingHandler.initialize();
        await().until(() -> thingHandler.isInterrogated());
        assertEquals(testParameters.size(), thingHandler.getDynamicChannels().size());

        // Verify that initial state is copied via the copy sensor and transferred back
        for (Parameter parameter : testParameters) {
            verify(thingHandlerCallback, timeout(2000)).stateUpdated(
                    eq(new ChannelUID(thing.getUID(), parameter.itemOrThingId.toLowerCase() + "_readback")),
                    eq(parameter.existingState));
        }

        // Send the new state to the event subscriber, verify that the copy sensor sends the new state back
        for (Parameter parameter : testParameters) {
            eventSubscriber.receive(parameter.event);
            verify(thingHandlerCallback, timeout(2000)).stateUpdated(
                    eq(new ChannelUID(thing.getUID(), parameter.itemOrThingId.toLowerCase() + "_readback")),
                    eq(parameter.newState));
        }

        thingHandler.dispose();
    }

    private static class Parameter {
        State newState;
        String itemOrThingId;
        Event event;
        State existingState;

        public Parameter(String itemOrThingId, Event event, State existingState, State newState) {
            this.itemOrThingId = itemOrThingId;
            this.event = event;
            this.existingState = existingState;
            this.newState = newState;
        }
    }
}
