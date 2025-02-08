package no.seime.openhab.binding.esphome.internal.message.statesubscription;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.events.Event;
import org.openhab.core.events.EventFilter;
import org.openhab.core.events.EventSubscriber;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.events.*;
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

    public static final String ITEM_COMMAND_EVENT = "ItemCommandEvent";
    public static final String ITEM_STATE_EVENT = "ItemStateEvent";
    public static final String ITEM_STATE_PREDICTED_EVENT = "ItemStatePredictedEvent";
    public static final String ITEM_STATE_CHANGED_EVENT = "ItemStateChangedEvent";
    public static final String ITEM_STATE_UPDATED_EVENT = "ItemStateUpdatedEvent";
    public static final String GROUP_ITEM_STATE_CHANGED_EVENT = "GroupItemStateChangedEvent";
    public static final String GROUP_STATE_UPDATED_EVENT = "GroupStateUpdatedEvent";
    public static final String THING_STATUS_INFO_EVENT = "ThingStatusInfoEvent";
    public static final String THING_STATUS_INFO_CHANGED_EVENT = "ThingStatusInfoChangedEvent";
    private final static Set<String> supportedOpenhabEventTypes = Set.of(ITEM_COMMAND_EVENT, ITEM_STATE_EVENT,
            ITEM_STATE_PREDICTED_EVENT, ITEM_STATE_CHANGED_EVENT, ITEM_STATE_UPDATED_EVENT,
            GROUP_ITEM_STATE_CHANGED_EVENT, GROUP_STATE_UPDATED_EVENT, THING_STATUS_INFO_EVENT,
            THING_STATUS_INFO_CHANGED_EVENT);
    private final Logger logger = LoggerFactory.getLogger(ESPHomeEventSubscriber.class);
    private final Set<String> subscribedEventTypes = new HashSet<>();
    private final Map<ESPHomeHandler, List<EventSubscription>> eventSubscriptions = new HashMap<>();
    private ItemRegistry itemRegistry;
    private ThingRegistry thingRegistry;

    @Activate
    public ESPHomeEventSubscriber(@Reference ThingRegistry thingRegistry, @Reference ItemRegistry itemRegistry) {
        this.thingRegistry = thingRegistry;
        this.itemRegistry = itemRegistry;
        subscribedEventTypes.add(ItemCommandEvent.TYPE);
        subscribedEventTypes.add(ItemStateEvent.TYPE);
        subscribedEventTypes.add(ItemStatePredictedEvent.TYPE);
        subscribedEventTypes.add(ItemStateChangedEvent.TYPE);
        subscribedEventTypes.add(ItemStateUpdatedEvent.TYPE);
        subscribedEventTypes.add(GroupItemStateChangedEvent.TYPE);
        subscribedEventTypes.add(GroupStateUpdatedEvent.TYPE);
        subscribedEventTypes.add(ThingStatusInfoEvent.TYPE);
        subscribedEventTypes.add(ThingStatusInfoChangedEvent.TYPE);
    }

    public void setItemRegistry(ItemRegistry itemRegistry) {
        this.itemRegistry = itemRegistry;
    }

    public void setThingRegistry(ThingRegistry thingRegistry) {
        this.thingRegistry = thingRegistry;
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
            // No units allowed
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
        } else if (event instanceof ItemStateUpdatedEvent e) {
            return e.getItemState();
        } else if (event instanceof ItemStatePredictedEvent e) {
            return e.getPredictedState();
        } else if (event instanceof GroupStateUpdatedEvent e) {
            return e.getItemState();
        } else if (event instanceof GroupItemStateChangedEvent e) {
            return e.getItemState();
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

        TargetType targetType = eventType.startsWith("Item") || eventType.startsWith("Group") ? TargetType.ITEM
                : TargetType.THING;

        String targetName = targetType == TargetType.ITEM ? findCaseSensitiveItemName(parts[1])
                : parts[1].replaceAll("_", ":");
        Pattern toppicPattern = createTopicFromTargetName(eventType, targetName);

        EventSubscription subscription = new EventSubscription(entityId, attribute, toppicPattern, targetType,
                targetName, handler);

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

    Pattern createTopicFromTargetName(String eventType, String targetName) {
        switch (eventType) {
            // Single items
            case ITEM_COMMAND_EVENT -> {
                return Pattern.compile("openhab/items/" + targetName + "/command");
            }
            case ITEM_STATE_EVENT -> {
                return Pattern.compile("openhab/items/" + targetName + "/state");
            }
            case ITEM_STATE_PREDICTED_EVENT -> {
                return Pattern.compile("openhab/items/" + targetName + "/statepredicted");
            }
            case ITEM_STATE_UPDATED_EVENT -> {
                return Pattern.compile("openhab/items/" + targetName + "/stateupdated");
            }
            case ITEM_STATE_CHANGED_EVENT -> {
                return Pattern.compile("openhab/items/" + targetName + "/statechanged");
            }
            // Groups
            case GROUP_ITEM_STATE_CHANGED_EVENT -> {
                return Pattern.compile("openhab/items/" + targetName + "/.*/statechanged");
            }
            case GROUP_STATE_UPDATED_EVENT -> {
                return Pattern.compile("openhab/items/" + targetName + "/.*/stateupdated");
            }

            // Things
            case THING_STATUS_INFO_EVENT -> {
                return Pattern.compile("openhab/things/" + targetName + "/status");
            }
            case THING_STATUS_INFO_CHANGED_EVENT -> {
                return Pattern.compile("openhab/things/" + targetName + "/statuschanged");
            }
            default -> {
                logger.warn("Unknown event type " + eventType + ", defaulting to {}", ITEM_STATE_CHANGED_EVENT);
                return Pattern.compile("openhab/items/" + targetName + "/statechanged");
            }
        }
    }
}
