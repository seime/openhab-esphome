package no.seime.openhab.binding.esphome.internal.handler.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.esphome.api.ExecuteServiceRequest;
import io.esphome.api.ListEntitiesServicesArgument;
import io.esphome.api.ListEntitiesServicesResponse;
import io.esphome.api.ServiceArgType;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class AbstractESPHomeThingActionTest {

    private class TestAction extends AbstractESPHomeThingAction {
    }

    @Test
    public void test() {
        TestAction action = new TestAction();
        ListEntitiesServicesResponse.Builder actionBuilder = ListEntitiesServicesResponse.newBuilder().setKey(1)
                .setName("test_action");
        actionBuilder.addArgs(ListEntitiesServicesArgument.newBuilder().setName("bool_param")
                .setTypeValue(ServiceArgType.SERVICE_ARG_TYPE_BOOL_VALUE).build());
        actionBuilder.addArgs(ListEntitiesServicesArgument.newBuilder().setName("int_param")
                .setTypeValue(ServiceArgType.SERVICE_ARG_TYPE_INT_VALUE).build());
        actionBuilder.addArgs(ListEntitiesServicesArgument.newBuilder().setName("float_param")
                .setTypeValue(ServiceArgType.SERVICE_ARG_TYPE_FLOAT_VALUE).build());
        actionBuilder.addArgs(ListEntitiesServicesArgument.newBuilder().setName("string_param")
                .setTypeValue(ServiceArgType.SERVICE_ARG_TYPE_STRING_VALUE).build());
        actionBuilder.addArgs(ListEntitiesServicesArgument.newBuilder().setName("bool_array_param")
                .setTypeValue(ServiceArgType.SERVICE_ARG_TYPE_BOOL_ARRAY_VALUE).build());
        actionBuilder.addArgs(ListEntitiesServicesArgument.newBuilder().setName("int_array_param")
                .setTypeValue(ServiceArgType.SERVICE_ARG_TYPE_INT_ARRAY_VALUE).build());
        actionBuilder.addArgs(ListEntitiesServicesArgument.newBuilder().setName("float_array_param")
                .setTypeValue(ServiceArgType.SERVICE_ARG_TYPE_FLOAT_ARRAY_VALUE).build());
        actionBuilder.addArgs(ListEntitiesServicesArgument.newBuilder().setName("string_array_param")
                .setTypeValue(ServiceArgType.SERVICE_ARG_TYPE_STRING_ARRAY_VALUE).build());

        action.setListEntitiesServicesResponse(actionBuilder.build());

        ESPHomeHandler handler = Mockito.mock(ESPHomeHandler.class);
        action.setThingHandler(handler);
        ArgumentCaptor<ExecuteServiceRequest> requestCaptor = ArgumentCaptor.forClass(ExecuteServiceRequest.class);

        Map<String, Object> parameters = new java.util.HashMap<>();
        parameters.put("bool_param", true);
        parameters.put("int_param", 2);
        parameters.put("float_param", 3.5);
        parameters.put("string_param", "string");
        parameters.put("bool_array_param", "true,false");
        parameters.put("int_array_param", "2,3");
        parameters.put("float_array_param", "3.5,4.5");
        parameters.put("string_array_param", "string1,string2");

        action.executeAction(parameters);

        Mockito.verify(handler).executeAPIAction(requestCaptor.capture());

        // Verify the captured request
        ExecuteServiceRequest capturedRequest = requestCaptor.getValue();
        assertEquals(1, capturedRequest.getKey());
        assertEquals(8, capturedRequest.getArgsCount());

        assertTrue(capturedRequest.getArgsList().get(0).getBool());
        assertEquals(2, capturedRequest.getArgsList().get(1).getInt());
        assertEquals(3.5, capturedRequest.getArgsList().get(2).getFloat(), 0.0001);
        assertEquals("string", capturedRequest.getArgsList().get(3).getString());

        assertEquals(List.of(true, false), capturedRequest.getArgsList().get(4).getBoolArrayList());
        assertEquals(List.of(2, 3), capturedRequest.getArgsList().get(5).getIntArrayList());

        assertEquals(3.5, capturedRequest.getArgsList().get(6).getFloatArray(0), 0.0001);
        assertEquals(4.5, capturedRequest.getArgsList().get(6).getFloatArray(1), 0.0001);

        assertEquals(List.of("string1", "string2"), capturedRequest.getArgsList().get(7).getStringArrayList());
    }
}
