package no.seime.openhab.binding.esphome.internal;
/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Based on work done by @Nadahar
 * https://github.com/openhab/openhab-core/compare/main...Nadahar:openhab-core:composite-executor
 */
public class MonitoredCompositeExecutorService implements ScheduledExecutorService {

    @NonNull
    private final static Logger logger = LoggerFactory.getLogger(MonitoredCompositeExecutorService.class);
    private static final int MAX_WAIT_TIME_MS = 2000;

    @NonNull
    private final ThreadPoolExecutor executor;

    @NonNull
    private final ScheduledExecutorService scheduler;

    private final long defaultMaxExecutionTimeMs;

    public MonitoredCompositeExecutorService(@NonNull ScheduledExecutorService scheduler,
            @NonNull ThreadPoolExecutor executor, long defaultMaxExecutionTimeMs) {
        this.scheduler = scheduler;
        this.executor = executor;
        this.defaultMaxExecutionTimeMs = defaultMaxExecutionTimeMs;

        scheduler.scheduleAtFixedRate(() -> {
            logger.debug("Executor stats poolSize={}, activeCount={}, queueSize={}", executor.getPoolSize(),
                    executor.getActiveCount(), executor.getQueue().size());
        }, 2, 5, TimeUnit.SECONDS);
    }

    @Override
    public void shutdown() {
        scheduler.shutdown();
        executor.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        List<Runnable> result = scheduler.shutdownNow();
        result.addAll(executor.shutdownNow());
        return result;
    }

