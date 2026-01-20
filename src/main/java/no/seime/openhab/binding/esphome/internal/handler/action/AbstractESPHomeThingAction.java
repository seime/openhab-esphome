package no.seime.openhab.binding.esphome.internal.handler.action;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.binding.ThingActions;
import org.openhab.core.thing.binding.ThingHandler;

public class AbstractESPHomeThingAction implements ThingActions {
    @Override
    public void setThingHandler(ThingHandler handler) {
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return null;
    }
}
