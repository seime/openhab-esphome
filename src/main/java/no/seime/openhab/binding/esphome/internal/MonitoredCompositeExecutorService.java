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

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final ScheduledFuture<?> statsFuture;

    public MonitoredCompositeExecutorService(@NonNull ScheduledExecutorService scheduler,
            @NonNull ThreadPoolExecutor executor, long defaultMaxExecutionTimeMs) {
        this.scheduler = scheduler;
        this.executor = executor;
        this.defaultMaxExecutionTimeMs = defaultMaxExecutionTimeMs;

        statsFuture = scheduler.scheduleAtFixedRate(() -> {
            logger.debug("Executor stats poolSize={}, activeCount={}, queueSize={}", executor.getPoolSize(),
                    executor.getActiveCount(), executor.getQueue().size());
        }, 2, 5, TimeUnit.SECONDS);
    }

    @Override
    public void shutdown() {
        statsFuture.cancel(false);
        scheduler.shutdown();
        executor.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        statsFuture.cancel(false);
        List<Runnable> result = new ArrayList<>(scheduler.shutdownNow());
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
        long timeoutNanos = timeUnit.toNanos(timeout);

        boolean didTerminate = scheduler.awaitTermination(timeoutNanos, TimeUnit.NANOSECONDS);
        if (didTerminate) {
            long remaining = timeoutNanos - (System.nanoTime() - starttime);
            if (remaining <= 0L) {
                return executor.isTerminated();
            }
            didTerminate = executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        }

        return didTerminate;
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return executor
                .submit(new TimedCallable<>(task, getStackTraceElements(), defaultMaxExecutionTimeMs, null, false));
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
        return executor.invokeAll(wrapCallables(tasks, null, defaultMaxExecutionTimeMs));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return executor.invokeAll(wrapCallables(tasks, null, defaultMaxExecutionTimeMs), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return executor.invokeAny(wrapCallables(tasks, null, defaultMaxExecutionTimeMs));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return executor.invokeAny(wrapCallables(tasks, null, defaultMaxExecutionTimeMs), timeout, unit);
    }

    private <T> Collection<Callable<T>> wrapCallables(Collection<? extends Callable<T>> tasks,
            @Nullable String callerSignature, long maxExecutionTimeMs) {
        StackTraceElement[] stackTrace = getStackTraceElements();
        return tasks.stream()
                .<Callable<T>> map(
                        task -> new TimedCallable<>(task, stackTrace, maxExecutionTimeMs, callerSignature, false))
                .toList();
    }

    @Override
    public void execute(@NonNull Runnable command) {
        executor.execute(new TimedRunnable(command, getStackTraceElements(), defaultMaxExecutionTimeMs, null, false));
    }

    @Override
    public @NonNull ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(unit);
        if (delay <= 0L) {
            return new ImmediateScheduledFuture<>(executor.submit(
                    new TimedRunnable(command, getStackTraceElements(), defaultMaxExecutionTimeMs, null, true)));
        }
        return new CompoundScheduledFuture<>(scheduler.schedule(() -> submitOrLog(
                new TimedRunnable(command, getStackTraceElements(), defaultMaxExecutionTimeMs, null, true), null),
                delay, unit));
    }

    @Override
    public <V> @NonNull ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        Objects.requireNonNull(callable);
        Objects.requireNonNull(unit);
        if (delay <= 0L) {
            return new ImmediateScheduledFuture<>(executor.submit(
                    new TimedCallable<>(callable, getStackTraceElements(), defaultMaxExecutionTimeMs, null, true)));
        }
        return new CompoundScheduledFuture<>(scheduler.schedule(() -> submitOrLog(
                new TimedCallable<>(callable, getStackTraceElements(), defaultMaxExecutionTimeMs, null, true), null),
                delay, unit));
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
    public @NonNull ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
            TimeUnit unit) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(unit);
        PeriodicScheduledFuture future = new PeriodicScheduledFuture(command, initialDelay, period, unit, true,
                getStackTraceElements(), defaultMaxExecutionTimeMs, null);
        future.start();
        return future;
    }

    public @NonNull ScheduledFuture<?> scheduleAtFixedRate(Runnable runnable, long initialDelay, long period,
            TimeUnit timeUnit, String callerSignature) {
        Objects.requireNonNull(runnable);
        Objects.requireNonNull(timeUnit);
        Objects.requireNonNull(callerSignature);
        PeriodicScheduledFuture future = new PeriodicScheduledFuture(runnable, initialDelay, period, timeUnit, true,
                getStackTraceElements(), defaultMaxExecutionTimeMs, callerSignature);
        future.start();
        return future;
    }

    @Override
    public @NonNull ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
            TimeUnit unit) {
        return scheduleWithFixedDelay(command, initialDelay, delay, unit, null, defaultMaxExecutionTimeMs);
    }

    public @NonNull ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
            TimeUnit unit, @Nullable String callerSignature, long maxExecutionTimeMs) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(unit);
        PeriodicScheduledFuture future = new PeriodicScheduledFuture(command, initialDelay, delay, unit, false,
                getStackTraceElements(), maxExecutionTimeMs, callerSignature);
        future.start();
        return future;
    }

    public @NonNull ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit timeUnit,
            String callerSignature) {
        return schedule(command, delay, timeUnit, callerSignature, defaultMaxExecutionTimeMs);
    }

    public @NonNull ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit timeUnit, String callerSignature,
            long maxExecutionTimeMs) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(timeUnit);
        if (delay <= 0L) {
            return new ImmediateScheduledFuture<>(submitOrLog(
                    new TimedRunnable(command, getStackTraceElements(), maxExecutionTimeMs, callerSignature, true),
                    callerSignature));
        }
        return new CompoundScheduledFuture<>(scheduler.schedule(() -> submitOrLog(
                new TimedRunnable(command, getStackTraceElements(), maxExecutionTimeMs, callerSignature, true),
                callerSignature), delay, timeUnit));
    }

    private record ImmediateScheduledFuture<V> (@NonNull Future<V> delegate) implements ScheduledFuture<V> {

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            if (other == this || other instanceof ImmediateScheduledFuture) {
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

    private class CompoundScheduledFuture<V> implements ScheduledFuture<V> {

        @NonNull
        private final ScheduledFuture<Future<V>> scheduledTask;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        public CompoundScheduledFuture(@NonNull ScheduledFuture<Future<V>> scheduledFuture) {
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
            if (other instanceof CompoundScheduledFuture o) {
                return scheduledTask.compareTo(o.scheduledTask);
            }
            if (other instanceof PeriodicScheduledFuture o) {
                return Long.compare(getDelay(TimeUnit.NANOSECONDS), o.getDelay(TimeUnit.NANOSECONDS));
            }
            long diff = getDelay(TimeUnit.NANOSECONDS) - other.getDelay(TimeUnit.NANOSECONDS);
            return (diff < 0) ? -1 : (diff > 0) ? 1 : 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled.set(true);
            boolean result = scheduledTask.cancel(mayInterruptIfRunning);
            if (scheduledTask.isDone() && !scheduledTask.isCancelled()) {
                try {
                    Future<V> task = scheduledTask.get();
                    if (task != null) {
                        result = task.cancel(mayInterruptIfRunning) || result;
                    }
                } catch (CancellationException ignored) {
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (ExecutionException ignored) {
                    return result;
                }
            }
            return result;
        }

        @Override
        public boolean isCancelled() {
            if (cancelled.get() || scheduledTask.isCancelled()) {
                return true;
            }
            if (scheduledTask.isDone()) {
                try {
                    Future<V> inner = scheduledTask.get();
                    return inner != null && inner.isCancelled();
                } catch (CancellationException e) {
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
            return false;
        }

        @Override
        public boolean isDone() {
            if (cancelled.get() || scheduledTask.isCancelled()) {
                return true;
            }
            if (!scheduledTask.isDone()) {
                return false;
            }
            try {
                Future<V> inner = scheduledTask.get();
                return inner != null && inner.isDone();
            } catch (CancellationException e) {
                return true;
            } catch (Exception e) {
                return true;
            }
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            if (cancelled.get()) {
                throw new CancellationException();
            }
            Future<V> inner = scheduledTask.get();
            return inner.get();
        }

        @Override
        public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            if (cancelled.get()) {
                throw new CancellationException();
            }
            TimeUnit timeUnit = unit == null ? TimeUnit.MILLISECONDS : unit;
            long startNanos = System.nanoTime();
            long timeoutNanos = timeUnit.toNanos(timeout);

            Future<V> task = scheduledTask.get(timeoutNanos, TimeUnit.NANOSECONDS);
            long elapsedNanos = System.nanoTime() - startNanos;
            long remainingNanos = timeoutNanos - elapsedNanos;
            if (remainingNanos <= 0L) {
                if (task.isDone()) {
                    return task.get();
                }
                throw new TimeoutException();
            }
            return task.get(remainingNanos, TimeUnit.NANOSECONDS);
        }
    }

    private class PeriodicScheduledFuture implements ScheduledFuture<Void>, Runnable {

        private final Runnable command;
        private final long initialDelay;
        private final long periodOrDelay;
        private final TimeUnit unit;
        private final boolean isFixedRate;
        private final StackTraceElement[] stackTrace;
        private final long maxExecutionTimeMs;
        private final @Nullable String callerSignature;

        private final Object lock = new Object();
        private ScheduledFuture<?> scheduledFuture;
        private Future<?> runningFuture;
        private boolean cancelled = false;
        private Throwable failureException = null;
        private long nextRunTimeNanos;

        public PeriodicScheduledFuture(Runnable command, long initialDelay, long periodOrDelay, TimeUnit unit,
                boolean isFixedRate, StackTraceElement[] stackTrace, long maxExecutionTimeMs,
                @Nullable String callerSignature) {
            this.command = command;
            this.initialDelay = initialDelay;
            this.periodOrDelay = periodOrDelay;
            this.unit = unit;
            this.isFixedRate = isFixedRate;
            this.stackTrace = stackTrace;
            this.maxExecutionTimeMs = maxExecutionTimeMs;
            this.callerSignature = callerSignature;
            nextRunTimeNanos = System.nanoTime() + unit.toNanos(initialDelay);
        }

        public void start() {
            synchronized (lock) {
                if (cancelled || isShutdown()) {
                    return;
                }
                scheduledFuture = scheduler.schedule(this, initialDelay, unit);
            }
        }

        @Override
        public void run() {
            synchronized (lock) {
                if (cancelled || failureException != null || executor.isShutdown()) {
                    return;
                }
                try {
                    runningFuture = executor.submit(new TimedRunnable(() -> {
                        try {
                            command.run();
                            onExecutionSuccess();
                        } catch (Throwable t) {
                            onExecutionFailure(t);
                            throw t;
                        }
                    }, stackTrace, maxExecutionTimeMs, callerSignature, true));
                } catch (RejectedExecutionException e) {
                    logger.warn("Periodic task '{}' rejected by executor: {}",
                            callerSignature != null ? callerSignature : "<unnamed>", e.getMessage());
                    onExecutionFailure(e);
                }
            }
        }

        private void onExecutionSuccess() {
            synchronized (lock) {
                if (cancelled || failureException != null || scheduler.isShutdown()) {
                    lock.notifyAll();
                    return;
                }
                long delayNanos;
                if (isFixedRate) {
                    nextRunTimeNanos += unit.toNanos(periodOrDelay);
                    delayNanos = nextRunTimeNanos - System.nanoTime();
                    if (delayNanos < 0) {
                        delayNanos = 0;
                    }
                } else {
                    delayNanos = unit.toNanos(periodOrDelay);
                    nextRunTimeNanos = System.nanoTime() + delayNanos;
                }
                try {
                    scheduledFuture = scheduler.schedule(this, delayNanos, TimeUnit.NANOSECONDS);
                } catch (RejectedExecutionException e) {
                    logger.warn("Periodic task '{}' reschedule rejected by scheduler: {}",
                            callerSignature != null ? callerSignature : "<unnamed>", e.getMessage());
                    onExecutionFailure(e);
                }
            }
        }

        private void onExecutionFailure(Throwable t) {
            synchronized (lock) {
                failureException = t;
                lock.notifyAll();
            }
        }

        @Override
        public long getDelay(TimeUnit targetUnit) {
            synchronized (lock) {
                long remainingNanos = nextRunTimeNanos - System.nanoTime();
                return targetUnit.convert(remainingNanos, TimeUnit.NANOSECONDS);
            }
        }

        @Override
        public int compareTo(@NonNull Delayed other) {
            if (other == this) {
                return 0;
            }
            if (other instanceof PeriodicScheduledFuture o) {
                return Long.compare(nextRunTimeNanos, o.nextRunTimeNanos);
            }
            long diff = getDelay(TimeUnit.NANOSECONDS) - other.getDelay(TimeUnit.NANOSECONDS);
            return (diff < 0) ? -1 : (diff > 0) ? 1 : 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            synchronized (lock) {
                if (cancelled) {
                    return false;
                }
                if (failureException != null) {
                    return false;
                }
                cancelled = true;
                if (scheduledFuture != null) {
                    scheduledFuture.cancel(mayInterruptIfRunning);
                }
                if (runningFuture != null) {
                    runningFuture.cancel(mayInterruptIfRunning);
                }
                lock.notifyAll();
                return true;
            }
        }

        @Override
        public boolean isCancelled() {
            synchronized (lock) {
                return cancelled;
            }
        }

        @Override
        public boolean isDone() {
            synchronized (lock) {
                return cancelled || failureException != null;
            }
        }

        @Override
        public Void get() throws InterruptedException, ExecutionException {
            synchronized (lock) {
                while (!isDone()) {
                    lock.wait();
                }
                if (cancelled) {
                    throw new CancellationException();
                }
                if (failureException != null) {
                    throw new ExecutionException(failureException);
                }
                return null;
            }
        }

        @Override
        public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            long remainingNanos = unit.toNanos(timeout);
            long deadline = System.nanoTime() + remainingNanos;
            synchronized (lock) {
                while (!isDone()) {
                    if (remainingNanos <= 0L) {
                        throw new TimeoutException();
                    }
                    TimeUnit.NANOSECONDS.timedWait(lock, remainingNanos);
                    remainingNanos = deadline - System.nanoTime();
                }
                if (cancelled) {
                    throw new CancellationException();
                }
                if (failureException != null) {
                    throw new ExecutionException(failureException);
                }
                return null;
            }
        }
    }

    private static class TimedRunnable implements Runnable {
        private final Runnable delegate;
        private final StackTraceElement[] stackTrace;
        private final long submitTime;
        private final long maxExecutionTime;
        private final @Nullable String taskDescription;
        private final boolean isScheduled;

        public TimedRunnable(Runnable delegate, StackTraceElement[] stackTrace, long maxExecutionTime,
                @Nullable String taskDescription, boolean isScheduled) {
            this.delegate = delegate;
            this.stackTrace = stackTrace;
            this.maxExecutionTime = maxExecutionTime;
            this.taskDescription = taskDescription;
            this.isScheduled = isScheduled;
            submitTime = System.currentTimeMillis();
        }

        @Override
        public void run() {
            long startTime = System.currentTimeMillis();
            try {
                delegate.run();
            } finally {
                long duration = System.currentTimeMillis() - startTime;
                if (duration > maxExecutionTime) {
                    logger.warn(
                            "Task '{}' took longer than expected to execute: {}ms, expected < {}ms. Task was submitted here: {}",
                            taskDescription != null ? taskDescription : "<unnamed>", duration, maxExecutionTime,
                            formatStacktrace(stackTrace));
                }

                long waitTime = startTime - submitTime;
                if (!isScheduled && waitTime > MAX_WAIT_TIME_MS) {
                    logger.warn(
                            "Task '{}' stayed longer than {}ms in queue before being processed: {}ms. This may indicate a too small threadpool or inadequate hardware for openHAB to run on. Task was submitted here: {}",
                            taskDescription != null ? taskDescription : "<unnamed>", MAX_WAIT_TIME_MS, waitTime,
                            formatStacktrace(stackTrace));
                }
            }
        }
    }

    private static class TimedCallable<V> implements Callable<V> {
        private final Callable<V> delegate;
        private final StackTraceElement[] stackTrace;
        private final long submitTime;
        private final long maxExecutionTime;
        private final @Nullable String taskDescription;
        private final boolean isScheduled;

        public TimedCallable(Callable<V> delegate, StackTraceElement[] stackTrace, long maxExecutionTime,
                @Nullable String taskDescription, boolean isScheduled) {
            this.delegate = delegate;
            this.stackTrace = stackTrace;
            this.maxExecutionTime = maxExecutionTime;
            this.taskDescription = taskDescription;
            this.isScheduled = isScheduled;
            submitTime = System.currentTimeMillis();
        }

        @Override
        public V call() throws Exception {
            long startTime = System.currentTimeMillis();
            try {
                return delegate.call();
            } finally {
                long duration = System.currentTimeMillis() - startTime;
                if (duration > maxExecutionTime) {
                    logger.warn(
                            "Task '{}' took longer than expected to execute: {}ms, expected < {}ms. Task was submitted here: {}",
                            taskDescription != null ? taskDescription : "<unnamed>", duration, maxExecutionTime,
                            formatStacktrace(stackTrace));
                }

                long waitTime = startTime - submitTime;
                if (!isScheduled && waitTime > MAX_WAIT_TIME_MS) {
                    logger.warn(
                            "Task '{}' stayed longer than {}ms in queue before being processed: {}ms. This may indicate a too small threadpool or inadequate hardware for openHAB to run on. Task was submitted here: {}",
                            taskDescription != null ? taskDescription : "<unnamed>", MAX_WAIT_TIME_MS, waitTime,
                            formatStacktrace(stackTrace));
                }
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
        if (callerStacktrace.length <= 3) {
            return new StackTraceElement[0];
        }
        return Arrays.copyOfRange(callerStacktrace, 3, callerStacktrace.length);
    }
}
