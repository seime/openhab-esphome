package no.seime.openhab.binding.esphome.internal.message;

import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.internal.ThingImpl;

import io.esphome.api.ListEntitiesSensorResponse;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

@ExtendWith(MockitoExtension.class)
public class InvalidDeviceClassTest {

    @Mock
    ESPHomeHandler handler;

    @Mock
    ThingImpl thing;

    @Test
    public void testInvalidDeviceClass() {
        when(thing.getUID()).thenReturn(new ThingUID("esphome:device:1"));
        when(handler.getThing()).thenReturn(thing);
        when(handler.getLogPrefix()).thenReturn("test");

        SensorMessageHandler sensorMessageHandler = new SensorMessageHandler(handler);
        ListEntitiesSensorResponse entityRsp = ListEntitiesSensorResponse.newBuilder().setDeviceClass("invalid")
                .setName("name").setObjectId("objectid").build();
        sensorMessageHandler.buildChannels(entityRsp);
    }
}