    @Override
    public boolean isShutdown() {
        return scheduler.isShutdown() && executor.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return scheduler.isTerminated() && executor.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, @Nullable TimeUnit unit) throws InterruptedException {
        TimeUnit timeUnit = unit == null ? TimeUnit.MILLISECONDS : unit;
        long starttime = System.nanoTime();

        boolean didTerminate = scheduler.awaitTermination(timeout, timeUnit);
        if (didTerminate) {
            long remaining = timeUnit.toNanos(timeout) - System.nanoTime() + starttime;
            if (remaining <= 0L) {
                return false;
            }
            didTerminate = executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } else {
            executor.shutdownNow();
            didTerminate = false;
        }

        return didTerminate;
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return executor.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return executor.submit(new TimedRunnable(task, getStackTraceElements(), defaultMaxExecutionTimeMs, null, false),
                result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return executor
                .submit(new TimedRunnable(task, getStackTraceElements(), defaultMaxExecutionTimeMs, null, false));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return executor.invokeAll(tasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return executor.invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return executor.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return executor.invokeAny(tasks, timeout, unit);
    }

    @Override
    public void execute(@NonNull Runnable command) {
        executor.execute(new TimedRunnable(command, getStackTraceElements(), defaultMaxExecutionTimeMs, null, false));
    }

    @Override
    public @NonNull ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        if (delay <= 0L) {
            return new FakeScheduledFuture<>(executor.submit(
                    new TimedRunnable(command, getStackTraceElements(), defaultMaxExecutionTimeMs, null, true)));
        }
        return new CompondScheduledFuture<>(scheduler.schedule(() -> submitOrLog(
                new TimedRunnable(command, getStackTraceElements(), defaultMaxExecutionTimeMs, null, true), null),
                delay, unit));
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        if (delay <= 0L) {
            return new FakeScheduledFuture<>(executor.submit(callable));
        }
        return new CompondScheduledFuture<>(scheduler.schedule(() -> submitOrLog(callable, null), delay, unit));
    }

    private <V> Future<V> submitOrLog(Callable<V> task, @Nullable String callerSignature) {
        try {
            return executor.submit(task);
        } catch (RejectedExecutionException e) {
            logger.warn("Task '{}' rejected by executor: {}", callerSignature != null ? callerSignature : "<unnamed>",
                    e.getMessage());
            throw e;
        }
    }

    private Future<?> submitOrLog(Runnable task, @Nullable String callerSignature) {
        try {
            return executor.submit(task);
        } catch (RejectedExecutionException e) {
            logger.warn("Task '{}' rejected by executor: {}", callerSignature != null ? callerSignature : "<unnamed>",
                    e.getMessage());
            throw e;
        }
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(unit);
        TaskLauncher launcher = new TaskLauncher(
                new TimedRunnable(command, getStackTraceElements(), defaultMaxExecutionTimeMs, null, true), executor);
        return new TaskLauncherScheduledFuture(scheduler.scheduleAtFixedRate(launcher, initialDelay, period, unit),
                launcher);
    }

    public @Nullable ScheduledFuture<?> scheduleAtFixedRate(Runnable runnable, long initialDelay, long period,
            TimeUnit timeUnit, String callerSignature) {
        Objects.requireNonNull(runnable);
        Objects.requireNonNull(timeUnit);
        Objects.requireNonNull(callerSignature);
        TaskLauncher launcher = new TaskLauncher(
                new TimedRunnable(runnable, getStackTraceElements(), defaultMaxExecutionTimeMs, callerSignature, true),
                executor);
        return new TaskLauncherScheduledFuture(scheduler.scheduleAtFixedRate(launcher, initialDelay, period, timeUnit),
                launcher);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return scheduleWithFixedDelay(command, initialDelay, delay, unit, null, defaultMaxExecutionTimeMs);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit,
            String callerSignature, long maxExecutionTimeMs) {

        Objects.requireNonNull(command);
        Objects.requireNonNull(unit);
        TaskLauncher launcher = new TaskLauncher(
                new TimedRunnable(command, getStackTraceElements(), maxExecutionTimeMs, callerSignature, true),
                executor);
        return new TaskLauncherScheduledFuture(scheduler.scheduleWithFixedDelay(launcher, initialDelay, delay, unit),
                launcher);
    }

    public @Nullable ScheduledFuture<?> schedule(Runnable command, int delay, TimeUnit timeUnit,
            String callerSignature) {
        return schedule(command, delay, timeUnit, callerSignature, defaultMaxExecutionTimeMs);
    }

    public @Nullable ScheduledFuture<?> schedule(Runnable command, int delay, TimeUnit timeUnit, String callerSignature,
            long maxExecutionTimeMs) {
        if (delay <= 0L) {
            return new FakeScheduledFuture<>(submitOrLog(
                    new TimedRunnable(command, getStackTraceElements(), maxExecutionTimeMs, callerSignature, false),
                    callerSignature));
        }
        return new CompondScheduledFuture<>(
                scheduler
                        .schedule(
                                () -> submitOrLog(new TimedRunnable(command, getStackTraceElements(),
                                        maxExecutionTimeMs, callerSignature, false), callerSignature),
                                delay, timeUnit));
    }

    private class FakeScheduledFuture<V> implements ScheduledFuture<V> {

        @NonNull
        private final Future<V> delegate;

        public FakeScheduledFuture(@NonNull Future<V> future) {
            delegate = future;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            if (other == this || other instanceof FakeScheduledFuture) {
                return 0;
            }
            long diff = getDelay(TimeUnit.NANOSECONDS) - other.getDelay(TimeUnit.NANOSECONDS);
            return (diff < 0) ? -1 : (diff > 0) ? 1 : 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return delegate.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        @Override
        public boolean isDone() {
            return delegate.isDone();
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            return delegate.get();
        }

        @Override
        public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return delegate.get(timeout, unit);
        }
    }

    private class CompondScheduledFuture<V> implements ScheduledFuture<V> {

        @NonNull
        private final ScheduledFuture<Future<V>> scheduledTask;

        public CompondScheduledFuture(@NonNull ScheduledFuture<Future<V>> scheduledFuture) {
            scheduledTask = scheduledFuture;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return scheduledTask.getDelay(unit);
        }

        @Override
        public int compareTo(@NonNull Delayed other) {
            if (other == this) {
                return 0;
            }
            if (other instanceof CompondScheduledFuture o) {
                return scheduledTask.compareTo(o.scheduledTask);
            }
            if (other instanceof TaskLauncherScheduledFuture o) {
                return scheduledTask.compareTo(o.scheduledTask);
            }
            long diff = getDelay(TimeUnit.NANOSECONDS) - other.getDelay(TimeUnit.NANOSECONDS);
            return (diff < 0) ? -1 : (diff > 0) ? 1 : 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean result = scheduledTask.cancel(false);
            if (scheduledTask.isCancelled()) {
                return result;
            }
            Future<V> task;
            try {
                task = scheduledTask.get();
            } catch (CancellationException e) {
                return result;
            } catch (InterruptedException e) {
                return false;
            } catch (ExecutionException e) {
                // Should be impossible
                logger.warn("Unexpected exception in CompondScheduledFuture.cancel(): {}", e.getCause());
                return false;
            }
            return task.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean isCancelled() {
            if (!scheduledTask.isCancelled()) {
                return false;
            }
            if (scheduledTask.isDone()) {
                try {
                    return scheduledTask.get().isCancelled();
                } catch (CancellationException e) {
                    return true;
                } catch (InterruptedException e) {
                    return false;
                } catch (ExecutionException e) {
                    // Should be impossible
                    logger.warn("Unexpected exception in CompondScheduledFuture.isCancelled(): {}", e.getCause());
                    return false;
                }
            }
            return false;
        }

        @Override
        public boolean isDone() {
            if (!scheduledTask.isDone()) {
                return false;
            }
            try {
                return scheduledTask.get().isDone();
            } catch (CancellationException e) {
                return true;
            } catch (InterruptedException e) {
                return false;
            } catch (ExecutionException e) {
                // Should be impossible
                logger.warn("Unexpected exception in CompondScheduledFuture.isDone(): {}", e.getCause());
                return false;
            }
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            return scheduledTask.get().get();
        }

        @Override
        public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            TimeUnit timeUnit = unit == null ? TimeUnit.MILLISECONDS : unit;
            long starttime = System.nanoTime();

            Future<V> task = scheduledTask.get(timeout, timeUnit);
            long remaining = timeUnit.toNanos(timeout) - System.nanoTime() + starttime;
            if (remaining <= 0L) {
                throw new TimeoutException();
            }
            return task.get(remaining, TimeUnit.NANOSECONDS);
        }
    }

    private class TaskLauncherScheduledFuture implements ScheduledFuture<Void> {

        @NonNull
        private final ScheduledFuture<?> scheduledTask;

        @NonNull
        private final TaskLauncher taskLauncher;

        public TaskLauncherScheduledFuture(@NonNull ScheduledFuture<?> scheduledTask,
                @NonNull TaskLauncher taskLauncher) {
            this.scheduledTask = scheduledTask;
            this.taskLauncher = taskLauncher;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return scheduledTask.getDelay(unit);
        }

        @Override
        public int compareTo(@NonNull Delayed other) {
            if (other == this) {
                return 0;
            }
            if (other instanceof CompondScheduledFuture o) {
                return scheduledTask.compareTo(o.scheduledTask);
            }
            if (other instanceof TaskLauncherScheduledFuture o) {
                return scheduledTask.compareTo(o.scheduledTask);
            }
            long diff = getDelay(TimeUnit.NANOSECONDS) - other.getDelay(TimeUnit.NANOSECONDS);
            return (diff < 0) ? -1 : (diff > 0) ? 1 : 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean result = scheduledTask.cancel(false);
            Future<?> task = taskLauncher.getTaskFuture();
            if (task != null) {
                result &= task.cancel(mayInterruptIfRunning);
            }
            return result;
        }

        @Override
        public boolean isCancelled() {
            return scheduledTask.isCancelled();
        }

        @Override
        public boolean isDone() {
            return scheduledTask.isDone();
        }

        @Override
        public Void get() throws InterruptedException, ExecutionException {
            return (Void) scheduledTask.get();
        }

        @Override
        public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return (Void) scheduledTask.get(timeout, unit);
        }
    }

