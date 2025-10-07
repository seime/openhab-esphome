/**
 * Copyright (c) 2023 Contributors to the Seime Openhab Addons project
 * <p>
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 * <p>
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * <p>
 * SPDX-License-Identifier: EPL-2.0
 */
package no.seime.openhab.binding.esphome.internal.module.handler;

import java.util.Arrays;
import java.util.Collection;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.automation.Module;
import org.openhab.core.automation.Trigger;
import org.openhab.core.automation.handler.BaseModuleHandlerFactory;
import org.openhab.core.automation.handler.ModuleHandler;
import org.openhab.core.automation.handler.ModuleHandlerFactory;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This HandlerFactory creates TimerTriggerHandlers to control items within the
 * RuleManager.
 *
 * @author Christoph Knauf - Initial contribution
 * @author Kai Kreuzer - added new module types
 */
@NonNullByDefault
@Component(immediate = true, service = ModuleHandlerFactory.class)
public class ESPHomeModuleHandlerFactory extends BaseModuleHandlerFactory {

    private final Logger logger = LoggerFactory.getLogger(ESPHomeModuleHandlerFactory.class);

    private static final Collection<String> TYPES = Arrays.asList(ActionTriggerHandler.MODULE_TYPE_ID,
            EventTriggerHandler.MODULE_TYPE_ID, TagScannedTriggerHandler.MODULE_TYPE_ID);

    private final BundleContext bundleContext;

    @Activate
    public ESPHomeModuleHandlerFactory(final BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    @Override
    @Deactivate
    public void deactivate() {
        super.deactivate();
    }

    @Override
    public Collection<String> getTypes() {
        return TYPES;
    }

    @Override
    protected @Nullable ModuleHandler internalCreate(Module module, String ruleUID) {
        logger.trace("create {} -> {}", module.getId(), module.getTypeUID());
        String moduleTypeUID = module.getTypeUID();
        if (module instanceof Trigger trigger) {
            switch (moduleTypeUID) {
                case ActionTriggerHandler.MODULE_TYPE_ID:
                    return new ActionTriggerHandler(trigger, bundleContext);
                case EventTriggerHandler.MODULE_TYPE_ID:
                    return new EventTriggerHandler(trigger, bundleContext);
                case TagScannedTriggerHandler.MODULE_TYPE_ID:
                    return new TagScannedTriggerHandler(trigger, bundleContext);
            }
        }
        logger.error("The module handler type '{}' is not supported.", moduleTypeUID);
        return null;
    }
}
