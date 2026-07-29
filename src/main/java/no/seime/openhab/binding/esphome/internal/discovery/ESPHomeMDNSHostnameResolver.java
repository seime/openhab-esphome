/**
 * Copyright (c) 2023 Contributors to the Seime Openhab Addons project
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

import java.net.Inet4Address;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.io.transport.mdns.MDNSClient;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a cache of ESPHome device hostname -> IP mappings from mDNS advertisements
 * received via the standard openHAB {@link MDNSClient}. Used to resolve hostnames when
 * OS-level DNS/mDNS is unreliable or slow to pick up address changes.
 */
@Component(immediate = true, service = ESPHomeMDNSHostnameResolver.class)
@NonNullByDefault
public class ESPHomeMDNSHostnameResolver implements ServiceListener {

    private static final Logger logger = LoggerFactory.getLogger(ESPHomeMDNSHostnameResolver.class);
    private static final String SERVICE_TYPE = "_esphomelib._tcp.local.";
    private static final String LOCAL_SUFFIX = ".local";

    private final Map<String, String> hostnameToIp = new ConcurrentHashMap<>();
    private final MDNSClient mdnsClient;

    @Activate
    public ESPHomeMDNSHostnameResolver(@Reference MDNSClient mdnsClient) {
        this.mdnsClient = mdnsClient;
        for (ServiceInfo info : mdnsClient.list(SERVICE_TYPE)) {
            update(info);
        }
        mdnsClient.addServiceListener(SERVICE_TYPE, this);
        logger.debug("Started mDNS hostname resolver, initial cache size: {}", hostnameToIp.size());
    }

    @Deactivate
    public void deactivate() {
        mdnsClient.removeServiceListener(SERVICE_TYPE, this);
        hostnameToIp.clear();
    }

    @Override
    public void serviceAdded(@Nullable ServiceEvent event) {
        // Address information not yet available; wait for serviceResolved.
    }

    @Override
    public void serviceRemoved(@Nullable ServiceEvent event) {
        if (event == null) {
            return;
        }
        ServiceInfo info = event.getInfo();
        if (info == null) {
            return;
        }
        String server = normalize(info.getServer());
        if (server != null) {
            String removed = hostnameToIp.remove(server);
            if (removed != null) {
                logger.debug("Removed mDNS cache entry {} -> {}", server, removed);
            }
        }
    }

    @Override
    public void serviceResolved(@Nullable ServiceEvent event) {
        if (event == null) {
            return;
        }
        update(event.getInfo());
    }

    private void update(@Nullable ServiceInfo info) {
        if (info == null) {
            return;
        }
        String server = normalize(info.getServer());
        if (server == null) {
            return;
        }
        Inet4Address[] ipv4 = info.getInet4Addresses();
        if (ipv4 == null || ipv4.length == 0) {
            return;
        }
        String ip = ipv4[0].getHostAddress();
        String prev = hostnameToIp.put(server, ip);
        if (!ip.equals(prev)) {
            logger.debug("Cached mDNS entry {} -> {} (was {})", server, ip, prev);
        }
    }

    /**
     * Resolve a hostname from the mDNS cache. The lookup is case-insensitive and tolerates
     * missing/present {@code .local} suffix.
     */
    public Optional<String> resolve(String hostname) {
        String normalized = normalize(hostname);
        if (normalized == null) {
            return Optional.empty();
        }
        String ip = hostnameToIp.get(normalized);
        if (ip == null) {
            if (normalized.endsWith(LOCAL_SUFFIX)) {
                ip = hostnameToIp.get(normalized.substring(0, normalized.length() - LOCAL_SUFFIX.length()));
            } else {
                ip = hostnameToIp.get(normalized + LOCAL_SUFFIX);
            }
        }
        return Optional.ofNullable(ip);
    }

    private static @Nullable String normalize(@Nullable String hostname) {
        if (hostname == null || hostname.isEmpty()) {
            return null;
        }
        String normalized = hostname.toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? null : normalized;
    }
}
