package no.seime.openhab.binding.esphome.internal.message;

import static no.seime.openhab.binding.esphome.internal.message.LightMessageHandler.LightColorCapability.ON_OFF;
import static no.seime.openhab.binding.esphome.internal.message.LightMessageHandler.LightColorCapability.RGB;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import no.seime.openhab.binding.esphome.internal.message.LightMessageHandler.LightColorCapability;

public class LightMessageHandlerTest {

    @Test
    public void testEncodeDecodeSingle() {
        String single = LightMessageHandler.serialize(new TreeSet<>(Set.of(RGB)));
        assertEquals("RGB", single);

        SortedSet<LightColorCapability> deserialize = LightMessageHandler.deserialize(single);
        assertEquals(Set.of(RGB), deserialize);
    }

    @Test
    public void testEncodeDecodeMultiple() {

        String dual = LightMessageHandler.serialize(new TreeSet<>(Set.of(RGB, ON_OFF)));
        assertEquals("ON_OFF,RGB", dual);

        SortedSet<LightColorCapability> deserialize = LightMessageHandler.deserialize(dual);
        assertEquals(Set.of(RGB, ON_OFF), deserialize);
    }
}
