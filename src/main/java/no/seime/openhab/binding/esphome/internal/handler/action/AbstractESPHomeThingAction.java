package no.seime.openhab.binding.esphome.internal.handler.action;

import java.util.List;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.binding.ThingActions;
import org.openhab.core.thing.binding.ThingHandler;

import io.esphome.api.ListEntitiesServicesResponse;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public abstract class AbstractESPHomeThingAction implements ThingActions {
    private ESPHomeHandler handler;

    private ListEntitiesServicesResponse listEntitiesServicesResponse;

    public void setListEntitiesServicesResponse(ListEntitiesServicesResponse listEntitiesServicesResponse) {
        this.listEntitiesServicesResponse = listEntitiesServicesResponse;
    }

    protected void executeAction(List<? extends Object> parameters) {
        ESPHomeHandler handler = this.handler;

        if (handler == null) {
            return;
        }

        handler.executeAPIAction(listEntitiesServicesResponse.getKey(), parameters);
    }

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
}
