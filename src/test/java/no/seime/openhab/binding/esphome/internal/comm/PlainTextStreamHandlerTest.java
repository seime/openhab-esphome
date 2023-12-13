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
import java.net.InetSocketAddress;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.google.protobuf.GeneratedMessageV3;

import io.esphome.api.HelloRequest;
import io.esphome.api.HelloResponse;
import no.seime.openhab.binding.esphome.internal.ESPHomeEmulator;
import no.seime.openhab.binding.esphome.internal.PacketListener;

public class PlainTextStreamHandlerTest {

    boolean responseReceived = false;

    private ConnectionSelector connectionSelector = new ConnectionSelector();

    private InetSocketAddress serverAddress = new InetSocketAddress("localhost", new Random().nextInt(10000) + 10000);

    public PlainTextStreamHandlerTest() throws IOException {
    }

    @Test
    void testParsePacket() throws IOException {

        final ESPHomeEmulator espHomeDevice;

        espHomeDevice = new ESPHomeEmulator(serverAddress);
        espHomeDevice.setPacketListener(new PacketListener() {
            @Override
            public void onPacket(GeneratedMessageV3 message) throws IOException {
                System.out.println("Received packet: " + message);

                // Respond with hello response
                HelloResponse helloResponse = HelloResponse.newBuilder().setApiVersionMajor(1).setApiVersionMinor(7)
                        .setServerInfo("ESPHome 1.7.3").build();
                espHomeDevice.sendPacket(helloResponse);
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

        try {

            // Send a packet to a server and receive a response. Verify that both messages are parsed correctly.

            espHomeDevice.start();
            await().until(() -> espHomeDevice.isReady());

            connectionSelector.start();
            ESPHomeConnection clientConnection = new ESPHomeConnection(connectionSelector,
                    new PlainTextStreamHandler(new PacketListener() {
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
                    }), "localhost");

            clientConnection.connect(serverAddress);

            // Send hello request from client to server
            HelloRequest helloRequest = HelloRequest.newBuilder().setClientInfo("openHAB").setApiVersionMajor(1)
                    .setApiVersionMinor(7).build();
            clientConnection.send(helloRequest);

            await().until(() -> responseReceived);
        } catch (Exception e) {
            fail(e);

        } finally {
            espHomeDevice.stop();
            connectionSelector.stop();
        }
    }
}
