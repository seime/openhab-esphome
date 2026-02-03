package no.seime.openhab.binding.esphome.internal.handler.action;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

import org.openhab.core.automation.annotation.ActionInput;
import org.openhab.core.automation.annotation.RuleAction;
import org.openhab.core.thing.binding.ThingActions;
import org.openhab.core.thing.binding.ThingActionsScope;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;

import com.google.common.base.CaseFormat;

import io.esphome.api.ListEntitiesServicesArgument;
import io.esphome.api.ListEntitiesServicesResponse;
import io.esphome.api.ServiceArgType;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.FixedValue;

public class DynamicThingActionsGenerator {

    public AbstractESPHomeThingAction generateActions(ListEntitiesServicesResponse rsp,
            ClassLoader thingActionClassLoader)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, InstantiationException {

        String methodName = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, rsp.getName());
        String classNameAsString = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, rsp.getName());
        String label = rsp.getName();
        String description = String.format("Executes the '%s' action on the device", rsp.getName());

        // Define ThingAction implementation class
        AnnotationDescription componentAnnotation = AnnotationDescription.Builder.ofType(Component.class)
                .define("scope", ServiceScope.PROTOTYPE)
                .defineTypeArray("service", TypeDescription.ForLoadedType.of(ThingActions.class)).build();

        AnnotationDescription thingScopeAction = AnnotationDescription.Builder.ofType(ThingActionsScope.class)
                .define("name", "esphome").build();

        String className = String.format("no.seime.openhab.binding.esphome.internal.handler.action.%s",
                classNameAsString);

        DynamicType.Builder<AbstractESPHomeThingAction> thingActionType = new ByteBuddy()
                .subclass(AbstractESPHomeThingAction.class).name(className).annotateType(componentAnnotation)
                .annotateType(thingScopeAction);

        // Define rule action method
        AnnotationDescription ruleAction = AnnotationDescription.Builder.ofType(RuleAction.class).define("label", label)
                .define("description", description).build();

        DynamicType.Builder.MethodDefinition.ParameterDefinition<AbstractESPHomeThingAction> methodBuilder = thingActionType
                .defineMethod(methodName, void.class, Modifier.PUBLIC);

        for (ListEntitiesServicesArgument arg : rsp.getArgsList()) {
            Class<?> paramType = mapServiceArgumentTypeToJavaType(arg.getType());

            AnnotationDescription inputAnnotation = AnnotationDescription.Builder.ofType(ActionInput.class)
                    .define("name", arg.getName()).define("label", arg.getName())
                    .define("description", formatDataType(arg)).build();

            methodBuilder = methodBuilder.withParameter(paramType, arg.getName()).annotateParameter(inputAnnotation);
        }

        thingActionType = methodBuilder.intercept(FixedValue.originType()).annotateMethod(ruleAction);

        Class<? extends AbstractESPHomeThingAction> dynamicClass = thingActionType.make()
                .load(thingActionClassLoader, ClassLoadingStrategy.Default.INJECTION).getLoaded();

        Object instance = dynamicClass.getDeclaredConstructor().newInstance();

        return (AbstractESPHomeThingAction) instance;
    }

    private static String formatDataType(ListEntitiesServicesArgument arg) {
        return stripEnumPrefix(arg.getType());
    }

    public static String stripEnumPrefix(ServiceArgType argType) {
        String toRemove = "SERVICE_ARG_TYPE";
        return argType.toString().substring(toRemove.length() + 1);
    }

    private Class<?> mapServiceArgumentTypeToJavaType(io.esphome.api.ServiceArgType type) {
        switch (type) {
            case SERVICE_ARG_TYPE_BOOL:
                return Boolean.class;
            case SERVICE_ARG_TYPE_INT:
                return Integer.class;
            case SERVICE_ARG_TYPE_FLOAT:
                return Float.class;
            case SERVICE_ARG_TYPE_STRING:
            case SERVICE_ARG_TYPE_INT_ARRAY:
            case SERVICE_ARG_TYPE_BOOL_ARRAY:
            case SERVICE_ARG_TYPE_STRING_ARRAY:
            case SERVICE_ARG_TYPE_FLOAT_ARRAY:
            default:
                return String.class;
        }
    }
}
