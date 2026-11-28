package no.seime.openhab.binding.esphome.internal.bluetooth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingUID;

import io.esphome.api.BluetoothConnectionsFreeResponse;
import no.seime.openhab.binding.esphome.internal.MonitoredCompositeExecutorService;
import no.seime.openhab.binding.esphome.internal.bluetooth.ESPHomeBluetoothProxyHandler.ConnectionCandidate;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class ESPHomeBluetoothProxyHandlerTest {

    private static final long ADDRESS = 0xE48501042595L;

    private final ThingRegistry thingRegistry = mock(ThingRegistry.class);
    private ESPHomeBluetoothProxyHandler proxy;
    private ESPHomeHandler espA;
    private ESPHomeHandler espB;
    private ESPHomeHandler espC;

    @BeforeEach
    void setUp() {
        proxy = new ESPHomeBluetoothProxyHandler(mock(Bridge.class), thingRegistry,
                mock(MonitoredCompositeExecutorService.class));
        espA = registerEsp("esphome:device:a");
        espB = registerEsp("esphome:device:b");
        espC = registerEsp("esphome:device:c");
    }

    private ESPHomeHandler registerEsp(String uid) {
        ThingUID thingUID = new ThingUID(uid);
        ESPHomeHandler handler = mock(ESPHomeHandler.class);
        Thing thing = mock(Thing.class);
        when(thing.getUID()).thenReturn(thingUID);
        when(thing.getHandler()).thenReturn(handler);
        when(handler.getThing()).thenReturn(thing);
        when(thingRegistry.get(thingUID)).thenReturn(thing);
        return handler;
    }

    private List<ESPHomeHandler> candidateHandlers() {
        return proxy.getConnectionCandidates(ADDRESS).stream().map(ConnectionCandidate::handler).toList();
    }

    private void freeSlots(ESPHomeHandler esp, int free) {
        proxy.handleBluetoothMessage(BluetoothConnectionsFreeResponse.newBuilder().setFree(free).setLimit(3).build(),
                esp);
    }

    @Test
    void candidatesAreStrongestFirstAndWithinRssiSpread() {
        proxy.updateDeviceLocation(ADDRESS, -58, espB);
        proxy.updateDeviceLocation(ADDRESS, -50, espA);
        proxy.updateDeviceLocation(ADDRESS, -65, espC);

        assertEquals(List.of(espA, espB), candidateHandlers());
    }

    @Test
    void espsHearingTheDeviceEquallyStrongAreBothCandidates() {
        proxy.updateDeviceLocation(ADDRESS, -60, espA);
        proxy.updateDeviceLocation(ADDRESS, -60, espB);

        assertEquals(2, candidateHandlers().size());
    }

    @Test
    void strongestEspIsACandidateEvenWithWeakSignal() {
        proxy.updateDeviceLocation(ADDRESS, -95, espA);
        proxy.updateDeviceLocation(ADDRESS, -97, espB);

        assertEquals(List.of(espA), candidateHandlers());
    }

    @Test
    void unknownDeviceHasNoCandidates() {
        assertTrue(proxy.getConnectionCandidates(ADDRESS).isEmpty());
    }

    @Test
    void freeSlotsAreUnknownUntilTheEspReportsThem() {
        proxy.updateDeviceLocation(ADDRESS, -50, espA);

        assertEquals(ConnectionCandidate.UNKNOWN_FREE_SLOTS, proxy.getConnectionCandidates(ADDRESS).get(0).freeSlots());

        freeSlots(espA, 0);
        assertFalse(proxy.getConnectionCandidates(ADDRESS).get(0).mayHaveFreeSlot());
    }

    @Test
    void reservingASlotLowersTheFreeCount() {
        proxy.updateDeviceLocation(ADDRESS, -50, espA);
        freeSlots(espA, 1);

        proxy.reserveConnectionSlot(espA);

        assertEquals(0, proxy.getConnectionCandidates(ADDRESS).get(0).freeSlots());
    }

    @Test
    void refusedConnectMarksEspFull() {
        proxy.updateDeviceLocation(ADDRESS, -50, espA);

        proxy.markNoFreeConnectionSlot(espA);

        assertEquals(0, proxy.getConnectionCandidates(ADDRESS).get(0).freeSlots());
        freeSlots(espA, 2);
        assertEquals(2, proxy.getConnectionCandidates(ADDRESS).get(0).freeSlots());
    }
}
