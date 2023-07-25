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
package no.seime.openhab.binding.esphome.internal.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link BindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Arne Seime - Initial contribution
 */
@NonNullByDefault
public class BindingConstants {

    public static final String BINDING_ID = "esphome";

    // List of all Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_DEVICE = new ThingTypeUID(BINDING_ID, "device");

    public static final String COMMAND_KEY = "command_key";
    public static final String COMMAND_CLASS = "command_class";
    public static final String COMMAND_FIELD = "command_field";

    public static final String CHANNEL_TYPE_TEMPERATURE = "temperature";
    public static final String CHANNEL_TYPE_DISTANCE = "distance";
    public static final String CHANNEL_TYPE_HUMIDITY = "humidity";
    public static final String CHANNEL_TYPE_NUMBER = "number";
    public static final String CHANNEL_TYPE_SWITCH = "switch";
    public static final String CHANNEL_TYPE_CONTACT = "contact";
    public static final String CHANNEL_NAME_MODE = "mode";
    public static final String CHANNEL_NAME_CURRENT_TEMPERATURE = "current_temperature";
    public static final String CHANNEL_NAME_TARGET_TEMPERATURE = "target_temperature";
    public static final String CHANNEL_NAME_SWING_MODE = "swing_mode";
    public static final String CHANNEL_NAME_FAN_MODE = "fan_mode";
    public static final String CHANNEL_NAME_CUSTOM_FAN_MODE = "custom_fan_mode";
    public static final String CHANNEL_NAME_PRESET = "preset";
    public static final String CHANNEL_NAME_CUSTOM_PRESET = "custom_preset";
}
