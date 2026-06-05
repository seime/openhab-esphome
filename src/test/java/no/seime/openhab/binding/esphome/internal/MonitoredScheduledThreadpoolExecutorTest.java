package no.seime.openhab.binding.esphome.internal;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

public class MonitoredScheduledThreadpoolExecutorTest {
    @Test
    public void test() throws InterruptedException {
        MonitoredCompositeExecutorService executor = new MonitoredCompositeExecutorService(
                Executors.newScheduledThreadPool(1), (ThreadPoolExecutor) Executors.newCachedThreadPool(), 1000);

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
