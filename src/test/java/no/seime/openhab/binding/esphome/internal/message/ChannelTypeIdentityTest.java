package no.seime.openhab.binding.esphome.internal.message;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openhab.core.thing.type.ChannelTypeUID;

import io.esphome.api.EntityCategory;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

class ChannelTypeIdentityTest {
    private ChannelTypeUID type(String label, String itemType, Set<String> tags, String category, boolean advanced) {
        AbstractMessageHandler<?, ?> handler = mock(AbstractMessageHandler.class,
                withSettings().useConstructor(mock(ESPHomeHandler.class)).defaultAnswer(CALLS_REAL_METHODS));
        return handler.addChannelType(label, itemType, tags, category, EntityCategory.ENTITY_CATEGORY_NONE, advanced)
                .getUID();
    }

    @Test
    void identicalPropertiesShareTypesAcrossHandlersAndTagOrder() {
        assertEquals(
                type("Temperature", "Number", new LinkedHashSet<>(List.of("Measurement", "Temperature")), "temperature",
                        false),
                type("Temperature", "Number", new LinkedHashSet<>(List.of("Temperature", "Measurement")), "temperature",
                        false));
        assertEquals(type("Temperature", "Number", null, null, false),
                type("Temperature", "Number", Set.of(), null, false));
    }

    @Test
    void differentPropertiesHaveDifferentTypes() {
        ChannelTypeUID baseline = type("Temperature", "Number", Set.of("Measurement"), "temperature", false);
        assertNotEquals(baseline, type("Humidity", "Number", Set.of("Measurement"), "temperature", false));
        assertNotEquals(baseline, type("Temperature", "String", Set.of("Measurement"), "temperature", false));
        assertNotEquals(baseline, type("Temperature", "Number", Set.of("Control"), "temperature", false));
        assertNotEquals(baseline, type("Temperature", "Number", Set.of("Measurement"), "humidity", false));
        assertNotEquals(baseline, type("Temperature", "Number", Set.of("Measurement"), "temperature", true));
    }
}
