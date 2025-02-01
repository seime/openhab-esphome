package no.seime.openhab.binding.esphome.internal.message.statesubscription;

import java.util.regex.Pattern;

import org.openhab.core.events.Event;
import org.openhab.core.events.EventFilter;

import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class EventSubscription implements EventFilter {
    private final String entityId;
    private final String attribute;
    private Pattern topicPattern;
    private TargetType targetType;
    private String targetName;
    private final ESPHomeHandler espHomeHandler;

    public EventSubscription(String entityId, String attribute, Pattern topicPattern, TargetType targetType,
            String targetName, ESPHomeHandler espHomeHandler) {
        this.entityId = entityId;
        this.attribute = attribute;
        this.topicPattern = topicPattern;
        this.targetType = targetType;
        this.targetName = targetName;
        this.espHomeHandler = espHomeHandler;
    }

    @Override
    public boolean apply(Event event) {
        return topicPattern != null && topicPattern.matcher(event.getTopic()).matches();
    }

    public String getEntityId() {
        return entityId;
    }

    public String getAttribute() {
        return attribute;
    }

    public ESPHomeHandler getEspHomeHandler() {
        return espHomeHandler;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public String getTargetName() {
        return targetName;
    }
}
