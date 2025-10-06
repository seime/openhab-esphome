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
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.events.AbstractEventFactory;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventFactory;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class represents an action request sent from an ESPHome device.
 *
 * @author Cody Cutrer - Initial contribution
 */
@NonNullByDefault
@Component(service = EventFactory.class, immediate = true)
public class ESPHomeEventFactory extends AbstractEventFactory {
    private static final String ACTION_IDENTIFIER = "{action}";
    private static final String ACTION_EVENT_TOPIC = "openhab/esphome/action/" + ACTION_IDENTIFIER;
    private static final String EVENT_EVENT_TOPIC = "openhab/esphome/event/" + ACTION_IDENTIFIER;
    private static final String TAG_SCANNED_EVENT_TOPIC = "openhab/esphome/tag_scanned";
    private static final String SOURCE_PREFIX = "no.seime.openhab.binding.esphome$";

    private final Logger logger = LoggerFactory.getLogger(ESPHomeEventFactory.class);

    private static final Set<String> SUPPORTED_TYPES = Set.of(ActionEvent.TYPE, EventEvent.TYPE, TagScannedEvent.TYPE);

    public ESPHomeEventFactory() {
        super(SUPPORTED_TYPES);
    }

    @Override
    protected Event createEventByType(String eventType, String topic, String payload, @Nullable String source)
            throws Exception {
        logger.trace("creating ruleEvent of type: {}", eventType);
        if (source == null) {
            throw new IllegalArgumentException("'source' must not be null for ESPHome events");
        }
        if (ActionEvent.TYPE.equals(eventType)) {
            return createActionEvent(topic, payload, source);
        } else if (EventEvent.TYPE.equals(eventType)) {
            return createEventEvent(topic, payload, source);
        } else if (TagScannedEvent.TYPE.equals(eventType)) {
            return new TagScannedEvent(topic, payload, source);
        }
        throw new IllegalArgumentException("The event type '" + eventType + "' is not supported by this factory.");
    }

    private Event createActionEvent(String topic, String payload, String source) {
        String action = getAction(topic);
        ActionEventPayloadBean bean = deserializePayload(payload, ActionEventPayloadBean.class);

        return new ActionEvent(topic, payload, source, action, bean.getData(), bean.getDataTemplate(),
                bean.getVariables());
    }

    private Event createEventEvent(String topic, String payload, String source) {
        String action = getAction(topic);
        ActionEventPayloadBean bean = deserializePayload(payload, ActionEventPayloadBean.class);

        return new EventEvent(topic, payload, source, action, bean.getData(), bean.getDataTemplate(),
                bean.getVariables());
    }

    private String getAction(String topic) {
        String[] topicElements = getTopicElements(topic);
        if (topicElements.length < 4) {
            throw new IllegalArgumentException("Event creation failed, invalid topic: " + topic);
        }

        return topicElements[3];
    }

    /**
     * Creates an {@link ActionEvent}
     *
     * @param deviceId the device requesting the action
     * @param action the action to perform
     * @param data the data for the action
     * @param data_template the data template for the action
     * @param variables variables for the use in the templates
     * @return the created event
     */
    public static ActionEvent createActionEvent(String deviceId, String action, Map<String, String> data,
            Map<String, String> data_template, Map<String, String> variables) {
        String topic = ACTION_EVENT_TOPIC.replace(ACTION_IDENTIFIER, action);
        ActionEventPayloadBean bean = new ActionEventPayloadBean(data, data_template, variables);
        String payload = serializePayload(bean);
        return new ActionEvent(topic, payload, SOURCE_PREFIX + deviceId, action, data, data_template, variables);
    }

    /**
     * Creates an {@link EventEvent}
     *
     * @param deviceId the device emitting the event
     * @param event the event identifier
     * @param data the data for the action
     * @param data_template the data template for the action
     * @param variables variables for the use in the templates
     * @return the created event
     */
    public static EventEvent createEventEvent(String deviceId, String event, Map<String, String> data,
            Map<String, String> data_template, Map<String, String> variables) {
        String topic = EVENT_EVENT_TOPIC.replace(ACTION_IDENTIFIER, event);
        ActionEventPayloadBean bean = new ActionEventPayloadBean(data, data_template, variables);
        String payload = serializePayload(bean);
        return new EventEvent(topic, payload, SOURCE_PREFIX + deviceId, event, data, data_template, variables);
    }

    /**
     * Creates a {@link TagScannedEvent}
     *
     * @param deviceId the device emitting the event
     * @param tagId the tag identifier
     * @return the created event
     */
    public static TagScannedEvent createTagScannedEvent(String deviceId, String tagId) {
        return new TagScannedEvent(TAG_SCANNED_EVENT_TOPIC, tagId, SOURCE_PREFIX + deviceId);
    }

    /**
     * This is a java bean that is used to serialize/deserialize action event payload.
     */
    private static class ActionEventPayloadBean {
        private @NonNullByDefault({}) Map<String, String> data;
        private @NonNullByDefault({}) Map<String, String> data_template;
        private @NonNullByDefault({}) Map<String, String> variables;

        /**
         * Default constructor for deserialization e.g. by Gson.
         */
        @SuppressWarnings("unused")
        protected ActionEventPayloadBean() {
        }

        public ActionEventPayloadBean(Map<String, String> data, Map<String, String> data_template,
                Map<String, String> variables) {
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
}
