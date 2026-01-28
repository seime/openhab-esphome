package no.seime.openhab.binding.esphome.internal.handler;

import java.util.concurrent.ThreadLocalRandom;

public final class ExponentialBackoff {
    private final int initialDelay;
    private final int maxDelay;
    private static final int FACTOR = 2;

    private int nextDelay;

    public ExponentialBackoff(int initialDelay, int maxDelay) {
        if (initialDelay <= 0 || maxDelay <= 0) {
            throw new IllegalArgumentException("Delays must be positive");
        }
        if (initialDelay > maxDelay) {
            throw new IllegalArgumentException("Initial delay must not exceed max delay");
        }
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.nextDelay = initialDelay;
    }

    public synchronized int getNextDelay() {
        int delay = nextDelay;
        int randomJitter = ThreadLocalRandom.current().nextInt(0, delay / 2);
        nextDelay = Math.min(maxDelay, delay * FACTOR);
        return delay + randomJitter;
    }

    public synchronized void reset() {
        nextDelay = initialDelay;
    }

    @Override
    public String toString() {
        return "ExponentialBackoff [initialDelay=" + initialDelay + ", maxDelay=" + maxDelay + ", nextDelay="
                + nextDelay + "]";
    }
}
