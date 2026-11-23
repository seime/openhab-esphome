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

import static no.seime.openhab.binding.esphome.internal.BindingConstants.CONFIG_ENCRYPTION_KEY;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingUID;

import com.google.protobuf.GeneratedMessage;

import io.esphome.api.AuthenticationResponse;
import io.esphome.api.DeviceInfoResponse;
import io.esphome.api.DisconnectRequest;
import io.esphome.api.DisconnectResponse;
import io.esphome.api.NoiseEncryptionSetKeyResponse;
import io.esphome.api.PingRequest;
import io.esphome.api.PingResponse;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;

/**
 * Rules for adopting an unprovisioned Noise device: it is reached with a public, all-zero bootstrap key, and a new
 * random key is saved to the Thing before it is sent to the device.
 *
 * @author Arne Seime - Initial contribution
 */
@NonNullByDefault
final class EncryptionKeyProvisioning {

    // Reserved bootstrap PSK accepted only by unprovisioned Noise devices.
    static final String PROVISIONING_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    // The bootstrap PSK is public. Do not trust such a session with actions, state updates,
    // proxy data, or other normal traffic until it reconnects using the saved private key.
    private static final Set<Class<? extends GeneratedMessage>> ALLOWED_DURING_PROVISIONING = Set.of(
            AuthenticationResponse.class, DeviceInfoResponse.class, NoiseEncryptionSetKeyResponse.class,
            PingRequest.class, PingResponse.class, DisconnectRequest.class, DisconnectResponse.class);

    private EncryptionKeyProvisioning() {
    }

    static boolean isAllowedDuringProvisioning(GeneratedMessage message) {
        return ALLOWED_DURING_PROVISIONING.contains(message.getClass());
    }

    /**
     * @throws ProtocolAPIError if the key is not valid base64 or is the public bootstrap key
     */
    static void rejectProvisioningKey(String encryptionKey) throws ProtocolAPIError {
        try {
            if (Arrays.equals(Base64.getDecoder().decode(encryptionKey), new byte[32])) {
                throw new ProtocolAPIError(
                        "The reserved bootstrap encryption key cannot be configured; remove it to adopt the device automatically");
            }
        } catch (IllegalArgumentException e) {
            throw new ProtocolAPIError("Invalid base64 encryption key", e);
        }
    }

    /**
     * Generates a new key for the device and saves it to the Thing configuration. The key is persisted before it is
     * sent to the device so the credential survives a restart or a lost response.
     *
     * @param updateConfiguration saves a configuration to the Thing
     * @return the saved key
     * @throws AdoptionFailedException if the device cannot be adopted or the key could not be saved
     */
    static String generateAndSaveKey(DeviceInfoResponse response, @Nullable ManagedThingProvider provider,
            ThingUID thingUID, Consumer<Configuration> updateConfiguration) throws AdoptionFailedException {
        if (!response.getApiEncryptionProvisionable() || !response.getApiEncryptionSupported() || provider == null
                || provider.get(thingUID) == null) {
            throw new AdoptionFailedException(
                    "Device cannot be automatically adopted. Configure its existing encryptionKey manually");
        }
        // Recheck the stored configuration to avoid replacing a key entered while connecting.
        Thing storedThing = provider.get(thingUID);
        if (storedThing == null
                || StringUtils.isNotBlank((String) storedThing.getConfiguration().get(CONFIG_ENCRYPTION_KEY))) {
            throw new AdoptionFailedException(
                    "Thing configuration changed during adoption; reconnect with the configured key");
        }
        String encodedKey = generateKey();
        Configuration configuration = new Configuration(storedThing.getConfiguration().getProperties());
        configuration.put(CONFIG_ENCRYPTION_KEY, encodedKey);
        try {
            updateConfiguration.accept(configuration);
            Thing savedThing = provider.get(thingUID);
            if (savedThing == null || !encodedKey.equals(savedThing.getConfiguration().get(CONFIG_ENCRYPTION_KEY))) {
                throw new IllegalStateException("Encryption key was not saved");
            }
        } catch (RuntimeException e) {
            throw new AdoptionFailedException(
                    "Could not save encryption key to Thing configuration; device was not changed");
        }
        return encodedKey;
    }

    static String generateKey() {
        byte[] key = new byte[32];
        SecureRandom random = new SecureRandom();
        String encodedKey;
        do {
            random.nextBytes(key);
            encodedKey = Base64.getEncoder().encodeToString(key);
        } while (PROVISIONING_KEY.equals(encodedKey));
        return encodedKey;
    }

    static class AdoptionFailedException extends Exception {
        private static final long serialVersionUID = 1L;

        AdoptionFailedException(String message) {
            super(message);
        }
    }
}
