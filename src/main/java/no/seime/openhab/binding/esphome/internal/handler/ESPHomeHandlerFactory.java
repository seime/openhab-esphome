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
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.BluetoothAdapter;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jano7.executor.KeySequentialExecutor;

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

    private final Logger logger = LoggerFactory.getLogger(ESPHomeHandlerFactory.class);

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Set.of(BindingConstants.THING_TYPE_DEVICE,
            BindingConstants.THING_TYPE_BLE_PROXY);

    private @Nullable String defaultEncryptionKey;

    private final AtomicLong threadCounter = new AtomicLong(0);

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    private final ESPChannelTypeProvider dynamicChannelTypeProvider;
    private final ESPStateDescriptionProvider stateDescriptionProvider;
    private final ESPHomeEventSubscriber eventSubscriber;

    private final ThingRegistry thingRegistry;
    private final MonitoredScheduledThreadPoolExecutor scheduler;
    private final KeySequentialExecutor packetExecutor;
    private final ConnectionSelector connectionSelector;

    @Activate
    public ESPHomeHandlerFactory(@Reference ESPChannelTypeProvider dynamicChannelTypeProvider,
            @Reference ESPStateDescriptionProvider stateDescriptionProvider,
            @Reference ESPHomeEventSubscriber eventSubscriber, @Reference ThingRegistry thingRegistry)
            throws IOException {
        scheduler = new MonitoredScheduledThreadPoolExecutor(4, r -> {
            long currentCount = threadCounter.incrementAndGet();
            logger.debug("Creating new worker thread {} for scheduler", currentCount);
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("ESPHome Thing Scheduler " + currentCount);
            return t;
        }, 300);

        packetExecutor = new KeySequentialExecutor(scheduler);

        this.dynamicChannelTypeProvider = dynamicChannelTypeProvider;
        this.stateDescriptionProvider = stateDescriptionProvider;
        this.eventSubscriber = eventSubscriber;
        this.thingRegistry = thingRegistry;

        connectionSelector = new ConnectionSelector();
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (BindingConstants.THING_TYPE_DEVICE.equals(thingTypeUID)) {
            return new ESPHomeHandler(thing, connectionSelector, dynamicChannelTypeProvider, stateDescriptionProvider,
                    eventSubscriber, scheduler, packetExecutor, defaultEncryptionKey);
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
        Dictionary<String, Object> properties = componentContext.getProperties();
        defaultEncryptionKey = StringUtils.trimToNull((String) properties.get("defaultEncryptionKey"));
        if (defaultEncryptionKey != null) {
            logger.info(
                    "Found binding default encryption key for ESPHome devices, will use if not configured on thing");
        }
    }

    @Override
    protected void deactivate(ComponentContext componentContext) {
        connectionSelector.stop();
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.warn("Scheduler did not terminate in time. This may indicate ESPs with hanging connections");
        }

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
