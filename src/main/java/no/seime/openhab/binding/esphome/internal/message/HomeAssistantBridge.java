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
package no.seime.openhab.binding.esphome.internal.message;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.events.AbstractEvent;
import org.openhab.core.events.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.HomeAssistantStateResponse;
import io.esphome.api.HomeassistantActionRequest;
import io.esphome.api.HomeassistantServiceMap;
import io.esphome.api.SubscribeHomeAssistantStateResponse;
import no.seime.openhab.binding.esphome.events.ESPHomeEventFactory;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.ESPHomeEventSubscriber;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.EventSubscription;

/**
 * Translates the Home Assistant parts of the ESPHome API to openHAB: actions and events sent by the device become
 * openHAB events, and openHAB item states the device subscribes to are sent back as Home Assistant states.
 *
 * @author Arne Seime - Initial contribution
 */
@NonNullByDefault
public class HomeAssistantBridge {

    private static final String ACTION_TAG_SCANNED = "esphome.tag_scanned";

    private final Logger logger = LoggerFactory.getLogger(HomeAssistantBridge.class);

    private final ESPHomeHandler handler;
    private final EventPublisher eventPublisher;
    private final ESPHomeEventSubscriber eventSubscriber;

    public HomeAssistantBridge(ESPHomeHandler handler, EventPublisher eventPublisher,
            ESPHomeEventSubscriber eventSubscriber) {
        this.handler = handler;
        this.eventPublisher = eventPublisher;
        this.eventSubscriber = eventSubscriber;
    }

    /**
     * Posts an action or event sent by the device on the openHAB event bus.
     */
    public void onActionRequest(HomeassistantActionRequest request, String deviceId) {
        Map<String, String> data = toMap(request.getDataList());
        Map<String, String> dataTemplate = toMap(request.getDataTemplateList());
        Map<String, String> variables = toMap(request.getVariablesList());
        AbstractEvent event;
        if (request.getIsEvent()) {
            String tagId;
            if (request.getService().equals(ACTION_TAG_SCANNED) && dataTemplate.isEmpty() && variables.isEmpty()
                    && data.size() == 1 && (tagId = data.get("tag_id")) != null) {
                event = ESPHomeEventFactory.createTagScannedEvent(deviceId, tagId);
            } else {
                event = ESPHomeEventFactory.createEventEvent(deviceId, request.getService(), data, dataTemplate,
                        variables);
            }
        } else {
            event = ESPHomeEventFactory.createActionEvent(deviceId, request.getService(), data, dataTemplate,
                    variables);
        }
        eventPublisher.post(event);
    }

    /**
     * Subscribes to the openHAB state the device asked for.
     *
     * @return the current state, to send to the device
     */
    public HomeAssistantStateResponse subscribe(SubscribeHomeAssistantStateResponse request) {
        String logPrefix = handler.getLogPrefix();
        logger.debug("[{}] Start subscribe to OH events entity: {}, attribute: {}", logPrefix, request.getEntityId(),
                request.getAttribute());

        EventSubscription subscription = eventSubscriber.createEventSubscription(request.getEntityId(),
                request.getAttribute(), handler);
        eventSubscriber.addEventSubscription(handler, subscription);

        String state = eventSubscriber.getInitialState(logPrefix, subscription);

        logger.debug("[{}] Sending initial state for subscription {} with state '{}'", logPrefix, subscription, state);
        return stateResponse(subscription, state);
    }

    public static HomeAssistantStateResponse stateResponse(EventSubscription subscription, String state) {
        return HomeAssistantStateResponse.newBuilder().setEntityId(subscription.getEntityId())
                .setAttribute(subscription.getAttribute()).setState(state).build();
    }

    private static Map<String, String> toMap(List<HomeassistantServiceMap> list) {
        Map<String, String> map = new HashMap<>();
        for (HomeassistantServiceMap kv : list) {
            map.put(kv.getKey(), kv.getValue());
        }
        return Collections.unmodifiableMap(map);
    }
}
