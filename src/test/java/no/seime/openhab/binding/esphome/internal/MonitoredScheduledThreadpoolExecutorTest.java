package no.seime.openhab.binding.esphome.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

public class MonitoredScheduledThreadpoolExecutorTest {

    @Test
    public void testBasicSubmit() throws Exception {
        MonitoredCompositeExecutorService executor = new MonitoredCompositeExecutorService(
                Executors.newScheduledThreadPool(1), (ThreadPoolExecutor) Executors.newCachedThreadPool(), 1000);

        Future<String> future = executor.submit(() -> "hello");
        assertEquals("hello", future.get(5, TimeUnit.SECONDS));

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    public void testScheduleRunnableAndCallable() throws Exception {
        MonitoredCompositeExecutorService executor = new MonitoredCompositeExecutorService(
                Executors.newScheduledThreadPool(1), (ThreadPoolExecutor) Executors.newCachedThreadPool(), 1000);

        AtomicBoolean ran = new AtomicBoolean(false);
        ScheduledFuture<?> rf = executor.schedule(() -> ran.set(true), 50, TimeUnit.MILLISECONDS);
        assertFalse(rf.isDone());
        assertNull(rf.get(2, TimeUnit.SECONDS));
        assertTrue(ran.get());
        assertTrue(rf.isDone());

        ScheduledFuture<Integer> cf = executor.schedule(() -> 42, 50, TimeUnit.MILLISECONDS);
        assertEquals(42, cf.get(2, TimeUnit.SECONDS));
        assertTrue(cf.isDone());

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    public void testScheduleZeroDelay() throws Exception {
        MonitoredCompositeExecutorService executor = new MonitoredCompositeExecutorService(
                Executors.newScheduledThreadPool(1), (ThreadPoolExecutor) Executors.newCachedThreadPool(), 1000);

        AtomicBoolean ran = new AtomicBoolean(false);
        ScheduledFuture<?> rf = executor.schedule(() -> ran.set(true), 0, TimeUnit.MILLISECONDS);
        rf.get(2, TimeUnit.SECONDS);
        assertTrue(ran.get());
        assertTrue(rf.isDone());

        ScheduledFuture<String> cf = executor.schedule(() -> "instant", -5, TimeUnit.MILLISECONDS);
        assertEquals("instant", cf.get(2, TimeUnit.SECONDS));
        assertTrue(cf.isDone());

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    public void testScheduleCancellation() throws Exception {
        MonitoredCompositeExecutorService executor = new MonitoredCompositeExecutorService(
                Executors.newScheduledThreadPool(1), (ThreadPoolExecutor) Executors.newCachedThreadPool(), 1000);

        AtomicBoolean ran = new AtomicBoolean(false);
        ScheduledFuture<?> future = executor.schedule(() -> ran.set(true), 500, TimeUnit.MILLISECONDS);
        assertTrue(future.cancel(false));
        assertTrue(future.isCancelled());
        assertTrue(future.isDone());
        assertThrows(CancellationException.class, future::get);

        Thread.sleep(600);
        assertFalse(ran.get());

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    public void testScheduleAtFixedRate() throws Exception {
        MonitoredCompositeExecutorService executor = new MonitoredCompositeExecutorService(
                Executors.newScheduledThreadPool(1), (ThreadPoolExecutor) Executors.newCachedThreadPool(), 1000);

        AtomicInteger count = new AtomicInteger(0);
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(count::incrementAndGet, 10, 50, TimeUnit.MILLISECONDS);

        Thread.sleep(200);
        assertTrue(count.get() >= 3);
        assertTrue(future.cancel(true));
        assertTrue(future.isCancelled());
        assertTrue(future.isDone());

        int countAfterCancel = count.get();
        Thread.sleep(150);
        assertEquals(countAfterCancel, count.get());

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    public void testScheduleWithFixedDelay() throws Exception {
        MonitoredCompositeExecutorService executor = new MonitoredCompositeExecutorService(
                Executors.newScheduledThreadPool(1), (ThreadPoolExecutor) Executors.newCachedThreadPool(), 1000);

        AtomicInteger count = new AtomicInteger(0);
        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
            }
            count.incrementAndGet();
        }, 10, 40, TimeUnit.MILLISECONDS);

        Thread.sleep(200);
        assertTrue(count.get() >= 2);
        assertTrue(future.cancel(true));
        assertTrue(future.isCancelled());

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPeriodicTaskSuppressedOnException() throws Exception {
        MonitoredCompositeExecutorService executor = new MonitoredCompositeExecutorService(
                Executors.newScheduledThreadPool(1), (ThreadPoolExecutor) Executors.newCachedThreadPool(), 1000);

        AtomicInteger count = new AtomicInteger(0);
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
            if (count.incrementAndGet() == 2) {
                throw new RuntimeException("Test exception");
            }
        }, 10, 30, TimeUnit.MILLISECONDS);

        assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());
        assertEquals(2, count.get());

        Thread.sleep(100);
        assertEquals(2, count.get());

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAwaitTerminationNonDestructive() throws Exception {
        ThreadPoolExecutor threadPool = (ThreadPoolExecutor) Executors.newCachedThreadPool();
        MonitoredCompositeExecutorService executor = new MonitoredCompositeExecutorService(
                Executors.newScheduledThreadPool(1), threadPool, 1000);

        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch finishTask = new CountDownLatch(1);

        executor.submit(() -> {
            taskStarted.countDown();
            try {
                finishTask.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });

        assertTrue(taskStarted.await(2, TimeUnit.SECONDS));
        // Await termination with very short timeout before shutdown
        assertFalse(executor.awaitTermination(50, TimeUnit.MILLISECONDS));
        // Executor should NOT have been shut down destructively
        assertFalse(executor.isShutdown());
        assertFalse(executor.isTerminated());

        // Now initiate shutdown
        executor.shutdown();
        assertTrue(executor.isShutdown());

        finishTask.countDown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(executor.isTerminated());
    }

    @Test
    public void testInvokeAllAndInvokeAny() throws Exception {
        MonitoredCompositeExecutorService executor = new MonitoredCompositeExecutorService(
                Executors.newScheduledThreadPool(1), (ThreadPoolExecutor) Executors.newCachedThreadPool(), 1000);

        List<Callable<String>> callables = List.of(() -> "a", () -> "b");
        List<Future<String>> results = executor.invokeAll(callables);
        assertEquals(2, results.size());
        assertEquals("a", results.get(0).get());
        assertEquals("b", results.get(1).get());

        String any = executor.invokeAny(callables);
        assertTrue("a".equals(any) || "b".equals(any));

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
}
