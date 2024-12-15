package no.seime.openhab.binding.esphome.internal.message;

import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;

import com.google.protobuf.GeneratedMessage;

import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

@ExtendWith(MockitoExtension.class)
public class ClimateMessageHandlerTest {

    @Mock
    ESPHomeHandler handler;

    @Test
    public void testAggregateCommand() throws InterruptedException, ProtocolAPIError {
        ClimateMessageHandler messageHandler = new ClimateMessageHandler(handler);

        Configuration fanModeConfig = new Configuration();
        fanModeConfig.put(BindingConstants.COMMAND_KEY, 1);
        fanModeConfig.put(BindingConstants.COMMAND_FIELD, "custom_fan_mode");
        Channel customFanModeChannel = ChannelBuilder.create(new ChannelUID("esphome:device:1:custom_fan_mode"))
                .withConfiguration(fanModeConfig).build();

        Configuration presetConfig = new Configuration();
        presetConfig.put(BindingConstants.COMMAND_KEY, 1);
        presetConfig.put(BindingConstants.COMMAND_FIELD, ClimateMessageHandler.CHANNEL_CUSTOM_PRESET);
        Channel customPresetChannel = ChannelBuilder.create(new ChannelUID("esphome:device:1:custom_preset"))
                .withConfiguration(presetConfig).build();

        messageHandler.handleCommand(customFanModeChannel, new StringType("mode1"), 1);
        messageHandler.handleCommand(customPresetChannel, new StringType("presetmode1"), 1);

        Thread.sleep(500);
        verify(handler).sendMessage(isA(GeneratedMessage.class));
    }
}
