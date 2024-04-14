package no.seime.openhab.binding.esphome.internal.message.statesubscription;

import java.util.*;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventFilter;
import org.openhab.core.events.EventSubscriber;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.ItemCommandEvent;
import org.openhab.core.items.events.ItemStateChangedEvent;
import org.openhab.core.items.events.ItemStateEvent;
import org.openhab.core.items.events.ItemStatePredictedEvent;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.events.ThingStatusInfoChangedEvent;
import org.openhab.core.thing.events.ThingStatusInfoEvent;
import org.openhab.core.types.Type;
import org.openhab.core.types.UnDefType;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

@Component(service = { EventSubscriber.class,
        ESPHomeEventSubscriber.class }, configurationPid = "binding.esphome.eventsubscriber")
public class ESPHomeEventSubscriber implements EventSubscriber {

    private final Logger logger = LoggerFactory.getLogger(ESPHomeEventSubscriber.class);

    public static final String ITEM_COMMAND_EVENT = "ItemCommandEvent";

    public static final String ITEM_STATE_EVENT = "ItemStateEvent";

    public static final String ITEM_STATE_PREDICTED_EVENT = "ItemStatePredictedEvent";

    public static final String ITEM_STATE_CHANGED_EVENT = "ItemStateChangedEvent";

    public static final String THING_STATUS_INFO_EVENT = "ThingStatusInfoEvent";

    public static final String THING_STATUS_INFO_CHANGED_EVENT = "ThingStatusInfoChangedEvent";

    private final ItemRegistry itemRegistry;
    private final ThingRegistry thingRegistry;

    private final static Set<String> supportedOpenhabEventTypes = Set.of(ITEM_COMMAND_EVENT, ITEM_STATE_EVENT,
            ITEM_STATE_PREDICTED_EVENT, ITEM_STATE_CHANGED_EVENT, THING_STATUS_INFO_EVENT,
            THING_STATUS_INFO_CHANGED_EVENT);
    private Map<ESPHomeHandler, List<EventSubscription>> eventSubscriptions = new HashMap<>();

    private final Set<String> subscribedEventTypes = new HashSet<>();

    @Activate
    public ESPHomeEventSubscriber(@Reference ItemRegistry itemRegistry, @Reference ThingRegistry thingRegistry) {
        this.itemRegistry = itemRegistry;
        this.thingRegistry = thingRegistry;
        subscribedEventTypes.add(ItemCommandEvent.TYPE);
        subscribedEventTypes.add(ItemStateEvent.TYPE);
        subscribedEventTypes.add(ItemStatePredictedEvent.TYPE);
        subscribedEventTypes.add(ItemStateChangedEvent.TYPE);
        subscribedEventTypes.add(ThingStatusInfoEvent.TYPE);
        subscribedEventTypes.add(ThingStatusInfoChangedEvent.TYPE);
    }

    public String getInitialState(String logPrefix, EventSubscription subscription) {

        // Send initial state
        TargetType targetType = subscription.getTargetType();
        String state = "";
        switch (targetType) {
            case ITEM:
                try {
                    Item item = itemRegistry.getItem(subscription.getTargetName());
                    if (item != null) {
                        state = toESPHomeStringState(item.getState());
                    }
                } catch (ItemNotFoundException e) {
                    logger.warn("[{}] Item not found for subscription {}", logPrefix, subscription);
                }
                break;
            case THING:
                @Nullable
                Thing thing = thingRegistry.get(new ThingUID(subscription.getTargetName()));
                if (thing != null) {
                    state = thing.getStatus().toString();
                } else {
                    logger.warn("[{}] Thing not found for subscription {}", logPrefix, subscription);

                }
                break;
            default:
                break;
        }

        return state;
    }

    private String toESPHomeStringState(Type state) {
        if (state instanceof UnDefType) {
            return "";
        } else if (state instanceof QuantityType<?> q) {
            return String.valueOf(q.doubleValue());
        } else {
            // Defaulting to this, not really sure if other types are relevant
            return state.toString();
        }
    }

    @Override
    public Set<String> getSubscribedEventTypes() {
        return subscribedEventTypes;
    }

    @Override
    public @Nullable EventFilter getEventFilter() {
        return null;
    }

