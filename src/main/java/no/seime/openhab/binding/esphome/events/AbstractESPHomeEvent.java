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
import org.openhab.core.events.AbstractEvent;

/**
 * Abstract base class for both action and event events.
 *
 * @author Cody Cutrer - Initial contribution
 */
@NonNullByDefault
public abstract class AbstractESPHomeEvent extends AbstractEvent {
    protected final String action;
    private final Map<String, String> data;
    private final Map<String, String> data_template;
    private final Map<String, String> variables;

    public AbstractESPHomeEvent(String topic, String payload, String deviceId, String action, Map<String, String> data,
            Map<String, String> data_template, Map<String, String> variables) {
        super(topic, payload, deviceId);
        this.action = action;
        this.data = data;
        this.data_template = data_template;
        this.variables = variables;
    }

    public Map<String, String> getData() {
        return data;
    }

    public Map<String, String> getDataTemplate() {
        return data_template;
    }

    public Map<String, String> getVariables() {
        return variables;
    }
}
