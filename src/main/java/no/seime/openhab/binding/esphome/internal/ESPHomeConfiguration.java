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
package no.seime.openhab.binding.esphome.internal;

import org.eclipse.jdt.annotation.Nullable;

/**
 * The {@link ESPHomeConfiguration} class contains fields mapping thing configuration parameters.
 *
 * @author Arne Seime - Initial contribution
 */

public class ESPHomeConfiguration {

    public String hostname;
    @Nullable
    public String password;

    public int port = 6053;

    public int pingInterval = 10;

    public int maxPingTimeouts = 4;

    @Nullable
    public String encryptionKey;
    @Nullable
    public String server;

    @Nullable
    public String logPrefix;

    public LogLevel deviceLogLevel = LogLevel.NONE;

    public boolean enableBluetoothProxy = false;
}
