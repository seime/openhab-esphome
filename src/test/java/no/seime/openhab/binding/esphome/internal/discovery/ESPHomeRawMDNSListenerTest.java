package no.seime.openhab.binding.esphome.internal.discovery;

import static org.junit.Assert.*;

import java.net.DatagramPacket;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import org.junit.Test;

public class ESPHomeRawMDNSListenerTest {

    private static DatagramPacket packet(byte[] data) {
        return new DatagramPacket(data, data.length);
    }

    /**
     * Minimal DNS-like encoding:
     * - header (12 bytes)
     * - one answer
     * - name encoded as raw labels (not compressed)
     * - type PTR (0x000c)
     * - class IN (0x0001)
     * - ttl (32 bit)
     * - rdlength + rdata (target name)
     */
    private static byte[] mdnsPtrRecord(String deviceId, int ttl) {
        byte[] name = encodeName("_esphomelib._tcp.local");
        byte[] target = encodeName(deviceId + "._esphomelib._tcp.local");

        byte[] packet = new byte[12 + name.length + 10 + target.length];

        int i = 0;

        // DNS header
        packet[i++] = 0x00;
        packet[i++] = 0x00; // id
        packet[i++] = (byte) 0x84;
        packet[i++] = 0x00; // response
        packet[i++] = 0x00;
        packet[i++] = 0x00; // qdcount
        packet[i++] = 0x00;
        packet[i++] = 0x01; // ancount
        packet[i++] = 0x00;
        packet[i++] = 0x00; // nscount
        packet[i++] = 0x00;
        packet[i++] = 0x00; // arcount

        // NAME
        System.arraycopy(name, 0, packet, i, name.length);
        i += name.length;

        // TYPE PTR
        packet[i++] = 0x00;
        packet[i++] = 0x0c;

        // CLASS IN
        packet[i++] = 0x00;
        packet[i++] = 0x01;

        // TTL
        packet[i++] = (byte) ((ttl >> 24) & 0xff);
        packet[i++] = (byte) ((ttl >> 16) & 0xff);
        packet[i++] = (byte) ((ttl >> 8) & 0xff);
        packet[i++] = (byte) (ttl & 0xff);

        // RDLENGTH
        packet[i++] = (byte) ((target.length >> 8) & 0xff);
        packet[i++] = (byte) (target.length & 0xff);

        // RDATA
        System.arraycopy(target, 0, packet, i, target.length);

        return packet;
    }

    private static byte[] encodeName(String fqdn) {
        String[] labels = fqdn.split("\\.");
        int len = 1;
        for (String l : labels) {
            len += l.length() + 1;
        }
        byte[] out = new byte[len];
        int i = 0;
        for (String l : labels) {
            out[i++] = (byte) l.length();
            byte[] b = l.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(b, 0, out, i, b.length);
            i += b.length;
        }
        out[i] = 0x00;
        return out;
    }

    private static byte[] mdnsMultiPtrRecord(String[] deviceIds, int ttl) {
        byte[] serviceName = encodeName("_esphomelib._tcp.local");

        byte[][] targets = new byte[deviceIds.length][];
        int answersLen = 0;

        for (int i = 0; i < deviceIds.length; i++) {
            targets[i] = encodeName(deviceIds[i] + "._esphomelib._tcp.local");
            answersLen += serviceName.length + 10 + targets[i].length;
        }

        byte[] packet = new byte[12 + answersLen];
        int i = 0;

        // DNS header
        packet[i++] = 0x00;
        packet[i++] = 0x00; // id
        packet[i++] = (byte) 0x84;
        packet[i++] = 0x00; // response
        packet[i++] = 0x00;
        packet[i++] = 0x00; // qdcount
        packet[i++] = 0x00;
        packet[i++] = (byte) deviceIds.length; // ancount
        packet[i++] = 0x00;
        packet[i++] = 0x00; // nscount
        packet[i++] = 0x00;
        packet[i++] = 0x00; // arcount

        for (byte[] target : targets) {
            System.arraycopy(serviceName, 0, packet, i, serviceName.length);
            i += serviceName.length;

            // TYPE PTR
            packet[i++] = 0x00;
            packet[i++] = 0x0c;
            // CLASS IN
            packet[i++] = 0x00;
            packet[i++] = 0x01;

            // TTL
            packet[i++] = (byte) ((ttl >> 24) & 0xff);
            packet[i++] = (byte) ((ttl >> 16) & 0xff);
            packet[i++] = (byte) ((ttl >> 8) & 0xff);
            packet[i++] = (byte) (ttl & 0xff);

            // RDLENGTH
            packet[i++] = (byte) ((target.length >> 8) & 0xff);
            packet[i++] = (byte) (target.length & 0xff);

            // RDATA
            System.arraycopy(target, 0, packet, i, target.length);
            i += target.length;
        }

        return packet;
    }

