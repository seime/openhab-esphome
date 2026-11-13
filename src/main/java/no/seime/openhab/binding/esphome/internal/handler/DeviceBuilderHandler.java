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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.inbox.Inbox;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.discovery.DeviceBuilderDevice;
import no.seime.openhab.binding.esphome.internal.discovery.DeviceBuilderDiscoveryService;
import no.seime.openhab.binding.esphome.internal.discovery.ESPHomeDiscoveryParticipant;

/** Contacts the Device Builder for resolved device configurations. */
@NonNullByDefault
public class DeviceBuilderHandler extends BaseThingHandler {
    private final Logger logger = LoggerFactory.getLogger(DeviceBuilderHandler.class);

    private final ThingRegistry thingRegistry;
    private final Inbox inbox;

    public DeviceBuilderHandler(Thing thing, ThingRegistry thingRegistry, Inbox inbox) {
        super(thing);
        this.thingRegistry = thingRegistry;
        this.inbox = inbox;
    }

    @Override
    public void initialize() {
        try {
            URI uri = URI.create(value("url"));
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            if (getScanIntervalSeconds() < 0) {
                throw new IllegalArgumentException();
            }
            updateStatus(ThingStatus.UNKNOWN);
        } catch (IllegalArgumentException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Configure a valid dashboard URL and a scan interval of 0 or greater");
        }
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Set.of(DeviceBuilderDiscoveryService.class);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
    }

    private String value(String key) {
        Object value = getThing().getConfiguration().get(key);
        return value == null ? "" : value.toString();
    }

    public int getScanIntervalSeconds() {
        String interval = value("scanInterval");
        return interval.isBlank() ? 0 : Integer.parseInt(interval);
    }

    public void discover(Consumer<DiscoveryResult> consumer) {
        discover(consumer, () -> false);
    }

    public void discover(Consumer<DiscoveryResult> consumer, BooleanSupplier cancelled) {
        if (getThing().getStatusInfo().getStatusDetail() == ThingStatusDetail.CONFIGURATION_ERROR) {
            return;
        }
        try {
            JsonObject devices = get("devices");
            updateStatus(ThingStatus.ONLINE);
            int attempted = 0;
            int succeeded = 0;
            for (var element : prioritizeDevices(devices.getAsJsonArray("configured"))) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    return;
                }
                attempted++;
                String filename = "<unknown configuration>";
                try {
                    JsonObject entry = element.getAsJsonObject();
                    filename = entry.get("configuration").getAsString();
                    JsonObject config = get(
                            "json-config?configuration=" + URLEncoder.encode(filename, StandardCharsets.UTF_8));
                    DiscoveryResult result = DeviceBuilderDevice.createResult(entry, config);
                    succeeded++;
                    if (result != null) {
                        consumer.accept(result);
                    }
                } catch (IOException | RuntimeException e) {
                    String reason;
                    if (e instanceof IOException) {
                        reason = e.getClass().getSimpleName() + ": " + e.getMessage();
                    } else if (e instanceof JsonParseException) {
                        reason = "Invalid JSON returned by the dashboard";
                    } else {
                        reason = "Invalid or unsupported device configuration (" + e.getClass().getSimpleName() + ")";
                    }
                    logger.warn("Could not read Device Builder configuration '{}': {}", filename, reason);
                }
            }
            if (attempted > 0 && succeeded == 0) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Could not read any device configurations; see log for details");
            }
        } catch (IOException | RuntimeException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Could not read Device Builder dashboard; check URL and credentials");
        }
    }

    private List<JsonElement> prioritizeDevices(JsonArray devices) {
        Set<String> configuredIds = new HashSet<>();
        for (Thing thing : thingRegistry.getAll()) {
            if (BindingConstants.THING_TYPE_DEVICE.equals(thing.getThingTypeUID())) {
                Object deviceId = thing.getConfiguration().get(ESPHomeDiscoveryParticipant.PROPERTY_DEVICEID);
                if (deviceId == null) {
                    deviceId = thing.getProperties().get(ESPHomeDiscoveryParticipant.PROPERTY_DEVICEID);
                }
                configuredIds.add(deviceId == null ? thing.getUID().getId() : deviceId.toString());
            }
        }
        Set<String> inboxIds = new HashSet<>();
        for (DiscoveryResult result : inbox.getAll()) {
            if (BindingConstants.THING_TYPE_DEVICE.equals(result.getThingTypeUID())) {
                Object deviceId = result.getProperties().get(ESPHomeDiscoveryParticipant.PROPERTY_DEVICEID);
                inboxIds.add(deviceId == null ? result.getThingUID().getId() : deviceId.toString());
            }
        }
        List<JsonElement> ordered = new ArrayList<>();
        devices.forEach(ordered::add);
        ordered.removeIf(element -> {
            JsonElement name = element.isJsonObject() ? element.getAsJsonObject().get("name") : null;
            return name != null && name.isJsonPrimitive() && configuredIds.contains(name.getAsString());
        });
        // Stable sorting preserves dashboard order within each priority group.
        ordered.sort(Comparator.comparingInt(element -> {
            JsonElement name = element.isJsonObject() ? element.getAsJsonObject().get("name") : null;
            if (name == null || !name.isJsonPrimitive()) {
                return 0;
            }
            String deviceId = name.getAsString();
            return inboxIds.contains(deviceId) ? 1 : 0;
        }));
        return ordered;
    }

    private JsonObject get(String path) throws IOException {
        String base = value("url");
        HttpURLConnection connection = (HttpURLConnection) URI.create(base + (base.endsWith("/") ? "" : "/") + path)
                .toURL().openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(false);
        if (!value("username").isEmpty() || !value("password").isEmpty()) {
            String credentials = value("username") + ":" + value("password");
            connection.setRequestProperty("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("Dashboard returned HTTP " + status);
            }
            try (var input = connection.getInputStream()) {
                return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
            }
        } finally {
            connection.disconnect();
        }
    }
}
