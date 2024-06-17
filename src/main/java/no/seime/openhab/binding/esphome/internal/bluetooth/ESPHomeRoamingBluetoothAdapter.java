package no.seime.openhab.binding.esphome.internal.bluetooth;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bluetooth.BluetoothAdapter;
import org.openhab.core.thing.ThingUID;

@NonNullByDefault
public interface ESPHomeRoamingBluetoothAdapter extends BluetoothAdapter {

    void addBluetoothAdapter(BluetoothAdapter adapter);

    void removeBluetoothAdapter(BluetoothAdapter adapter);

    boolean isDiscoveryEnabled();

    boolean isRoamingMember(ThingUID adapterUID);
}
