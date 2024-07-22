package no.seime.openhab.binding.esphome.internal.bluetooth;

import java.util.HexFormat;

import org.openhab.binding.bluetooth.BluetoothAddress;

public class BluetoothAddressUtil {
    public static BluetoothAddress createAddress(long address) {
        String hexDigits = HexFormat.of().toHexDigits(address);

        StringBuilder addressBuilder = new StringBuilder();

        // Skip first 2 bytes as addresses are 48 bits and not 64 bits
        for (int i = 4; i < hexDigits.length(); i += 2) {
            addressBuilder.append(hexDigits.substring(i, i + 2));
            if (i < hexDigits.length() - 2) {
                addressBuilder.append(":");
            }
        }

        return new BluetoothAddress(addressBuilder.toString().toUpperCase());
    }

    public static long convertAddressToLong(BluetoothAddress address) {
        String[] parts = address.toString().split(":");
        long result = 0;
        for (int i = 0; i < parts.length; i++) {
            result = result << 8;
            result |= Integer.parseInt(parts[i], 16);
        }
        return result;
    }
}
