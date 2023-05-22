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

import java.io.IOException;

import org.eclipse.jdt.annotation.NonNullByDefault;

import com.google.protobuf.GeneratedMessageV3;

import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;

@NonNullByDefault
public interface PacketListener {

    void onPacket(GeneratedMessageV3 message) throws ProtocolAPIError, IOException;

    void onEndOfStream();

    void onParseError();
}
