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
package no.seime.openhab.binding.esphome.internal.handler;

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.AbstractStorageBasedTypeProvider;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.thing.type.ChannelTypeProvider;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Channel Type Provider that does a callback the handler that initiated it.
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

        String thingUid = uid.getBindingId() + ":" + uid.getId() + "_";
        getChannelTypes(null).stream().map(ChannelType::getUID).filter(c -> c.getAsString().startsWith(thingUid))
                .forEach(this::removeChannelType);
    }

    /**
     * Remove dynamic channel types belonging to {@code thingUID} that are not present in {@code keep}. Used to clean up
     * types left over from a previous interrogation after the thing has been updated with the current channel set — so
     * no channel ever references a missing type (which would race with
     * {@code ThingManagerImpl.normalizeConfiguration}).
     */
    public void removeOrphanedChannelTypesForThing(ThingUID thingUID, Set<ChannelTypeUID> keep) {
        String prefix = thingUID.getBindingId() + ":" + thingUID.getId() + "_";
        getChannelTypes(null).stream().map(ChannelType::getUID).filter(c -> c.getAsString().startsWith(prefix))
                .filter(c -> !keep.contains(c)).forEach(this::removeChannelType);
    }
}
