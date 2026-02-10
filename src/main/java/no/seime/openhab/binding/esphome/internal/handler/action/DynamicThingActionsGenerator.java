package no.seime.openhab.binding.esphome.internal.handler.action;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.List;

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
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.InstrumentedType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/**
 * Generates dynamic ThingActions for ESPHome devices based on provided ListEntitiesServicesResponse.
 */
public class DynamicThingActionsGenerator {

    public static AbstractESPHomeThingAction generateDynamicThingAction(ListEntitiesServicesResponse rsp,
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
                    .define("description",
                            formatDataType(arg)
                                    + (isArrayType(arg.getType()) ? " (array, separate values with comma)" : ""))
                    .build();

            methodBuilder = methodBuilder.withParameter(paramType, arg.getName()).annotateParameter(inputAnnotation);
        }

        thingActionType = methodBuilder.intercept(new ParameterMapImplementation(rsp.getArgsList()))
                .annotateMethod(ruleAction);

        Class<? extends AbstractESPHomeThingAction> dynamicClass = thingActionType.make()
                .load(thingActionClassLoader, ClassLoadingStrategy.Default.INJECTION).getLoaded();

        Object instance = dynamicClass.getDeclaredConstructor().newInstance();

        return (AbstractESPHomeThingAction) instance;
    }

    private static boolean isArrayType(ServiceArgType type) {
        return type == ServiceArgType.SERVICE_ARG_TYPE_INT_ARRAY || type == ServiceArgType.SERVICE_ARG_TYPE_BOOL_ARRAY
                || type == ServiceArgType.SERVICE_ARG_TYPE_STRING_ARRAY
                || type == ServiceArgType.SERVICE_ARG_TYPE_FLOAT_ARRAY;
    }

    private static String formatDataType(ListEntitiesServicesArgument arg) {
        return stripEnumPrefix(arg.getType());
    }

    public static String stripEnumPrefix(ServiceArgType argType) {
        String toRemove = "SERVICE_ARG_TYPE";
        return argType.toString().substring(toRemove.length() + 1);
    }

    private static Class<?> mapServiceArgumentTypeToJavaType(io.esphome.api.ServiceArgType type) {
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

    /**
     * Custom ByteBuddy implementation that generates bytecode to:
     * 1. Create a HashMap
     * 2. Put all method parameters into the map with their names as keys
     * 3. Call this.executeAction(map)
     */
    private record ParameterMapImplementation(List<ListEntitiesServicesArgument> arguments) implements Implementation {

        @Override
        public InstrumentedType prepare(InstrumentedType instrumentedType) {
            return instrumentedType;
        }

        @Override
        public ByteCodeAppender appender(Target implementationTarget) {
            return new ByteCodeAppender() {
                @Override
                public Size apply(MethodVisitor methodVisitor, Context implementationContext,
                        MethodDescription instrumentedMethod) {

                    // Create new HashMap
                    methodVisitor.visitTypeInsn(Opcodes.NEW, "java/util/HashMap");
                    methodVisitor.visitInsn(Opcodes.DUP);
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);

                    // Store the map in a local variable (slot after 'this' and all parameters)
                    int mapSlot = 1; // Start after 'this' (slot 0)
                    for (ListEntitiesServicesArgument arg : arguments) {
                        Class<?> paramType = mapServiceArgumentTypeToJavaType(arg.getType());
                        mapSlot += (paramType == long.class || paramType == double.class) ? 2 : 1;
                    }
                    methodVisitor.visitVarInsn(Opcodes.ASTORE, mapSlot);

                    // For each parameter, add it to the map
                    int paramSlot = 1; // Start at 1 (0 is 'this')
                    for (ListEntitiesServicesArgument arg : arguments) {
                        Class<?> paramType = mapServiceArgumentTypeToJavaType(arg.getType());

                        // Load map reference
                        methodVisitor.visitVarInsn(Opcodes.ALOAD, mapSlot);

                        // Load parameter name as String
                        methodVisitor.visitLdcInsn(arg.getName());

                        // Load parameter value and box if primitive
                        loadAndBoxParameter(methodVisitor, paramType, paramSlot);

                        // Call map.put(key, value)
                        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "put",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
                        methodVisitor.visitInsn(Opcodes.POP); // Discard return value

                        // Move to next parameter slot
                        paramSlot += (paramType == long.class || paramType == double.class) ? 2 : 1;
                    }

                    // Load 'this' reference
                    methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);

                    // Load the map
                    methodVisitor.visitVarInsn(Opcodes.ALOAD, mapSlot);

                    // Call this.executeAction(map)
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                            implementationTarget.getInstrumentedType().getInternalName(), "executeAction",
                            "(Ljava/util/Map;)V", false);

                    // Return void
                    methodVisitor.visitInsn(Opcodes.RETURN);

                    // Calculate stack and local variable sizes
                    return new Size(4, mapSlot + 1); // Max stack size, max locals
                }

                private void loadAndBoxParameter(MethodVisitor mv, Class<?> paramType, int slot) {
                    if (paramType == Boolean.class) {
                        mv.visitVarInsn(Opcodes.ALOAD, slot);
                    } else if (paramType == Integer.class) {
                        mv.visitVarInsn(Opcodes.ALOAD, slot);
                    } else if (paramType == Float.class) {
                        mv.visitVarInsn(Opcodes.ALOAD, slot);
                    } else if (paramType == Long.class) {
                        mv.visitVarInsn(Opcodes.ALOAD, slot);
                    } else if (paramType == Double.class) {
                        mv.visitVarInsn(Opcodes.ALOAD, slot);
                    } else {
                        // Reference type (String, etc.)
                        mv.visitVarInsn(Opcodes.ALOAD, slot);
                    }
                }
            };
        }

        private static Class<?> mapServiceArgumentTypeToJavaType(ServiceArgType type) {
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
}
