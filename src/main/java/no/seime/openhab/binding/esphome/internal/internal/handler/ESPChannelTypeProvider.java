/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
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
package no.seime.openhab.binding.esphome.internal.internal.handler;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.AbstractStorageBasedTypeProvider;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.thing.type.ChannelTypeProvider;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Channel Type Provider that does a callback the SensiboSkyHandler that initiated it.
 *
 * @author Arne Seime - Initial contribution
 */
@Component(service = { ESPChannelTypeProvider.class, ChannelTypeProvider.class })
@NonNullByDefault
public class ESPChannelTypeProvider extends AbstractStorageBasedTypeProvider {
    @Activate
    public ESPChannelTypeProvider(@Reference StorageService storageService) {
        super(storageService);
    }

    public void removeChannelTypesForThing(ThingUID uid) {

        String thingUid = uid.getId();
        getChannelTypes(null).stream().map(ChannelType::getUID).filter(c -> c.getAsString().endsWith(thingUid))
                .forEach(this::removeChannelType);
    }
}
