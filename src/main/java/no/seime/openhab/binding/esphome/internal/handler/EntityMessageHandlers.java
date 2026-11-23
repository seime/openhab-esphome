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
package no.seime.openhab.binding.esphome.internal.handler;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.protobuf.GeneratedMessage;

import io.esphome.api.*;
import no.seime.openhab.binding.esphome.internal.EntityTypes;
import no.seime.openhab.binding.esphome.internal.message.*;

/**
 * The {@link AbstractMessageHandler} of each ESPHome entity type, looked up by the entity type of a channel or by
 * the class of a message from the device.
 *
 * @author Arne Seime - Initial contribution
 */
@NonNullByDefault
final class EntityMessageHandlers {

    private final Map<String, AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage>> byEntityType = new HashMap<>();
    private final Map<Class<? extends GeneratedMessage>, AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage>> byMessageClass = new HashMap<>();

    EntityMessageHandlers(ESPHomeHandler handler) {
        register(EntityTypes.SELECT, new SelectMessageHandler(handler), ListEntitiesSelectResponse.class,
                SelectStateResponse.class);
        register(EntityTypes.SENSOR, new SensorMessageHandler(handler), ListEntitiesSensorResponse.class,
                SensorStateResponse.class);
        register(EntityTypes.BINARY_SENSOR, new BinarySensorMessageHandler(handler),
                ListEntitiesBinarySensorResponse.class, BinarySensorStateResponse.class);
        register(EntityTypes.TEXT_SENSOR, new TextSensorMessageHandler(handler), ListEntitiesTextSensorResponse.class,
                TextSensorStateResponse.class);
        register(EntityTypes.TEXT, new TextMessageHandler(handler), ListEntitiesTextResponse.class,
                TextStateResponse.class);
        register(EntityTypes.SWITCH, new SwitchMessageHandler(handler), ListEntitiesSwitchResponse.class,
                SwitchStateResponse.class);
        register(EntityTypes.CLIMATE, new ClimateMessageHandler(handler), ListEntitiesClimateResponse.class,
                ClimateStateResponse.class);
        register(EntityTypes.NUMBER, new NumberMessageHandler(handler), ListEntitiesNumberResponse.class,
                NumberStateResponse.class);
        register(EntityTypes.LIGHT, new LightMessageHandler(handler), ListEntitiesLightResponse.class,
                LightStateResponse.class);
        register(EntityTypes.BUTTON, new ButtonMessageHandler(handler), ListEntitiesButtonResponse.class,
                ButtonCommandRequest.class);
        register(EntityTypes.COVER, new CoverMessageHandler(handler), ListEntitiesCoverResponse.class,
                CoverStateResponse.class);
        register(EntityTypes.FAN, new FanMessageHandler(handler), ListEntitiesFanResponse.class,
                FanStateResponse.class);
        register(EntityTypes.DATE, new DateMessageHandler(handler), ListEntitiesDateResponse.class,
                DateStateResponse.class);
        register(EntityTypes.DATE_TIME, new DateTimeMessageHandler(handler), ListEntitiesDateTimeResponse.class,
                DateTimeStateResponse.class);
        register(EntityTypes.TIME, new TimeMessageHandler(handler), ListEntitiesTimeResponse.class,
                TimeStateResponse.class);
        register(EntityTypes.LOCK, new LockMessageHandler(handler), ListEntitiesLockResponse.class,
                LockStateResponse.class);
        register(EntityTypes.VALVE, new ValveMessageHandler(handler), ListEntitiesValveResponse.class,
                ValveStateResponse.class);
        register(EntityTypes.EVENT, new EventMessageHandler(handler), ListEntitiesEventResponse.class,
                EventResponse.class);
        register(EntityTypes.MEDIA_PLAYER, new MediaPlayerMessageHandler(handler),
                ListEntitiesMediaPlayerResponse.class, MediaPlayerStateResponse.class);
    }

    private void register(String entityType,
            AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage> messageHandler,
            Class<? extends GeneratedMessage> listEntitiesClass, Class<? extends GeneratedMessage> stateClass) {
        byEntityType.put(entityType, messageHandler);
        byMessageClass.put(listEntitiesClass, messageHandler);
        byMessageClass.put(stateClass, messageHandler);
    }

    @Nullable
    AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage> forEntityType(String entityType) {
        return byEntityType.get(entityType);
    }

    @Nullable
    AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage> forMessage(
            GeneratedMessage message) {
        return byMessageClass.get(message.getClass());
    }
}
