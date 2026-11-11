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
import java.util.Base64;
import java.util.Collection;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import no.seime.openhab.binding.esphome.internal.discovery.DeviceBuilderDevice;
import no.seime.openhab.binding.esphome.internal.discovery.DeviceBuilderDiscoveryService;

/** Contacts the Device Builder for resolved device configurations. */
@NonNullByDefault
public class DeviceBuilderHandler extends BaseThingHandler {
    private final Logger logger = LoggerFactory.getLogger(DeviceBuilderHandler.class);

    public DeviceBuilderHandler(Thing thing) {
        super(thing);
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
                    "Configure a valid dashboard URL and a positive scan interval, or leave the interval empty");
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
        if (getThing().getStatusInfo().getStatusDetail() == ThingStatusDetail.CONFIGURATION_ERROR) {
            return;
        }
        try {
            JsonObject devices = get("devices");
            updateStatus(ThingStatus.ONLINE);
            int attempted = 0;
            int succeeded = 0;
            for (var element : devices.getAsJsonArray("configured")) {
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
