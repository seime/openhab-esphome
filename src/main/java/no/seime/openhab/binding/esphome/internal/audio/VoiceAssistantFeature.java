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
 * Voice assistant feature flags advertised by ESPHome in {@code DeviceInfoResponse.voice_assistant_feature_flags}.
 * The currently available flags come from ESPHome's
 * {@code esphome/components/voice_assistant/voice_assistant.h}.
 *
 * @author Cody Cutrer - Initial contribution
 */
public enum VoiceAssistantFeature {
    /**
     * The device exposes the voice assistant component itself.
     */
    VOICE_ASSISTANT(1 << 0),
    /**
     * The device has a local speaker attached to the voice assistant.
     */
    SPEAKER(1 << 1),
    /**
     * The device supports microphone audio streaming over the native API.
     */
    API_AUDIO(1 << 2),
    /**
     * The device exposes voice-assistant timer support.
     */
    TIMERS(1 << 3),
    /**
     * The device supports voice assistant announcements via a media player.
     */
    ANNOUNCE(1 << 4),
    /**
     * The device supports starting a follow-up conversation from a media player.
     */
    START_CONVERSATION(1 << 5);

    private final int flag;

    VoiceAssistantFeature(int flag) {
        this.flag = flag;
    }

    public int getFlag() {
        return flag;
    }
}
