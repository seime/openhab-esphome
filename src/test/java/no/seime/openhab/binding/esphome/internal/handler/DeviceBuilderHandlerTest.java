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
package no.seime.openhab.binding.esphome.internal.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.internal.ThingImpl;

import com.sun.net.httpserver.HttpServer;

import no.seime.openhab.binding.esphome.internal.BindingConstants;

class DeviceBuilderHandlerTest {
    @Test
    void emptyScanIntervalDisablesBackgroundDiscoveryAndCanBeConfigured() {
        ThingImpl thing = new ThingImpl(BindingConstants.THING_TYPE_DEVICE_BUILDER, "builder");
        DeviceBuilderHandler handler = new DeviceBuilderHandler(thing);
        assertEquals(0, handler.getScanIntervalSeconds());
        thing.setConfiguration(new Configuration(Map.of("scanInterval", 60)));
        assertEquals(60, handler.getScanIntervalSeconds());
    }

    @Test
    void readsResolvedConfigWithPrefixCredentialsAndEncodedFilename() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/dashboard/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String response;
            if (exchange.getRequestURI().getPath().endsWith("/devices")) {
                response = "{\"configured\":[{\"configuration\":\"my device.yaml\",\"address\":\"192.0.2.1\"}]}";
            } else {
                query.set(exchange.getRequestURI().getRawQuery());
                response = "{\"esphome\":{\"name\":\"test\"},\"api\":{\"encryption\":{\"key\":\"resolved-key\"}}}";
            }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            ThingImpl thing = new ThingImpl(BindingConstants.THING_TYPE_DEVICE_BUILDER, "builder");
            thing.setConfiguration(
                    new Configuration(Map.of("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/dashboard",
                            "username", "user", "password", "pass")));
            DeviceBuilderHandler handler = new DeviceBuilderHandler(thing);
            ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
            handler.setCallback(callback);
            handler.initialize();
            List<DiscoveryResult> results = new ArrayList<>();
            handler.discover(results::add);
            assertEquals(1, results.size());
            assertEquals("resolved-key", results.get(0).getProperties().get("encryptionKey"));
            assertEquals("configuration=my+device.yaml", query.get());
            assertEquals("Basic dXNlcjpwYXNz", authorization.get());
            verify(callback).statusUpdated(eq(thing), argThat(status -> status.getStatus() == ThingStatus.ONLINE));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void comesOnlineBeforeReadingConfigsAndStaysOnlineForPartialFailure() throws Exception {
        checkConfigurationFailures(false, false);
    }

    @Test
    void goesOfflineWhenAllConfigurationsFail() throws Exception {
        checkConfigurationFailures(true, false);
    }

    @Test
    void staysOnlineForEmptyDeviceList() throws Exception {
        checkConfigurationFailures(false, true);
    }

    private void checkConfigurationFailures(boolean allFail, boolean empty) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ThingImpl thing = new ThingImpl(BindingConstants.THING_TYPE_DEVICE_BUILDER, "builder");
        ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
        List<ThingStatus> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            org.openhab.core.thing.ThingStatusInfo status = invocation.getArgument(1);
            statuses.add(status.getStatus());
            return null;
        }).when(callback).statusUpdated(eq(thing), any());
        java.util.concurrent.atomic.AtomicBoolean onlineAtFirstConfig = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/", exchange -> {
            String response;
            int status = 200;
            if (exchange.getRequestURI().getPath().equals("/devices")) {
                response = empty ? "{\"configured\":[]}" : """
                        {"configured":[{"configuration":"bad.yaml"},{"configuration":"good.yaml"}]}
                        """;
            } else {
                onlineAtFirstConfig.set(statuses.contains(ThingStatus.ONLINE));
                if (allFail || exchange.getRequestURI().getQuery().contains("bad.yaml")) {
                    status = 422;
                    response = "Invalid configuration";
                } else {
                    response = "{\"esphome\":{\"name\":\"test\"},\"api\":{}}";
                }
            }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            thing.setConfiguration(
                    new Configuration(Map.of("url", "http://127.0.0.1:" + server.getAddress().getPort())));
            DeviceBuilderHandler handler = new DeviceBuilderHandler(thing);
            handler.setCallback(callback);
            handler.initialize();
            List<DiscoveryResult> results = new ArrayList<>();
            handler.discover(results::add);
            if (!empty) {
                assertTrue(onlineAtFirstConfig.get(), "Must be online before requesting device configurations");
            }
            assertEquals(allFail ? ThingStatus.OFFLINE : ThingStatus.ONLINE, statuses.get(statuses.size() - 1));
            assertEquals(empty || allFail ? 0 : 1, results.size());
        } finally {
            server.stop(0);
        }
    }
}
