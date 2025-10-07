/**
 * Copyright (c) 2023 Contributors to the Seime Openhab Addons project
 * <p>
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 * <p>
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * <p>
 * SPDX-License-Identifier: EPL-2.0
 */
package no.seime.openhab.binding.esphome.events;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.events.AbstractEvent;

/**
 * This class represents a tag scan event sent from an ESPHome device.
 *
 * @author Cody Cutrer - Initial contribution
 */
@NonNullByDefault
public class TagScannedEvent extends AbstractEvent {
    public static final String TYPE = "esphome.TagScannedEvent";

    public TagScannedEvent(String topic, String tag_id, String source) {
        super(topic, tag_id, source);
    }

    @Override
    public String getType() {
        return TYPE;
    }

    public String getTagId() {
        return getPayload();
    }

    @Override
    public String toString() {
        return String.format("Device '%s' scanned tag '%s'", getSource(), getTagId());
    }
}
