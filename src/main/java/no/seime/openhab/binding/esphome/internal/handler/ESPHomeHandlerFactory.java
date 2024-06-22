/**
 * Copyright (c) 2023 Contributors to the Seime Openhab Addons project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package no.seime.openhab.binding.esphome.internal.handler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.BluetoothAdapter;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.bluetooth.ESPHomeBluetoothProxyHandler;
import no.seime.openhab.binding.esphome.internal.comm.ConnectionSelector;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.ESPHomeEventSubscriber;

/**
 * The {@link ESPHomeHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Arne Seime - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.esphome", service = ThingHandlerFactory.class)
public class ESPHomeHandlerFactory extends BaseThingHandlerFactory {

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Set.of(BindingConstants.THING_TYPE_DEVICE,
            BindingConstants.THING_TYPE_BLE_PROXY);

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    private final ConnectionSelector connectionSelector;

    private final ESPChannelTypeProvider dynamicChannelTypeProvider;
    private final ESPHomeEventSubscriber eventSubscriber;
    private final ThingRegistry thingRegistry;

    @Activate
    public ESPHomeHandlerFactory(@Reference ESPChannelTypeProvider dynamicChannelTypeProvider,
            @Reference ESPHomeEventSubscriber eventSubscriber, @Reference ItemRegistry itemRegistry,
            @Reference ThingRegistry thingRegistry) throws IOException {
        this.dynamicChannelTypeProvider = dynamicChannelTypeProvider;

        this.eventSubscriber = eventSubscriber;
        this.thingRegistry = thingRegistry;
        eventSubscriber.setItemRegistry(itemRegistry);
        eventSubscriber.setThingRegistry(thingRegistry);

        connectionSelector = new ConnectionSelector();
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (BindingConstants.THING_TYPE_DEVICE.equals(thingTypeUID)) {
            return new ESPHomeHandler(thing, connectionSelector, dynamicChannelTypeProvider, eventSubscriber);
        } else if (BindingConstants.THING_TYPE_BLE_PROXY.equals(thingTypeUID)) {
            ESPHomeBluetoothProxyHandler handler = new ESPHomeBluetoothProxyHandler((Bridge) thing, thingRegistry);
            registerBluetoothAdapter(handler);
            return handler;
        }

        return null;
    }

    @Override
    protected void activate(ComponentContext componentContext) {
        super.activate(componentContext);
        connectionSelector.start();
    }

    @Override
    protected void deactivate(ComponentContext componentContext) {
        connectionSelector.stop();

        super.deactivate(componentContext);
    }

    private final Map<ThingUID, ServiceRegistration<?>> serviceRegs = new HashMap<>();

    private synchronized void registerBluetoothAdapter(BluetoothAdapter adapter) {
        serviceRegs.put(adapter.getUID(),
                bundleContext.registerService(BluetoothAdapter.class.getName(), adapter, new Hashtable<>()));
    }

    @Override
    protected synchronized void removeHandler(ThingHandler thingHandler) {
        if (thingHandler instanceof BluetoothAdapter bluetoothAdapter) {
            UID uid = bluetoothAdapter.getUID();
            ServiceRegistration<?> serviceReg = serviceRegs.remove(uid);
            if (serviceReg != null) {
                serviceReg.unregister();
            }
        }
    }
}
