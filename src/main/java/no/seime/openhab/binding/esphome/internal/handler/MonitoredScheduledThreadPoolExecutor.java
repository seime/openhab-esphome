package no.seime.openhab.binding.esphome.internal.handler;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MonitoredScheduledThreadPoolExecutor extends ScheduledThreadPoolExecutor {
    private static final Logger logger = LoggerFactory.getLogger(MonitoredScheduledThreadPoolExecutor.class);

    public MonitoredScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory) {
        super(corePoolSize, threadFactory);
    }

    ThreadLocal<Long> taskStartMillis = new ThreadLocal<>();

    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        taskStartMillis.set(System.currentTimeMillis());
        super.beforeExecute(t, r);
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        Long taskEndMillis = taskStartMillis.get();
        taskStartMillis.remove();

        if (taskEndMillis - taskStartMillis.get() > 3000) {
            logger.warn("Task took too long to execute: " + (taskEndMillis - taskStartMillis.get())
                    + "ms. Check the ESP network connectivity or the ESPHome device logs");
        }

        super.afterExecute(r, t);
    }
}
