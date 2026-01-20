package no.seime.openhab.binding.esphome.internal.handler.action;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.automation.annotation.ActionInput;
import org.openhab.core.automation.annotation.RuleAction;
import org.openhab.core.thing.binding.ThingActions;
import org.openhab.core.thing.binding.ThingActionsScope;
import org.openhab.core.thing.binding.ThingHandler;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

/**
 * The {@link DeviceAction} provides wrapper for sending api actions to the device. Actions are provided via the
 * api.actions esphome configuration
 *
 * @author Arne Seime - Initial contribution
 */
@Component(scope = ServiceScope.PROTOTYPE, service = DeviceAction.class)
@ThingActionsScope(name = "esphome")
@NonNullByDefault
public class DeviceAction implements ThingActions {
    private final Logger logger = LoggerFactory.getLogger(DeviceAction.class);

    private @Nullable ESPHomeHandler handler;

    @RuleAction(label = "API Action", description = "API Action")
    public void callService(
            @ActionInput(name = "parameters", label = "Map of keys (parameter names) and values (parameter value)", description = "No description") String parameters) {
        ESPHomeHandler handler = this.handler;

        if (handler == null) {
            logger.warn("ESPHome DeviceActions service ThingHandler is null!");
        }
    }

    public static void callService(@Nullable ThingActions actions, String parameters) {
        if (actions instanceof DeviceAction action) {
            action.callService(parameters);
        }
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
