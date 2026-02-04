package no.seime.openhab.binding.esphome.internal.handler.action;

import java.util.Arrays;
import java.util.Map;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.binding.ThingActions;
import org.openhab.core.thing.binding.ThingHandler;

import io.esphome.api.ExecuteServiceArgument;
import io.esphome.api.ExecuteServiceRequest;
import io.esphome.api.ListEntitiesServicesArgument;
import io.esphome.api.ListEntitiesServicesResponse;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public abstract class AbstractESPHomeThingAction implements ThingActions {
    private ESPHomeHandler handler;

    private ListEntitiesServicesResponse listEntitiesServicesResponse;

    @Override
    public void setThingHandler(@Nullable ThingHandler handler) {
        if (handler instanceof ESPHomeHandler bridgeHandler) {
            this.handler = bridgeHandler;
        }
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return handler;
    }

    protected void executeAction(Map<String, Object> parameters) {
        ESPHomeHandler handler = this.handler;

        if (handler == null) {
            return;
        }

        ExecuteServiceRequest.Builder cmd = ExecuteServiceRequest.newBuilder()
                .setKey(listEntitiesServicesResponse.getKey());
        for (ListEntitiesServicesArgument arg : listEntitiesServicesResponse.getArgsList()) {
            ExecuteServiceArgument.Builder argument = ExecuteServiceArgument.newBuilder();
            Object parameterValue = parameters.get(arg.getName());
            setArgumentValue(arg, parameterValue, argument);
            cmd.addArgs(argument);
        }

        handler.executeAPIAction(cmd.build());
    }

    private void setArgumentValue(ListEntitiesServicesArgument arg, @Nullable Object parameterValue,
            ExecuteServiceArgument.Builder argument) {
        if (parameterValue != null) {
            switch (arg.getType()) {
                case SERVICE_ARG_TYPE_INT -> argument.setInt((Integer) parameterValue);
                case SERVICE_ARG_TYPE_FLOAT -> argument.setFloat(((Double) parameterValue).floatValue());
                case SERVICE_ARG_TYPE_BOOL -> argument.setBool((Boolean) parameterValue);
                case SERVICE_ARG_TYPE_STRING -> argument.setString((String) parameterValue);
                case SERVICE_ARG_TYPE_INT_ARRAY -> argument.addAllIntArray(
                        Arrays.stream(((String) parameterValue).split(",")).map(e -> Integer.parseInt(e)).toList());
                case SERVICE_ARG_TYPE_FLOAT_ARRAY -> argument.addAllFloatArray(
                        Arrays.stream(((String) parameterValue).split(",")).map(e -> Float.parseFloat(e)).toList());
                case SERVICE_ARG_TYPE_BOOL_ARRAY -> argument.addAllBoolArray(
                        Arrays.stream(((String) parameterValue).split(",")).map(e -> Boolean.parseBoolean(e)).toList());
                case SERVICE_ARG_TYPE_STRING_ARRAY ->
                    argument.addAllStringArray(Arrays.asList(((String) parameterValue).split(",")));
            }
        }
    }

    public void setListEntitiesServicesResponse(ListEntitiesServicesResponse listEntitiesServicesResponse) {
        this.listEntitiesServicesResponse = listEntitiesServicesResponse;
    }
}
