package no.seime.openhab.binding.esphome.devicetest;

import java.lang.reflect.Method;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Comparator;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.events.Event;
import org.openhab.core.items.events.ItemEventFactory;
import org.openhab.core.types.State;

/**
 * Creates item events via {@link ItemEventFactory} in a way that compiles against both openHAB 4.x and 5.x.
 * openHAB 5 added (nullable) {@link ZonedDateTime} lastStateUpdate/lastStateChange parameters to several factory
 * methods and removed the old overloads. The matching overload is located reflectively and any
 * {@link ZonedDateTime} parameters are passed as null.
 */
public final class ItemEventFactoryCompat {

    private ItemEventFactoryCompat() {
    }

    public static Event stateChanged(String itemName, State newState, State oldState) {
        return invoke("createStateChangedEvent", new Class<?>[] { String.class, State.class, State.class }, itemName,
                newState, oldState);
    }

    public static Event stateUpdated(String itemName, State state) {
        return invoke("createStateUpdatedEvent", new Class<?>[] { String.class, State.class }, itemName, state);
    }

    public static Event groupStateChanged(String groupName, String memberName, State newState, State oldState) {
        return invoke("createGroupStateChangedEvent",
                new Class<?>[] { String.class, String.class, State.class, State.class }, groupName, memberName,
                newState, oldState);
    }

    public static Event groupStateUpdated(String groupName, String memberName, State state, @Nullable String source) {
        return invoke("createGroupStateUpdatedEvent",
                new Class<?>[] { String.class, String.class, State.class, String.class }, groupName, memberName, state,
                source);
    }

    /**
     * Finds the shortest overload whose parameter list equals {@code types} once all {@link ZonedDateTime}
     * parameters are removed, and invokes it with null for those parameters.
     */
    private static Event invoke(String methodName, Class<?>[] types, @Nullable Object... args) {
        Method method = Arrays.stream(ItemEventFactory.class.getMethods()).filter(m -> m.getName().equals(methodName))
                .filter(m -> Arrays.equals(types,
                        Arrays.stream(m.getParameterTypes()).filter(t -> t != ZonedDateTime.class).toArray()))
                .min(Comparator.comparingInt(Method::getParameterCount))
                .orElseThrow(() -> new IllegalStateException("No compatible ItemEventFactory." + methodName));

        Class<?>[] paramTypes = method.getParameterTypes();
        @Nullable
        Object[] callArgs = new Object[paramTypes.length];
        int next = 0;
        for (int i = 0; i < paramTypes.length; i++) {
            callArgs[i] = paramTypes[i] == ZonedDateTime.class ? null : args[next++];
        }
        try {
            // Static method: receiver is ignored, pass non-null to satisfy null analysis
            return (Event) method.invoke(ItemEventFactory.class, callArgs);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke ItemEventFactory." + methodName, e);
        }
    }
}