    @Override
    public void receive(Event event) {
        List<EventSubscription> eventSubscriptions = this.eventSubscriptions.values().stream().flatMap(List::stream)
                .filter(subscription -> subscription.apply(event)).collect(Collectors.toList());

        if (!eventSubscriptions.isEmpty()) {
            Type ohState = extractPayload(event);
            String espHomeState = toESPHomeStringState(ohState);

            eventSubscriptions.forEach(
                    subscription -> subscription.getEspHomeHandler().handleOpenHABEvent(subscription, espHomeState));
        }
    }

    private Type extractPayload(Event event) {
        if (event instanceof ItemCommandEvent e) {
            return e.getItemCommand();
        } else if (event instanceof ItemStateEvent e) {
            return e.getItemState();
        } else if (event instanceof ItemStateChangedEvent e) {
            return e.getItemState();
        } else if (event instanceof ItemStatePredictedEvent e) {
            return e.getPredictedState();
        } else if (event instanceof ThingStatusInfoEvent e) {
            return new StringType(e.getStatusInfo().getStatus().toString());
        } else if (event instanceof ThingStatusInfoChangedEvent e) {
            return new StringType(e.getStatusInfo().getStatus().toString());
        } else {
            throw new IllegalArgumentException("Unsupported event type: " + event.getClass().getName());
        }
    }

    public void addEventSubscription(ESPHomeHandler handler, EventSubscription subscription) {
        eventSubscriptions.computeIfAbsent(handler, espHomeHandler -> new ArrayList<>()).add(subscription);
    }

    public void removeEventSubscriptions(ESPHomeHandler handler) {
        eventSubscriptions.remove(handler);
    }

    public EventSubscription createEventSubscription(String entityId, String attribute, ESPHomeHandler handler) {

        String[] parts = entityId.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException(String
                    .format("EntityId must be in the format <OpenhabEventType>.<ItemName>, but was %s", entityId));
        }

        String eventType = supportedOpenhabEventTypes.stream().filter(e -> e.equalsIgnoreCase(parts[0])).findFirst()
                .orElse(ITEM_STATE_CHANGED_EVENT);

        TargetType targetType = eventType.startsWith("Item") ? TargetType.ITEM : TargetType.THING;

        String targetName = targetType == TargetType.ITEM ? findCaseSensitiveItemName(parts[1])
                : parts[1].replaceAll("_", ":");
        String topicName = createTopicFromTargetName(eventType, targetName);

        EventSubscription subscription = new EventSubscription(entityId, attribute, topicName, targetType, targetName,
                handler);

        return subscription;
    }

    private String findCaseSensitiveItemName(String part) {
        Set<Item> collect = itemRegistry.getItems().stream().filter(item -> item.getName().equalsIgnoreCase(part))
                .collect(Collectors.toSet());
        if (collect.size() > 1) {
            logger.warn("Multiple items named " + part + " with different casing exists, using the first one");
            return collect.iterator().next().getName();
        } else if (collect.size() == 1) {
            return collect.iterator().next().getName();
        } else {
            logger.warn("Subscribing to item " + part
                    + ", but was unable to find the item in the item registry. If the item is created later, be aware that the case insensitive matching does not work");
            return part;
        }
    }

    String createTopicFromTargetName(String eventType, String targetName) {
        String topicName = null;
        switch (eventType) {
            case ITEM_COMMAND_EVENT: {
                topicName = "openhab/items/" + targetName + "/command";
                break;
            }

            case ITEM_STATE_EVENT: {
                topicName = "openhab/items/" + targetName + "/state";
                break;
            }
            case ITEM_STATE_PREDICTED_EVENT: {
                topicName = "openhab/items/" + targetName + "/statepredicted";
                break;
            }
            case ITEM_STATE_CHANGED_EVENT: {
                topicName = "openhab/items/" + targetName + "/statechanged";
                break;
            }
            case THING_STATUS_INFO_EVENT: {
                topicName = "openhab/things/" + targetName + "/status";
                break;
            }
            case THING_STATUS_INFO_CHANGED_EVENT: {
                topicName = "openhab/things/" + targetName + "/statuschanged";
                break;
            }
            default: {
                topicName = "openhab/items/" + targetName + "/statechanged";
                logger.warn("Unknown event type " + eventType + ", defaulting to {}", ITEM_STATE_CHANGED_EVENT);
            }
        }
        return topicName;
    }
}
