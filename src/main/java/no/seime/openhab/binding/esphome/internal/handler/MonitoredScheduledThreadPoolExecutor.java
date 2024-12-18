package no.seime.openhab.binding.esphome.internal.handler;

import java.util.Arrays;
import java.util.concurrent.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MonitoredScheduledThreadPoolExecutor extends ScheduledThreadPoolExecutor {
    private static final Logger logger = LoggerFactory.getLogger(MonitoredScheduledThreadPoolExecutor.class);

    final long defaultMaxExecutionTimeMs;

    public MonitoredScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory,
            long defaultMaxExecutionTimeMs) {
        super(corePoolSize, threadFactory);
        this.defaultMaxExecutionTimeMs = defaultMaxExecutionTimeMs;
    }

    @Override
    public void execute(Runnable command) {
        super.execute(new TimedRunnable(command, getStackTraceElements(), defaultMaxExecutionTimeMs, null));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return super.submit(new TimedRunnable(task, getStackTraceElements(), defaultMaxExecutionTimeMs, null), result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return super.submit(new TimedRunnable(task, getStackTraceElements(), defaultMaxExecutionTimeMs, null));
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return super.schedule(new TimedRunnable(command, getStackTraceElements(), defaultMaxExecutionTimeMs, null),
                delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long initialDelay, long delay, TimeUnit unit) {
        return super.scheduleWithFixedDelay(
                new TimedRunnable(task, getStackTraceElements(), defaultMaxExecutionTimeMs, null), initialDelay, delay,
                unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return super.scheduleAtFixedRate(
                new TimedRunnable(command, getStackTraceElements(), defaultMaxExecutionTimeMs, null), initialDelay,
                period, unit);
    }

    public void execute(Runnable command, String taskDescription) {
        super.execute(new TimedRunnable(command, getStackTraceElements(), defaultMaxExecutionTimeMs, taskDescription));
    }

    public <T> Future<T> submit(Runnable task, T result, String taskDescription) {
        return super.submit(
                new TimedRunnable(task, getStackTraceElements(), defaultMaxExecutionTimeMs, taskDescription), result);
    }

    public Future<?> submit(Runnable task, String taskDescription) {
        return super.submit(
                new TimedRunnable(task, getStackTraceElements(), defaultMaxExecutionTimeMs, taskDescription));
    }

    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit, String taskDescription) {
        return super.schedule(
                new TimedRunnable(command, getStackTraceElements(), defaultMaxExecutionTimeMs, taskDescription), delay,
                unit);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long initialDelay, long delay, TimeUnit unit,
            String taskDescription) {
        return super.scheduleWithFixedDelay(
                new TimedRunnable(task, getStackTraceElements(), defaultMaxExecutionTimeMs, taskDescription),
                initialDelay, delay, unit);
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit,
            String taskDescription) {
        return super.scheduleAtFixedRate(
                new TimedRunnable(command, getStackTraceElements(), defaultMaxExecutionTimeMs, taskDescription),
                initialDelay, period, unit);
    }

    public void execute(Runnable command, String taskDescription, long maxExecutionTimeMs) {
        super.execute(new TimedRunnable(command, getStackTraceElements(), maxExecutionTimeMs, taskDescription));
    }

    public <T> Future<T> submit(Runnable task, T result, String taskDescription, long maxExecutionTimeMs) {
        return super.submit(new TimedRunnable(task, getStackTraceElements(), maxExecutionTimeMs, taskDescription),
                result);
    }

    public Future<?> submit(Runnable task, String taskDescription, long maxExecutionTimeMs) {
        return super.submit(new TimedRunnable(task, getStackTraceElements(), maxExecutionTimeMs, taskDescription));
    }

    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit, String taskDescription,
            long maxExecutionTimeMs) {
        return super.schedule(new TimedRunnable(command, getStackTraceElements(), maxExecutionTimeMs, taskDescription),
                delay, unit);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long initialDelay, long delay, TimeUnit unit,
            String taskDescription, long maxExecutionTimeMs) {
        return super.scheduleWithFixedDelay(
                new TimedRunnable(task, getStackTraceElements(), maxExecutionTimeMs, taskDescription), initialDelay,
                delay, unit);
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit,
            String taskDescription, long maxExecutionTimeMs) {
        return super.scheduleAtFixedRate(
                new TimedRunnable(command, getStackTraceElements(), maxExecutionTimeMs, taskDescription), initialDelay,
                period, unit);
    }

    private static class TimedRunnable implements Runnable {
        private final Runnable delegate;
        private final StackTraceElement[] stackTrace;
        private long startTime;
        private final long maxExecutionTime;
        private final String taskDescription;

        public TimedRunnable(Runnable delegate, StackTraceElement[] stackTrace, long maxExecutionTime,
                String taskDescription) {
            this.delegate = delegate;
            this.stackTrace = stackTrace;
            this.maxExecutionTime = maxExecutionTime;
            this.taskDescription = taskDescription;
        }

        @Override
        public void run() {
            startTime = System.currentTimeMillis();
            delegate.run();
            long duration = System.currentTimeMillis() - startTime;
            if (duration > maxExecutionTime) {
                logger.warn(
                        "Task '{}' took too long to execute: {}ms, expected < {}ms. Check the ESP network connectivity or the ESPHome device logs. Task was submitted here: {}",
                        taskDescription != null ? taskDescription : "<unnamed>", duration, maxExecutionTime,
                        formatStacktrace(stackTrace));
            }
        }
    }

    private static String formatStacktrace(StackTraceElement[] stackTrace) {
        StringBuilder sb = new StringBuilder("\n");
        for (StackTraceElement element : stackTrace) {
            sb.append("\t");
            sb.append(element.toString());
            sb.append("\n");
        }
        return sb.toString();
    }

    private static StackTraceElement[] getStackTraceElements() {
        StackTraceElement[] callerStacktrace = Thread.currentThread().getStackTrace();
        // Trim 3 first entries
        callerStacktrace = Arrays.copyOfRange(callerStacktrace, 3, callerStacktrace.length);
        return callerStacktrace;
    }
}
