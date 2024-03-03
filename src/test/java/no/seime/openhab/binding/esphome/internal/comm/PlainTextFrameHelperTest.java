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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.google.protobuf.GeneratedMessageV3;

import io.esphome.api.HelloRequest;
import io.esphome.api.HelloResponse;
import no.seime.openhab.binding.esphome.internal.CommunicationListener;
import no.seime.openhab.binding.esphome.internal.ESPHomeEmulator;

public class PlainTextFrameHelperTest {

    boolean responseReceived = false;

    private final ConnectionSelector connectionSelector = new ConnectionSelector();

    private final InetSocketAddress serverAddress = new InetSocketAddress("localhost",
            new Random().nextInt(10000) + 10000);

    public PlainTextFrameHelperTest() throws IOException {
    }

    @Test
    void testParsePacket() throws IOException {

        final ESPHomeEmulator espHomeDevice = getEspHomeEmulator();

        try {

            // Send a packet to a server and receive a response. Verify that both messages are parsed correctly.

            espHomeDevice.start();
            await().until(() -> espHomeDevice.isReady());

            connectionSelector.start();

            PlainTextFrameHelper serverConnection = new PlainTextFrameHelper(connectionSelector, null, "openhab");
            serverConnection.setPacketListener(new CommunicationListener() {
                @Override
                public void onPacket(GeneratedMessageV3 message) {
                    System.out.println("Received packet: " + message);
                    assertInstanceOf(HelloResponse.class, message);
                    responseReceived = true;
                }

                @Override
                public void onEndOfStream() {
                    fail();
                }

                @Override
                public void onParseError(CommunicationError error) {
                    fail(error.toString());
                }

                @Override
                public void onConnect() throws ProtocolAPIError {
                    HelloRequest helloRequest = HelloRequest.newBuilder().setClientInfo("openHAB").setApiVersionMajor(1)
                            .setApiVersionMinor(7).build();
                    serverConnection.send(helloRequest);
                }
            });

            serverConnection.connect(serverAddress);

            // Send hello request from client to server

            await().until(() -> responseReceived);
        } catch (Exception e) {
            fail(e);

        } finally {
            espHomeDevice.stop();
            connectionSelector.stop();
        }
    }

    private ESPHomeEmulator getEspHomeEmulator() {
        final ESPHomeEmulator espHomeDevice;

        espHomeDevice = new ESPHomeEmulator(serverAddress, new PlainTextFrameHelper(null, null, "emulator"));
        espHomeDevice.setPacketListener(new CommunicationListener() {
            @Override
            public void onPacket(GeneratedMessageV3 message) throws IOException, ProtocolAPIError {
                System.out.println("Received packet: " + message);
                assertInstanceOf(HelloRequest.class, message);

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
            public void onParseError(CommunicationError error) {
                fail(error.toString());
            }

            @Override
            public void onConnect() {
            }
        });
        return espHomeDevice;
    }
}
