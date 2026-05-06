package no.seime.openhab.binding.esphome.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;

@NonNullByDefault
public interface ESPHomeVersionListener {
    void onVersionUpdate(String version);
}
