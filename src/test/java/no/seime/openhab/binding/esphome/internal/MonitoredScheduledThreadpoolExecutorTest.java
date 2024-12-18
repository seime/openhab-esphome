package no.seime.openhab.binding.esphome.internal;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import no.seime.openhab.binding.esphome.internal.handler.MonitoredScheduledThreadPoolExecutor;

public class MonitoredScheduledThreadpoolExecutorTest {
    @Test
    public void test() throws InterruptedException {

        MonitoredScheduledThreadPoolExecutor executor = new MonitoredScheduledThreadPoolExecutor(1, r -> new Thread(r),
                1000);

        executor.submit(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }, "Delayed task");

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
