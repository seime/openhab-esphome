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
package no.seime.openhab.binding.esphome.internal.module.handler;

import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.automation.ModuleHandlerCallback;
import org.openhab.core.automation.Trigger;
import org.openhab.core.automation.handler.TriggerHandlerCallback;
import org.openhab.core.events.Event;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import no.seime.openhab.binding.esphome.events.EventEvent;

/**
 * This is a ModuleHandler implementation for Triggers which triggers rules
 * based on events sent from ESPHome devices
 *
 * @author Cody Cutrer - Initial contribution
 */
@NonNullByDefault
public class EventTriggerHandler extends AbstractESPHomeTriggerHandler {
    public static final String MODULE_TYPE_ID = "esphome.EventTrigger";
    public static final String CONFIG_EVENT = "event";

    private final Logger logger = LoggerFactory.getLogger(EventTriggerHandler.class);

    public EventTriggerHandler(Trigger module, BundleContext bundleContext) {
        super(module, "openhab/esphome/event/", CONFIG_EVENT, bundleContext);
    }

    @Override
    public Set<String> getSubscribedEventTypes() {
        return Set.of(EventEvent.TYPE);
    }

    @Override
    public void receive(Event event) {
        if (event instanceof EventEvent eventEvent && (eventEvent.getEvent().equals(action))) {
            if (deviceId != null && !eventEvent.getSource().equals(deviceId)) {
                // device is configured on the trigger, but doesn't match the event, so skip it
                return;
            }
            ModuleHandlerCallback callback = this.callback;
            if (callback instanceof TriggerHandlerCallback triggerHandlerCallback) {
                triggerHandlerCallback.triggered(module, Map.of("event", event));
            } else {
                logger.debug("Tried to trigger, but callback isn't available!");
            }
        }
    }
}
