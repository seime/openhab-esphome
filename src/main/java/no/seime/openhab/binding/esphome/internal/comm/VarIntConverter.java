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
package no.seime.openhab.binding.esphome.internal.comm;

/**
 * Helper utility for converting integers to and from varints
 *
 * @author Arne Seime - Initial contribution
 */
public class VarIntConverter {

    /**
     * Convert an integer to a varint byte array
     */
    public static byte[] intToBytes(int value) {
        if (value <= 0x7F) {
            return new byte[] { (byte) value };
        }

        byte[] ret = new byte[10];
        int index = 0;

        while (value != 0) {
            byte temp = (byte) (value & 0x7F);
            value >>= 7;
            if (value != 0) {
                temp |= (byte) 0x80;
            }
            ret[index] = temp;
            index++;
        }

        return trimArray(ret, index);
    }

    /**
     * Convert a varint byte array to an integer
     */
    public static Integer bytesToInt(byte[] value) {
        int result = 0;
        int bitpos = 0;

        for (byte val : value) {
            result |= (val & 0x7F) << bitpos;
            if ((val & 0x80) == 0) {
                return result;
            }
            bitpos += 7;
        }

        return null;
    }

    private static byte[] trimArray(byte[] array, int length) {
        byte[] trimmedArray = new byte[length];
        System.arraycopy(array, 0, trimmedArray, 0, length);
        return trimmedArray;
    }
}
