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

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * This class represents an action request sent from an ESPHome device.
 *
 * @author Cody Cutrer - Initial contribution
 */
@NonNullByDefault
public class ActionEvent extends AbstractESPHomeEvent {
    public static final String TYPE = "esphome.ActionEvent";

    public ActionEvent(String topic, String payload, String deviceId, String action, Map<String, String> data,
            Map<String, String> data_template, Map<String, String> variables) {
        super(topic, payload, deviceId, action, data, data_template, variables);
    }

    @Override
    public String getType() {
        return TYPE;
    }

    public String getAction() {
        return action;
    }

    @Override
    public String toString() {
        return String.format("Device '%s' requested action '%s'", getSource(), action);
    }
}
