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
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.stream.Stream;

import javax.jmdns.ServiceInfo;

import org.junit.jupiter.api.Test;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.inbox.Inbox;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class DeviceBuilderDiscoveryTest {
    private JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private DiscoveryResult builder(String extra) {
        return DeviceBuilderDevice.createResult(json("{\"address\":\"192.0.2.1\"}"), json("""
                {"esphome":{"name":"test-device","friendly_name":"Test device"},
                 "api":{"port":6054,"encryption":{"key":"resolved-secret"}},
                 "script":[{"then":[%s]}]}
                """.formatted(extra)));
    }

    @Test
    void discoversResolvedKeyPortIdentityAndNestedActions() {
        DiscoveryResult result = builder("{\"homeassistant.action\":{\"action\":\"light.turn_on\"}}");
        assertEquals("esphome:device:test-device", result.getThingUID().toString());
        assertEquals("deviceId", result.getRepresentationProperty());
        assertEquals("resolved-secret", result.getProperties().get("encryptionKey"));
        assertEquals(6054, result.getProperties().get("port"));
        assertEquals(true, result.getProperties().get("allowActions"));
        assertEquals("Test device", result.getLabel());
    }

    @Test
    void actionsAreDisabledWithoutOutgoingActions() {
        assertEquals(false, builder("{\"delay\":1000}").getProperties().get("allowActions"));
    }

    @Test
    void detectsAllOutgoingActionTypes() {
        for (String action : new String[] { "homeassistant.service", "homeassistant.action", "homeassistant.event",
                "homeassistant.tag_scanned" }) {
            assertEquals(true, builder("{\"" + action + "\":{}}").getProperties().get("allowActions"), action);
        }
    }

    @Test
    void discoversUnencryptedDevicesAndHomeAssistantControlEntities() {
        DiscoveryResult result = DeviceBuilderDevice.createResult(json("{}"), json("""
                {"esphome":{"name":"test"}, "api":{},
                 "switch":[{"platform":"homeassistant","entity_id":"switch.test"}]}
                """));
        assertNotNull(result);
        assertFalse(result.getProperties().containsKey("encryptionKey"));
        assertEquals("test.local", result.getProperties().get("hostname"));
        assertEquals(6053, result.getProperties().get("port"));
        assertEquals(true, result.getProperties().get("allowActions"));
    }

    @Test
    void enablesBluetoothProxyWhenComponentIsPresent() {
        DiscoveryResult result = DeviceBuilderDevice.createResult(json("{}"), json("""
                {"esphome":{"name":"test"}, "api":{}, "bluetooth_proxy":{}}
                """));
        assertNotNull(result);
        assertEquals(true, result.getProperties().get("enableBluetoothProxy"));
        assertEquals(false, builder("{}").getProperties().get("enableBluetoothProxy"));

        Inbox inbox = mock(Inbox.class);
        when(inbox.stream()).thenAnswer(invocation -> Stream.of(result));
        ServiceInfo service = ServiceInfo.create("_esphomelib._tcp.local.", "test", 6053, 0, 0,
                Map.of("board", "esp32"));
        DiscoveryResult mdns = new ESPHomeDiscoveryParticipant(inbox).createResult(service);
        assertNotNull(mdns);
        assertEquals(true, mdns.getProperties().get("enableBluetoothProxy"));
    }

    @Test
    void skipsDevicesWithoutNativeApi() {
        assertNull(DeviceBuilderDevice.createResult(json("{}"), json("{\"esphome\":{\"name\":\"test\"}}")));
    }

    @Test
    void mdnsPreservesBuilderPropertiesAndUsesSameIdentity() {
        Inbox inbox = mock(Inbox.class);
        when(inbox.stream()).thenAnswer(invocation -> Stream.of(builder("{\"homeassistant.event\":{}}")));
        ESPHomeDiscoveryParticipant mdns = new ESPHomeDiscoveryParticipant(inbox);
        ServiceInfo service = ServiceInfo.create("_esphomelib._tcp.local.", "test-device", 6054, 0, 0,
                Map.of("board", "esp32", "friendly_name", "Test"));
        DiscoveryResult result = mdns.createResult(service);
        assertNotNull(result);
        assertEquals(builder("{}").getThingUID(), result.getThingUID());
        assertEquals("resolved-secret", result.getProperties().get("encryptionKey"));
        assertEquals(true, result.getProperties().get("allowActions"));
    }

    @Test
    void mdnsWorksBeforeBuilderDiscovery() {
        Inbox inbox = mock(Inbox.class);
        when(inbox.stream()).thenReturn(Stream.empty());
        ServiceInfo service = ServiceInfo.create("_esphomelib._tcp.local.", "test-device", 6053, 0, 0,
                Map.of("board", "esp32"));
        DiscoveryResult result = new ESPHomeDiscoveryParticipant(inbox).createResult(service);
        assertNotNull(result);
        assertEquals(builder("{}").getThingUID(), result.getThingUID());
        assertEquals("deviceId", result.getRepresentationProperty());
    }
}