    private static class TaskLauncher implements Runnable {

        @NonNull
        private final Runnable task;
        @NonNull
        private final ExecutorService executor;

        private volatile Future<?> taskFuture;

        public TaskLauncher(@NonNull Runnable task, @NonNull ExecutorService executor) {
            this.task = task;
            this.executor = executor;
        }

        @Override
        public void run() {
            taskFuture = executor.submit(task);
        }

        public Future<?> getTaskFuture() {
            return taskFuture;
        }
    }

    private static class TimedRunnable implements Runnable {
        private final Runnable delegate;
        private final StackTraceElement[] stackTrace;
        private long startTime;
        private final long submitTime;
        private final long maxExecutionTime;
        private final String taskDescription;
        private final boolean isScheduled;

        public TimedRunnable(Runnable delegate, StackTraceElement[] stackTrace, long maxExecutionTime,
                String taskDescription, boolean isScheduled) {
            this.delegate = delegate;
            this.stackTrace = stackTrace;
            this.maxExecutionTime = maxExecutionTime;
            this.taskDescription = taskDescription;
            this.isScheduled = isScheduled;
            submitTime = System.currentTimeMillis();
        }

        @Override
        public void run() {
            startTime = System.currentTimeMillis();
            delegate.run();
            long waitTime = System.currentTimeMillis() - submitTime;
            long duration = System.currentTimeMillis() - startTime;
            if (duration > maxExecutionTime) {
                logger.warn(
                        "Task '{}' took longer than expected to execute: {}ms, expected < {}ms. Task was submitted here: {}",
                        taskDescription != null ? taskDescription : "<unnamed>", duration, maxExecutionTime,
                        formatStacktrace(stackTrace));
            }

            if (!isScheduled && waitTime > MAX_WAIT_TIME_MS) {
                logger.warn(
                        "Task '{}' stayed longer than {}ms in queue before being processed: {}ms. This may indicate a too small threadpool or inadequate hardware for openHAB to run on. Task was submitted here: {}",
                        taskDescription != null ? taskDescription : "<unnamed>", MAX_WAIT_TIME_MS, waitTime,
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
