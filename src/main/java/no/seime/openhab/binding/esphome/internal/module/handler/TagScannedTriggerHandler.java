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
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.automation.ModuleHandlerCallback;
import org.openhab.core.automation.Trigger;
import org.openhab.core.automation.handler.BaseTriggerModuleHandler;
import org.openhab.core.automation.handler.TriggerHandlerCallback;
import org.openhab.core.config.core.ConfigParser;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventFilter;
import org.openhab.core.events.EventSubscriber;
import org.openhab.core.events.TopicPrefixEventFilter;
import org.openhab.core.scheduler.ScheduledCompletableFuture;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import no.seime.openhab.binding.esphome.events.TagScannedEvent;

/**
 * This is a ModuleHandler implementation for Triggers which triggers rules
 * based on events sent from ESPHome devices
 *
 * @author Cody Cutrer - Initial contribution
 */
@NonNullByDefault
public class TagScannedTriggerHandler extends BaseTriggerModuleHandler implements EventSubscriber {
    public static final String CONFIG_DEVICE_ID = "deviceId";

    public static final String MODULE_TYPE_ID = "esphome.TagScannedTrigger";

    private final Logger logger = LoggerFactory.getLogger(TagScannedTriggerHandler.class);

    private final @Nullable EventFilter eventFilter;
    private @Nullable String deviceId = null;

    private @Nullable ScheduledCompletableFuture<?> schedule;
    private @Nullable ServiceRegistration<?> eventSubscriberRegistration;

    public TagScannedTriggerHandler(Trigger module, BundleContext bundleContext) {
        super(module);
        this.eventFilter = new TopicPrefixEventFilter("openhab/esphome/tag_scanned");
        this.deviceId = ConfigParser.valueAs(module.getConfiguration().get(CONFIG_DEVICE_ID), String.class);
        eventSubscriberRegistration = bundleContext.registerService(EventSubscriber.class.getName(), this, null);
    }

    @Override
    public void dispose() {
        ServiceRegistration<?> eventSubscriberRegistration = this.eventSubscriberRegistration;
        if (eventSubscriberRegistration != null) {
            eventSubscriberRegistration.unregister();
            this.eventSubscriberRegistration = null;
        }
        super.dispose();
    }

    @Override
    public synchronized void setCallback(ModuleHandlerCallback callback) {
        super.setCallback(callback);
    }

    @Override
    public Set<String> getSubscribedEventTypes() {
        return Set.of(TagScannedEvent.TYPE);
    }

    @Override
    public @Nullable EventFilter getEventFilter() {
        return eventFilter;
    }

    @Override
    public void receive(Event event) {
        if (event instanceof TagScannedEvent tagScannedEvent) {
            if (deviceId != null && !tagScannedEvent.getSource().equals(deviceId)) {
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
