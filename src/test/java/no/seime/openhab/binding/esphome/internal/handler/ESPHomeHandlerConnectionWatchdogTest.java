package no.seime.openhab.binding.esphome.internal.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.esphome.api.DeviceInfoResponse;
import no.seime.openhab.binding.esphome.internal.MonitoredCompositeExecutorService;

class ESPHomeHandlerConnectionWatchdogTest extends AbstractESPHomeHandlerTest {

    @Test
    void expiredBootstrapWatchdogCannotDisconnectConfirmationPhase() throws Exception {
        MonitoredCompositeExecutorService monitoredExecutor = spy(executor);
        setField("executorService", monitoredExecutor);
        setConnected();
        setField("provisioningConnection", true);
        when(managedThingProvider.get(thing.getUID())).thenReturn(thing);
        Runnable bootstrapWatchdog = scheduleWatchdog(monitoredExecutor);

        handler.onPacket(DeviceInfoResponse.newBuilder().setApiEncryptionSupported(true)
                .setApiEncryptionProvisionable(true).build());
        Object confirmationTimeout = getField("connectionTimeoutFuture");
        clearInvocations(frameHelper, callback);

        // Model a cancelled task that had already started and was waiting for the lock.
        bootstrapWatchdog.run();

        verifyNoInteractions(frameHelper, callback);
        assertSame(confirmationTimeout, getField("connectionTimeoutFuture"));
        assertEquals("CONNECTED", getField("connectionState").toString());
    }

    @Test
    void expiredConfirmationWatchdogCannotDisconnectLaterAdoption() throws Exception {
        MonitoredCompositeExecutorService monitoredExecutor = spy(executor);
        setField("executorService", monitoredExecutor);
        setConnected();
        setField("provisioningConnection", true);
        when(managedThingProvider.get(thing.getUID())).thenReturn(thing);
        handler.onPacket(DeviceInfoResponse.newBuilder().setApiEncryptionSupported(true)
                .setApiEncryptionProvisionable(true).build());
        ArgumentCaptor<Runnable> watchdog = ArgumentCaptor.forClass(Runnable.class);
        verify(monitoredExecutor).schedule(watchdog.capture(), eq(60L), eq(TimeUnit.SECONDS),
                contains("Adoption watchdog"));
        // Keep the pending flag true, as it would be for a later adoption attempt.
        setField("connectionGeneration", (long) getField("connectionGeneration") + 1);
        clearInvocations(frameHelper, callback);

        watchdog.getValue().run();

        verifyNoInteractions(frameHelper, callback);
        assertEquals("CONNECTED", getField("connectionState").toString());
    }

    @Test
    void cancelledWatchdogCannotDisconnectSameConnection() throws Exception {
        MonitoredCompositeExecutorService monitoredExecutor = spy(executor);
        setField("executorService", monitoredExecutor);
        setConnected();
        Runnable watchdog = scheduleWatchdog(monitoredExecutor);
        invokeMethod("cancelConnectionTimeoutWatchdog", new Class<?>[0]);

        watchdog.run();

        verifyNoInteractions(frameHelper, callback);
    }

    @Test
    void currentWatchdogStillDisconnects() throws Exception {
        MonitoredCompositeExecutorService monitoredExecutor = spy(executor);
        setField("executorService", monitoredExecutor);
        setConnected();

        scheduleWatchdog(monitoredExecutor).run();

        verify(frameHelper).close();
        assertEquals("UNINITIALIZED", getField("connectionState").toString());
    }

    private Runnable scheduleWatchdog(MonitoredCompositeExecutorService monitoredExecutor) throws Exception {
        invokeMethod("scheduleConnectionTimeoutWatchdog", new Class<?>[] { String.class, String.class },
                "Connection watchdog", "Connection attempt timed out");
        ArgumentCaptor<Runnable> watchdog = ArgumentCaptor.forClass(Runnable.class);
        verify(monitoredExecutor).schedule(watchdog.capture(), eq(60L), eq(TimeUnit.SECONDS),
                contains("Connection watchdog"));
        return watchdog.getValue();
    }
}
