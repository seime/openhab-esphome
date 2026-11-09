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
package no.seime.openhab.binding.esphome.internal.audio;

/**
 * Media-player feature flags advertised by ESPHome in {@code ListEntitiesMediaPlayerResponse.feature_flags}.
 * The currently available flags come from ESPHome's
 * {@code esphome/components/media_player/media_player.h}.
 *
 * @author Cody Cutrer - Initial contribution
 */
public enum MediaPlayerFeature {
    PAUSE(1 << 0),
    VOLUME_SET(1 << 2),
    VOLUME_MUTE(1 << 3),
    TURN_ON(1 << 7),
    TURN_OFF(1 << 8),
    VOLUME_STEP(1 << 10),
    STOP(1 << 12),
    CLEAR_PLAYLIST(1 << 13),
    PLAY(1 << 14),
    REPEAT_SET(1 << 18);

    public final int flag;

    MediaPlayerFeature(int flag) {
        this.flag = flag;
    }

    public int getFlag() {
        return flag;
    }
}
