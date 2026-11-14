package no.seime.openhab.binding.esphome.internal.handler;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;

import io.esphome.api.DeviceInfoResponse;
import io.esphome.api.ListEntitiesDoneResponse;
import io.esphome.api.ListEntitiesRequest;
import io.esphome.api.SubscribeStatesRequest;

class ESPHomeHandlerInterrogationTest extends AbstractESPHomeHandlerTest {

    @Test
    void skipsInterrogationWhenCompilationTimeUnchanged() throws Exception {
        stubFirmwareChannels();
        handleDeviceInfo("2026-04-14T00:00:00Z");
        invokeMethod("handleConnected", new Class<?>[] { com.google.protobuf.GeneratedMessage.class },
                ListEntitiesDoneResponse.getDefaultInstance());
        verify(frameHelper, times(1)).send(ListEntitiesRequest.getDefaultInstance());

        handleDeviceInfo("2026-04-14T00:00:00Z");

        verify(frameHelper, times(1)).send(ListEntitiesRequest.getDefaultInstance());
        verify(frameHelper, times(2)).send(SubscribeStatesRequest.getDefaultInstance());
    }

    @Test
    void interrogatesWhenCompilationTimeChanged() throws Exception {
        stubFirmwareChannels();
        handleDeviceInfo("2026-04-14T00:00:00Z");
        invokeMethod("handleConnected", new Class<?>[] { com.google.protobuf.GeneratedMessage.class },
                ListEntitiesDoneResponse.getDefaultInstance());

        handleDeviceInfo("2026-04-15T00:00:00Z");

        verify(frameHelper, times(2)).send(ListEntitiesRequest.getDefaultInstance());
    }

    @Test
    void interrogatesAgainWhenPreviousInterrogationDidNotComplete() throws Exception {
        handleDeviceInfo("2026-04-14T00:00:00Z");
        handleDeviceInfo("2026-04-14T00:00:00Z");

        verify(frameHelper, times(2)).send(ListEntitiesRequest.getDefaultInstance());
    }

    private void stubFirmwareChannels() {
        when(versionService.createFirmwareUpdateAvailableChannel(thing.getUID())).thenAnswer(
                i -> ChannelBuilder.create(new ChannelUID(thing.getUID(), "firmwareUpdateAvailable")).build());
        when(versionService.createLatestFirmwareVersionChannel(thing.getUID())).thenAnswer(
                i -> ChannelBuilder.create(new ChannelUID(thing.getUID(), "latestFirmwareVersion")).build());
    }

    private void handleDeviceInfo(String compilationTime) throws Exception {
        invokeMethod("handleConnected", new Class<?>[] { com.google.protobuf.GeneratedMessage.class },
                DeviceInfoResponse.newBuilder().setCompilationTime(compilationTime).build());
    }
}
