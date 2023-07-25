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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import org.junit.jupiter.api.Test;

import com.google.protobuf.GeneratedMessageV3;

import io.esphome.api.HelloRequest;
import io.esphome.api.HelloResponse;
import no.seime.openhab.binding.esphome.internal.internal.PacketListener;
import no.seime.openhab.binding.esphome.internal.internal.comm.PlainTextConnection;
import no.seime.openhab.binding.esphome.internal.internal.comm.PlainTextPacketStreamReader;

public class PlainTextPacketStreamReaderTest {

    boolean responseReceived = false;

    @Test
    void testParsePacket() throws IOException {

        // Send a packet to a server and receive a response. Verify that both messages are parsed correctly.

        PipedOutputStream clientOutputstream = new PipedOutputStream();
        PipedInputStream serverInputStream = new PipedInputStream(clientOutputstream);

        PipedOutputStream serverOutputstream = new PipedOutputStream();
        PipedInputStream clientInputStream = new PipedInputStream(serverOutputstream);

        PlainTextPacketStreamReader serverStreamReader = new PlainTextPacketStreamReader(new PacketListener() {
            @Override
            public void onPacket(GeneratedMessageV3 message) throws IOException {
                System.out.println("Received packet: " + message);

                // Respond with hello response
                HelloResponse helloResponse = HelloResponse.newBuilder().setApiVersionMajor(1).setApiVersionMinor(7)
                        .setServerInfo("ESPHome 1.7.3").build();
                byte[] helloResponsePacket = PlainTextConnection.encodeFrame(helloResponse);
                serverOutputstream.write(helloResponsePacket);
            }

            @Override
            public void onEndOfStream() {
                fail();
            }

            @Override
            public void onParseError() {
                fail();
            }
        });
        serverStreamReader.parseStream(serverInputStream);

        PlainTextPacketStreamReader clientStreamReader = new PlainTextPacketStreamReader(new PacketListener() {
            @Override
            public void onPacket(GeneratedMessageV3 message) {
                System.out.println("Received packet: " + message);
                assertTrue(message instanceof HelloResponse);
                responseReceived = true;
            }

            @Override
            public void onEndOfStream() {
                fail();
            }

            @Override
            public void onParseError() {
                fail();
            }
        });
        clientStreamReader.parseStream(clientInputStream);

        // Send hello request from client to server
        HelloRequest helloRequest = HelloRequest.newBuilder().setClientInfo("openHAB").setApiVersionMajor(1)
                .setApiVersionMinor(7).build();
        byte[] helloRequestPacket = PlainTextConnection.encodeFrame(helloRequest);
        clientOutputstream.write(helloRequestPacket);

        await().until(() -> responseReceived);
    }
}
