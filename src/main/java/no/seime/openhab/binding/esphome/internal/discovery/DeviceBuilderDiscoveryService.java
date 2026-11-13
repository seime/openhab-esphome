/**
 * Copyright (c) 2026 Contributors to the Seime Openhab Addons project
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
package no.seime.openhab.binding.esphome.internal.discovery;

import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.discovery.AbstractThingHandlerDiscoveryService;

import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.handler.DeviceBuilderHandler;

/** Discovery runs only while a Device Builder thing is configured. */
@NonNullByDefault
public class DeviceBuilderDiscoveryService extends AbstractThingHandlerDiscoveryService<DeviceBuilderHandler> {
    private @Nullable ScheduledFuture<?> polling;
    private @Nullable ScheduledFuture<?> manualScan;
    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile boolean disposed;

    @Override
    public synchronized void initialize() {
        disposed = false;
        super.initialize();
    }

    @Override
    public synchronized void dispose() {
        disposed = true;
        stopScan();
        super.dispose();
    }

    private void scan() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        try {
            synchronized (this) {
                if (disposed) {
                    return;
                }
            }
            thingHandler.discover(result -> {
                synchronized (this) {
                    if (!disposed) {
                        thingDiscovered(result);
                    }
                }
            }, () -> disposed);
        } finally {
            scanning.set(false);
        }
    }

    public DeviceBuilderDiscoveryService() {
        super(DeviceBuilderHandler.class, Set.of(BindingConstants.THING_TYPE_DEVICE), 300);
    }

    @Override
    protected synchronized void startScan() {
        if (!disposed && (manualScan == null || manualScan.isDone())) {
            manualScan = scheduler.schedule(this::scan, 0, TimeUnit.SECONDS);
        }
    }

    @Override
    protected synchronized void stopScan() {
        if (manualScan != null) {
            manualScan.cancel(true);
            manualScan = null;
        }
        super.stopScan();
    }

    @Override
    protected synchronized void startBackgroundDiscovery() {
        int interval = thingHandler.getScanIntervalSeconds();
        if (interval > 0) {
            polling = scheduler.scheduleWithFixedDelay(this::scan, 0, interval, TimeUnit.SECONDS);
        }
    }

    @Override
    protected synchronized void stopBackgroundDiscovery() {
        if (polling != null) {
            polling.cancel(true);
            polling = null;
        }
    }
}
