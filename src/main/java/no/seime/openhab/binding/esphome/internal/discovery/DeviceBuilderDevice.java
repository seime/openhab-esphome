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

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import no.seime.openhab.binding.esphome.internal.BindingConstants;

/** Maps resolved ESPHome configuration to the same identity used by mDNS. */
public final class DeviceBuilderDevice {
    private DeviceBuilderDevice() {
    }

    public static @Nullable DiscoveryResult createResult(JsonObject entry, JsonObject config) {
        if (!config.has("api") || !config.get("api").isJsonObject()) {
            return null;
        }
        JsonObject esphome = config.getAsJsonObject("esphome");
        JsonObject api = config.getAsJsonObject("api");
        if (esphome.has("name_add_mac_suffix") && esphome.get("name_add_mac_suffix").getAsBoolean()) {
            return null;
        }
        String name = esphome.get("name").getAsString();
        String address = text(entry, "address", name + ".local");
        Map<String, Object> properties = new HashMap<>();
        properties.put(ESPHomeDiscoveryParticipant.PROPERTY_DEVICEID, name);
        properties.put(ESPHomeDiscoveryParticipant.PROPERTY_HOSTNAME, address);
        properties.put(ESPHomeDiscoveryParticipant.PROPERTY_PORT, api.has("port") ? api.get("port").getAsInt() : 6053);
        if (!api.has("encryption") || !api.get("encryption").isJsonObject()) {
            return null;
        }
        String key = text(api.getAsJsonObject("encryption"), "key", "");
        if (!key.isBlank()) {
            properties.put("encryptionKey", key);
        }
        properties.put("allowActions", hasActions(config) || hasControlEntities(config));
        properties.put("enableBluetoothProxy", config.has("bluetooth_proxy"));
        return DiscoveryResultBuilder.create(new ThingUID(BindingConstants.THING_TYPE_DEVICE, name))
                .withProperties(properties).withLabel(text(esphome, "friendly_name", name))
                .withRepresentationProperty(ESPHomeDiscoveryParticipant.PROPERTY_DEVICEID).build();
    }

    private static String text(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() || value.getAsString().isBlank() ? fallback : value.getAsString();
    }

    private static boolean hasControlEntities(JsonObject config) {
        for (String domain : new String[] { "switch", "number", "button", "select", "text", "light", "cover", "fan",
                "climate" }) {
            JsonElement entities = config.get(domain);
            if (entities != null && entities.isJsonArray()) {
                for (JsonElement entity : entities.getAsJsonArray()) {
                    if (entity.isJsonObject()
                            && "homeassistant".equals(text(entity.getAsJsonObject(), "platform", ""))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static boolean hasActions(JsonElement value) {
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                if (entry.getKey().equals("homeassistant.service") || entry.getKey().equals("homeassistant.action")
                        || entry.getKey().equals("homeassistant.event")
                        || entry.getKey().equals("homeassistant.tag_scanned")) {
                    return true;
                }
                if (hasActions(entry.getValue())) {
                    return true;
                }
            }
        } else if (value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) {
                if (hasActions(child)) {
                    return true;
                }
            }
        }
        return false;
    }
}