    @Test
    public void singleValidDeviceIsDetected() {
        DatagramPacket p = packet(mdnsPtrRecord("livingroom-node", 120));

        List<String> ids = ESPHomeRawMDNSListener.findDeviceIdsInPacket(p);

        assertEquals(1, ids.size());
        assertEquals("livingroom-node", ids.get(0));
    }

    @Test
    public void ttlZeroIsIgnored() {
        DatagramPacket p = packet(mdnsPtrRecord("kitchen-node", 0));

        List<String> ids = ESPHomeRawMDNSListener.findDeviceIdsInPacket(p);

        assertTrue(ids.isEmpty());
    }

    @Test
    public void unrelatedServiceIsIgnored() {
        byte[] garbage = "_http._tcp.local".getBytes(StandardCharsets.US_ASCII);
        DatagramPacket p = packet(garbage);

        List<String> ids = ESPHomeRawMDNSListener.findDeviceIdsInPacket(p);

        assertTrue(ids.isEmpty());
    }

    @Test
    public void duplicateAnnouncementsFromProxyAreDeduplicated() {
        byte[] a = mdnsPtrRecord("node-a", 120);
        byte[] b = mdnsPtrRecord("node-a", 120);

        byte[] merged = new byte[a.length + b.length];
        System.arraycopy(a, 0, merged, 0, a.length);
        System.arraycopy(b, 0, merged, a.length, b.length);

        DatagramPacket p = packet(merged);

        List<String> ids = ESPHomeRawMDNSListener.findDeviceIdsInPacket(p);

        assertEquals(1, ids.size());
        assertEquals("node-a", ids.get(0));
    }

    @Test
    public void multipleDifferentDevicesInSinglePacket() {
        DatagramPacket p = packet(mdnsMultiPtrRecord(new String[] { "node-a", "node-b" }, 120));

        List<String> ids = ESPHomeRawMDNSListener.findDeviceIdsInPacket(p);

        assertEquals(2, ids.size());
        assertTrue(ids.contains("node-a"));
        assertTrue(ids.contains("node-b"));
    }

    @Test(timeout = 500)
    public void truncatedPacketDoesNotLoopForever() {
        byte[] broken = new byte[] { 0x00, 0x00, (byte) 0x84, 0x00, 0x00, 0x00, 0x00, 0x01 };

        DatagramPacket p = packet(broken);

        ESPHomeRawMDNSListener.findDeviceIdsInPacket(p);
    }

    @Test
    public void malformedPacketDoesNotLoopForever() {
        // This packet has an ancount of 1, but the packet ends before the answer record is complete.
        // Specifically, it ends after the NAME, TYPE, CLASS, and TTL, but before RDLENGTH and RDATA.
        byte[] broken = new byte[] { 0x00, 0x00, (byte) 0x84, 0x00, // Header
                0x00, 0x00, 0x00, 0x01, // ancount = 1
                0x00, 0x00, 0x00, 0x00, // nscount, arcount
                0x08, '_', 'e', 's', 'p', 'h', 'o', 'm', 'e', 0x04, '_', 't', 'c', 'p', 0x05, 'l', 'o', 'c', 'a', 'l',
                0x00, // NAME: _esphome._tcp.local
                0x00, 0x0c, // TYPE PTR
                0x00, 0x01, // CLASS IN
                0x00, 0x00, 0x00, 0x78 // TTL 120
        };
        DatagramPacket p = packet(broken);
        ESPHomeRawMDNSListener.findDeviceIdsInPacket(p);
    }

