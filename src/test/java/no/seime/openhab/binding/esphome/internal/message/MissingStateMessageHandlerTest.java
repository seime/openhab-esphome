package no.seime.openhab.binding.esphome.internal.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.types.UnDefType;

import io.esphome.api.FanStateResponse;
import io.esphome.api.SelectStateResponse;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

class MissingStateMessageHandlerTest {
    private final ESPHomeHandler handler = mock(ESPHomeHandler.class);
    private final Channel channel = mock(Channel.class);

    @Test
    void missingNumericStateIsNullEvenWhenValueIsNaN() {
        SensorMessageHandler sensor = new SensorMessageHandler(handler);
        assertEquals(UnDefType.NULL, sensor.toNumericState(channel, 42, true));
        assertEquals(UnDefType.NULL, sensor.toNumericState(channel, Float.NaN, true));
        assertEquals(UnDefType.UNDEF, sensor.toNumericState(channel, Float.NaN, false));

        when(channel.getConfiguration()).thenReturn(new Configuration());
        assertEquals(new DecimalType(42), sensor.toNumericState(channel, 42, false));
    }

    @Test
    void missingDateTimeStateIsNullAndEpochZeroIsValid() {
        DateTimeMessageHandler dateTime = new DateTimeMessageHandler(handler);
        assertEquals(UnDefType.NULL, dateTime.toDateTimeState(0, true));
        assertEquals(new DateTimeType(ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.systemDefault())),
                dateTime.toDateTimeState(0, false));
    }

    @Test
    void missingBinaryStateIsNullForEitherBooleanValue() {
        BinarySensorMessageHandler binary = new BinarySensorMessageHandler(handler);
        assertEquals(UnDefType.NULL, binary.toBinaryState(channel, false, true));
        assertEquals(UnDefType.NULL, binary.toBinaryState(channel, true, true));

        when(channel.getConfiguration()).thenReturn(new Configuration());
        assertEquals(OnOffType.OFF, binary.toBinaryState(channel, false, false));
        assertEquals(OnOffType.ON, binary.toBinaryState(channel, true, false));
    }

    @Test
    void missingDateStateIsNullEvenWithDefaultDateFields() {
        DateMessageHandler date = new DateMessageHandler(handler);
        assertEquals(UnDefType.NULL, date.toDateState(0, 0, 0, true));
        assertEquals(new DateTimeType(ZonedDateTime.of(2025, 6, 28, 0, 0, 0, 0, ZoneId.systemDefault())),
                date.toDateState(2025, 6, 28, false));
    }

    @Test
    void missingTimeStateIsNullAndMidnightIsValid() {
        TimeMessageHandler time = new TimeMessageHandler(handler);
        assertEquals(UnDefType.NULL, time.toTimeState(0, 0, 0, true));
        assertEquals(new QuantityType<>(0, Units.SECOND), time.toTimeState(0, 0, 0, false));
    }

    @Test
    void missingTextStateIsNullAndEmptyTextIsValid() {
        TextMessageHandler text = new TextMessageHandler(handler);
        TextSensorMessageHandler sensor = new TextSensorMessageHandler(handler);
        for (String value : List.of("", "hello")) {
            assertEquals(UnDefType.NULL, text.toTextState(value, true));
            assertEquals(new StringType(value), text.toTextState(value, false));
            assertEquals(UnDefType.NULL, sensor.toTextState(value, true));
            assertEquals(new StringType(value), sensor.toTextState(value, false));
        }
    }

    @Test
    void selectPublishesNullForMissingStateAndPreservesPresentText() {
        ChannelUID uid = configureChannel(null);
        SelectMessageHandler select = new SelectMessageHandler(handler);
        select.handleState(SelectStateResponse.newBuilder().setKey(7).setState("option").setMissingState(true).build());
        verify(handler).updateState(uid, UnDefType.NULL);

        for (String value : List.of("", "option")) {
            select.handleState(SelectStateResponse.newBuilder().setKey(7).setState(value).build());
            verify(handler).updateState(uid, new StringType(value));
        }
    }

    @Test
    void fanPublishesNullForEmptyPresetAndPreservesNamedPreset() {
        ChannelUID uid = configureChannel(FanMessageHandler.CHANNEL_PRESET);
        FanMessageHandler fan = new FanMessageHandler(handler);
        fan.handleState(FanStateResponse.newBuilder().setKey(7).build());
        verify(handler).updateState(uid, UnDefType.NULL);

        fan.handleState(FanStateResponse.newBuilder().setKey(7).setPresetMode("breeze").build());
        verify(handler).updateState(uid, new StringType("breeze"));
    }

    private ChannelUID configureChannel(String field) {
        Thing thing = mock(Thing.class);
        ChannelUID uid = new ChannelUID("esphome:device:test:state");
        Configuration configuration = new Configuration();
        configuration.put(BindingConstants.CHANNEL_CONFIGURATION_ENTITY_KEY, BigDecimal.valueOf(7));
        if (field != null) {
            configuration.put(BindingConstants.CHANNEL_CONFIGURATION_ENTITY_FIELD, field);
        }
        when(handler.getThing()).thenReturn(thing);
        when(thing.getChannels()).thenReturn(List.of(channel));
        when(channel.getUID()).thenReturn(uid);
        when(channel.getConfiguration()).thenReturn(configuration);
        return uid;
    }
}
