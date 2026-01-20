package no.seime.openhab.binding.esphome.internal.handler.action;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

import org.openhab.core.automation.annotation.RuleAction;
import org.openhab.core.thing.binding.ThingActions;

import io.esphome.api.ListEntitiesServicesResponse;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.FixedValue;

public class DynamicThingActionsGenerator {

    public ThingActions generateActions(ListEntitiesServicesResponse rsp)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, InstantiationException {

        // --- Dynamic annotation values ---
        String actionId = "turnOn";
        String label = "Turn device on";
        String description = "Turns the device on via binding action";

        AnnotationDescription ruleAction = AnnotationDescription.Builder.ofType(RuleAction.class).define("label", label)
                .define("description", description).build();

        Class<?> dynamicClass = new ByteBuddy().subclass(Object.class).implement(ThingActions.class)
                .name("org.openhab.generated.DynamicActions").defineMethod("turnOn", String.class, Modifier.PUBLIC)
                .intercept(FixedValue.value("Device turned on")).annotateMethod(ruleAction).make()
                .load(DynamicThingActionsGenerator.class.getClassLoader(), ClassLoadingStrategy.Default.INJECTION)
                .getLoaded();

        Object instance = dynamicClass.getDeclaredConstructor().newInstance();
        // String result = (String) dynamicClass.getMethod("turnOn").invoke(instance);
        // System.out.println(result);

        // --- Verify annotation at runtime ---

        return (ThingActions) instance;
    }
}
