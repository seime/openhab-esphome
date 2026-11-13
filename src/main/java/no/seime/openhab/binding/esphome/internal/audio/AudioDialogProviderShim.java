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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.audio.AudioSink;
import org.openhab.core.audio.AudioSource;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * Starts an openHAB dialog for the device's wake word.
 * <p>
 * Calls openHAB 5.1's {@code AudioDialogProvider} by reflection. Remove when this binding targets 5.1 or newer.
 *
 * @author Dan Cunningham - Initial contribution
 */
@NonNullByDefault
public final class AudioDialogProviderShim {

    private static final String PROVIDER_CLASS = "org.openhab.core.audio.AudioDialogProvider";

    private AudioDialogProviderShim() {
    }

    /**
     * @return the wake word trigger
     * @throws IllegalStateException if the dialog can't start yet
     * @throws UnsupportedOperationException if openHAB has no compatible dialog provider
     */
    public static Runnable startDialog(BundleContext bundleContext, AudioSink sink, AudioSource source,
            Runnable onAbort) {
        ServiceReference<?> reference = bundleContext.getServiceReference(PROVIDER_CLASS);
        if (reference == null) {
            if (!providerExists()) {
                throw new UnsupportedOperationException("requires openHAB 5.1 or newer");
            }
            throw new IllegalStateException("voice service not running");
        }
        try {
            Bundle providerBundle = reference.getBundle();
            Object provider = bundleContext.getService(reference);
            if (providerBundle == null || provider == null) {
                throw new IllegalStateException("voice service not running");
            }
            // must come from the service's bundle
            Method startDialog = providerBundle.loadClass(PROVIDER_CLASS).getMethod("startDialog", AudioSink.class,
                    AudioSource.class, String.class, String.class, Runnable.class);
            Runnable trigger = (Runnable) startDialog.invoke(provider, sink, source, null, null, onAbort);
            if (trigger == null) {
                throw new IllegalStateException("openHAB didn't start the dialog");
            }
            return trigger;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            Throwable error = cause != null ? cause : e;
            throw new IllegalStateException(Objects.toString(error.getMessage(), error.toString()), e);
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new UnsupportedOperationException("incompatible dialog provider: " + e, e);
        } finally {
            bundleContext.ungetService(reference);
        }
    }

    private static boolean providerExists() {
        try {
            Class.forName(PROVIDER_CLASS, false, AudioSink.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
