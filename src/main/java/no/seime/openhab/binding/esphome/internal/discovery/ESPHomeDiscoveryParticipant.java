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

import static no.seime.openhab.binding.esphome.internal.BindingConstants.THING_TYPE_DEVICE;

import java.util.Collections;
import java.util.Set;

import javax.jmdns.ServiceInfo;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.mdns.MDNSDiscoveryParticipant;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ESPHomeDiscoveryParticipant} is responsible for discovering
 * ESPHome devices
 *
 * @author Arne Seime - Initial contribution
 */
@Component(service = MDNSDiscoveryParticipant.class)
@NonNullByDefault
public class ESPHomeDiscoveryParticipant implements MDNSDiscoveryParticipant {

    public static final String PROPERTY_HOSTNAME = "hostname";

    private final Logger logger = LoggerFactory.getLogger(ESPHomeDiscoveryParticipant.class);

    @Override
    public Set<ThingTypeUID> getSupportedThingTypeUIDs() {
        return Collections.singleton(THING_TYPE_DEVICE);
    }

    @Override
    public String getServiceType() {
        return "_esphomelib._tcp.local.";
    }

    @Override
    public @Nullable DiscoveryResult createResult(ServiceInfo service) {
        String application = service.getApplication();

        if ("esphomelib".equals(application)) {
            final ThingUID deviceUID = getThingUID(service);
            if (deviceUID != null) {
                final DiscoveryResult discoveryResult = DiscoveryResultBuilder.create(deviceUID)
                        .withThingType(THING_TYPE_DEVICE).withProperty(PROPERTY_HOSTNAME, service.getServer())
                        .withLabel("ESPHome " + service.getName())

                        .withRepresentationProperty(PROPERTY_HOSTNAME).build();
                return discoveryResult;
            }
        }
        return null;
    }

    @Override
    public @Nullable ThingUID getThingUID(@Nullable ServiceInfo service) {
        if (service != null) {
            String serviceName = service.getName();
            logger.debug("Found ESPHome devices via mDNS:{} v4:{} v6:{}", serviceName, service.getInet4Addresses(),
                    service.getInet6Addresses());
            return new ThingUID(THING_TYPE_DEVICE, serviceName);
        }
        return null;
    }
}
