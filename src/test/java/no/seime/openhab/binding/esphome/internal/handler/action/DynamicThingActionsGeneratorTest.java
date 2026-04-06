package no.seime.openhab.binding.esphome.internal.handler.action;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import io.esphome.api.ListEntitiesServicesArgument;
import io.esphome.api.ListEntitiesServicesResponse;
import io.esphome.api.ServiceArgType;

public class DynamicThingActionsGeneratorTest {

    @Test
    public void testGenerateTwiceReturnsCachedClass() throws Exception {
        final ListEntitiesServicesResponse rsp = ListEntitiesServicesResponse.newBuilder().setKey(1)
                .setName("play_buzzer").addArgs(ListEntitiesServicesArgument.newBuilder().setName("duration")
                        .setTypeValue(ServiceArgType.SERVICE_ARG_TYPE_INT_VALUE).build())
                .build();

        final ClassLoader classLoader = getClass().getClassLoader();

        final AbstractESPHomeThingAction first = DynamicThingActionsGenerator.generateDynamicThingAction(rsp,
                classLoader);
        assertNotNull(first);
        assertInstanceOf(AbstractESPHomeThingAction.class, first);

        final AbstractESPHomeThingAction second = assertDoesNotThrow(
                () -> DynamicThingActionsGenerator.generateDynamicThingAction(rsp, classLoader));
        assertNotNull(second);
        assertSame(first.getClass(), second.getClass());
    }
}