    @Test
    public void dataFromWireshark() {
        assertFromData("000084000000000900000000095f7365727669636573075f646e732d7364045f756470056c6f63616c"
                + "00000c000100001194000d055f68747470045f746370c023c034000c00010000119400100d6573702d70616e6c6"
                + "5652d3032c034c04d002180010000007800160000000000500d6573702d70616e6c65652d3032c023c04d001080"
                + "0100001194000100c06f00018001000000780004c0aaaab4c00c000c000100001194000e0b5f657370686f6d656"
                + "c6962c03ac0a8000c00010000119400100d6573702d70616e6c65652d3032c0a8c0c20021800100000078000800"
                + "00000017a5c06fc0c2001080010000119400a7336170695f656e6372797074696f6e3d4e6f6973655f4e4e70736"
                + "b305f32353531395f436861436861506f6c795f5348413235360c6e6574776f726b3d7769666918626f6172643d"
                + "65737033322d73332d6465766b6974632d310e706c6174666f726d3d4553503332106d61633d663431326661636"
                + "6363636631076657273696f6e3d323032362e312e331b667269656e646c795f6e616d653d6573702d70616e6c65"
                + "652d3032", "esp-panlee-02");
        assertFromData(
                "000084000000000900000000095f7365727669636573075f646e732d7364045f756470056c6f63616c"
                        + "00000c000100001194000d055f68747470045f746370c023c034000c00010000119400100d6573702d"
                        + "70616e6c65652d3032c034c04d002180010000007800160000000000500d6573702d70616e6c65652d"
                        + "3032c023c04d0010800100001194000100c06f00018001000000780004c0aaaab4c00c000c00010000"
                        + "1194000e0b5f657370686f6d656c6962c03ac0a8000c00010000119400100d6573702d70616e6c6565"
                        + "2d3032c0a8c0c2002180010000007800080000000017a5c06fc0c2001080010000119400a733617069"
                        + "5f656e6372797074696f6e3d4e6f6973655f4e4e70736b305f32353531395f436861436861506f6c79"
                        + "5f5348413235360c6e6574776f726b3d7769666918626f6172643d65737033322d73332d6465766b69"
                        + "74632d310e706c6174666f726d3d4553503332106d61633d6634313266616366363636631076657273"
                        + "696f6e3d323032362e312e331b667269656e646c795f6e616d653d6573702d70616e6c65652d3032",
                "esp-panlee-02");
        assertFromData(
                "000084000000000900000000095f7365727669636573075f646e732d7364045f756470056c6f63616c"
                        + "00000c000100001194000d055f68747470045f746370c023c034000c00010000119400100d6573702d"
                        + "70616e6c65652d3032c034c04d002180010000007800160000000000500d6573702d70616e6c65652d"
                        + "3032c023c04d0010800100001194000100c06f00018001000000780004c0aaaab4c00c000c00010000"
                        + "1194000e0b5f657370686f6d656c6962c03ac0a8000c00010000119400100d6573702d70616e6c6565"
                        + "2d3032c0a8c0c2002180010000007800080000000017a5c06fc0c2001080010000119400a733617069"
                        + "5f656e6372797074696f6e3d4e6f6973655f4e4e70736b305f32353531395f436861436861506f6c79"
                        + "5f5348413235360c6e6574776f726b3d7769666918626f6172643d65737033322d73332d6465766b69"
                        + "74632d310e706c6174666f726d3d4553503332106d61633d6634313266616366363636631076657273"
                        + "696f6e3d323032362e312e331b667269656e646c795f6e616d653d6573702d70616e6c65652d3032",
                "esp-panlee-02");
    }

    public void assertFromData(String hexStream, String deviceId) {
        byte[] data = HexFormat.of().parseHex(hexStream);
        DatagramPacket p = new DatagramPacket(data, data.length);
        List<String> devices = ESPHomeRawMDNSListener.findDeviceIdsInPacket(p);
        assertEquals(1, devices.size());
        assertEquals(deviceId, devices.get(0));
    }
}
