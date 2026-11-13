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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import no.seime.openhab.binding.esphome.internal.handler.DeviceBuilderHandler;

class DeviceBuilderDiscoveryServiceTest {
    @Test
    void disposalCancelsManualScanAndPreventsFurtherScans() throws Exception {
        DeviceBuilderHandler handler = mock(DeviceBuilderHandler.class);
        DeviceBuilderDiscoveryService service = new DeviceBuilderDiscoveryService();
        service.setThingHandler(handler);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        doAnswer(invocation -> {
            BooleanSupplier cancellation = invocation.getArgument(1);
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                cancelled.set(cancellation.getAsBoolean());
            } finally {
                finished.countDown();
            }
            return null;
        }).when(handler).discover(any(), any());
        try {
            service.initialize();
            service.startScan();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            service.dispose();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertTrue(interrupted.get(), "Disposal must interrupt the retained manual task");
            assertTrue(cancelled.get(), "Disposal must propagate cancellation to the handler");
            service.startScan();
            verify(handler, times(1)).discover(any(), any());
        } finally {
            service.dispose();
        }
    }
}
