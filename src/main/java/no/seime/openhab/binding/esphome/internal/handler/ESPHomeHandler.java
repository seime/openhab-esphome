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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.GeneratedMessageV3;

import io.esphome.api.BinarySensorStateResponse;
import io.esphome.api.ConnectRequest;
import io.esphome.api.ConnectResponse;
import io.esphome.api.DeviceInfoRequest;
import io.esphome.api.DeviceInfoResponse;
import io.esphome.api.DisconnectRequest;
import io.esphome.api.DisconnectResponse;
import io.esphome.api.HelloRequest;
import io.esphome.api.HelloResponse;
import io.esphome.api.ListEntitiesBinarySensorResponse;
import io.esphome.api.ListEntitiesDoneResponse;
import io.esphome.api.ListEntitiesRequest;
import io.esphome.api.ListEntitiesSensorResponse;
import io.esphome.api.ListEntitiesSwitchResponse;
import io.esphome.api.PingRequest;
import io.esphome.api.PingResponse;
import io.esphome.api.SensorStateResponse;
import io.esphome.api.SubscribeStatesRequest;
import io.esphome.api.SwitchCommandRequest;
import io.esphome.api.SwitchStateResponse;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.ESPHomeConfiguration;
import no.seime.openhab.binding.esphome.internal.PacketListener;
import no.seime.openhab.binding.esphome.internal.comm.PlainTextConnection;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolException;

/**
 * The {@link ESPHomeHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Arne Seime - Initial contribution
 */
@NonNullByDefault
public class ESPHomeHandler extends BaseThingHandler implements PacketListener {

    private static final long PING_INTERVAL_SECONDS = 10;
    public static final int NUM_MISSED_PINGS_BEFORE_DISCONNECT = 4;
    public static final int CONNECT_TIMEOUT = 20;
    public static final String CHANNEL_CONFIGURATION_KEY = "key";
    private static int API_VERSION_MAJOR = 1;
    private static int API_VERSION_MINOR = 7;

    private final Logger logger = LoggerFactory.getLogger(ESPHomeHandler.class);

    private @Nullable ESPHomeConfiguration config;
    private @Nullable PlainTextConnection connection;

    private List<Channel> dynamicChannels = new ArrayList<>();
    @Nullable
    private ScheduledFuture<?> pingWatchdog;
    private Instant lastPong = Instant.now();
    @Nullable
    private ScheduledFuture<?> reconnectFuture;

    public ESPHomeHandler(Thing thing) {
        super(thing);
    }

    private ConnectionState connectionState = ConnectionState.UNINITIALIZED;

    @Override
    public void initialize() {

        config = getConfigAs(ESPHomeConfiguration.class);

        if (config.hostname != null && !config.hostname.isEmpty()) {
            scheduler.submit(() -> connect());
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No hostname configured");
        }
    }

    private void connect() {
        try {
            logger.info("Trying to connect to {}:{}", config.hostname, config.port);
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE,
                    String.format("Connecting to %s:%d", config.hostname, config.port));

            connection = new PlainTextConnection(this);
            connection.connect(config.hostname, config.port, CONNECT_TIMEOUT);

            dynamicChannels.clear();

            HelloRequest helloRequest = HelloRequest.newBuilder().setClientInfo("openHAB")
                    .setApiVersionMajor(API_VERSION_MAJOR).setApiVersionMinor(API_VERSION_MINOR).build();
            connectionState = ConnectionState.HELLO_SENT;
            connection.send(helloRequest);

        } catch (ProtocolException e) {
            logger.warn("Error initial connection", e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            reconnectFuture = scheduler.schedule(() -> connect(), CONNECT_TIMEOUT * 2, TimeUnit.SECONDS);
        }
    }

    @Override
    public void dispose() {
        if (reconnectFuture != null) {
            reconnectFuture.cancel(true);
        }
        if (connection != null) {
            if (pingWatchdog != null) {
                pingWatchdog.cancel(true);
            }

            if (connectionState == ConnectionState.CONNECTED) {
                try {
                    connection.send(DisconnectRequest.getDefaultInstance());
                } catch (ProtocolAPIError e) {
                    // Quietly ignore
                }
            } else {
                connection.close(true);
            }
        }
        super.dispose();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {

        if (command == RefreshType.REFRESH) {
            try {
                connection.send(SubscribeStatesRequest.getDefaultInstance());
            } catch (ProtocolAPIError e) {
                logger.error("Error sending command {} to channel {}: {}", command, channelUID, e.getMessage());
            }
            return;
        }

        Optional<Channel> channel = thing.getChannels().stream().filter(e -> e.getUID().equals(channelUID)).findFirst();
        channel.ifPresent(channel1 -> {
            try {
                BigDecimal key = (BigDecimal) channel1.getConfiguration().get(CHANNEL_CONFIGURATION_KEY);

                switch (channel1.getChannelTypeUID().getId()) {
                    case BindingConstants.CHANNEL_TYPE_SWITCH:
                        connection.send(SwitchCommandRequest.newBuilder().setKey(key.intValue())
                                .setState(command == OnOffType.ON).build());
                        break;
                    default:
                        logger.warn("Unknown channel type {}", channelUID.getId());
                }
            } catch (Exception e) {
                logger.error("Error sending command {} to channel {}: {}", command, channelUID, e.getMessage());
            }
        });
    }

    @Override
    public void onPacket(@NonNull GeneratedMessageV3 message) throws ProtocolAPIError {
        switch (connectionState) {
            case UNINITIALIZED:
                logger.warn("Received packet while uninitialized.");
                break;
            case HELLO_SENT:
                handleHelloResponse(message);
                break;
            case LOGIN_SENT:
                handleLoginResponse(message);
                break;
            case CONNECTED:
                handleConnected(message);
                break;
        }
    }

    @Override
    public void onEndOfStream() {
        updateStatus(ThingStatus.OFFLINE);
        connection.close(true);
    }

    @Override
    public void onParseError() {
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Parse error");
        connection.close(true);
    }

    private void handleConnected(GeneratedMessageV3 message) throws ProtocolAPIError {
        logger.info("Received message {}", message);
        if (message instanceof DeviceInfoResponse rsp) {
            Map<String, String> props = new HashMap<>();
            props.put("esphome_version", rsp.getEsphomeVersion());
            props.put("mac_address", rsp.getMacAddress());
            props.put("model", rsp.getModel());
            props.put("name", rsp.getName());
            props.put("manufacturer", rsp.getManufacturer());
            props.put("compilation_time", rsp.getCompilationTime());
            updateThing(editThing().withProperties(props).build());

        } else if (message instanceof PingRequest) {
            logger.debug("Responding to ping request");
            connection.send(PingResponse.getDefaultInstance());

        } else if (message instanceof PingResponse) {
            logger.debug("Received ping response");
            lastPong = Instant.now();

            // Sensor (numeric)
        } else if (message instanceof ListEntitiesSensorResponse rsp) {
            logger.debug("Received list sensors response");

            Configuration configuration = new Configuration();
            configuration.put(CHANNEL_CONFIGURATION_KEY, rsp.getKey());
            configuration.put("unit", rsp.getUnitOfMeasurement());

            dynamicChannels.add(ChannelBuilder.create(new ChannelUID(thing.getUID(), rsp.getObjectId()))
                    .withLabel(rsp.getName()).withKind(ChannelKind.STATE)
                    .withType(new ChannelTypeUID(BindingConstants.BINDING_ID, channelType(rsp.getObjectId())))
                    .withConfiguration(configuration).build());

        } else if (message instanceof SensorStateResponse rsp) {
            logger.debug("Received sensor state response");
            findChannelByKey(rsp.getKey()).ifPresent(channel -> updateState(channel.getUID(),
                    toNumberState(channel.getConfiguration(), rsp.getState(), rsp.getMissingState())));

            // Binary sensor
        } else if (message instanceof ListEntitiesBinarySensorResponse rsp) {
            logger.debug("Received list binary sensor response");

            Configuration configuration = new Configuration();
            configuration.put(CHANNEL_CONFIGURATION_KEY, rsp.getKey());

            dynamicChannels.add(ChannelBuilder.create(new ChannelUID(thing.getUID(), rsp.getObjectId()))
                    .withLabel(rsp.getName()).withKind(ChannelKind.STATE)
                    .withType(new ChannelTypeUID(BindingConstants.BINDING_ID, BindingConstants.CHANNEL_TYPE_CONTACT))
                    .withConfiguration(configuration).build());

        } else if (message instanceof BinarySensorStateResponse rsp) {
            logger.debug("Received sensor state response");
            findChannelByKey(rsp.getKey()).ifPresent(
                    channel -> updateState(channel.getUID(), toContactState(rsp.getState(), rsp.getMissingState())));

            // Switches
        } else if (message instanceof ListEntitiesSwitchResponse rsp) {
            logger.debug("Received list switch response");

            Configuration configuration = new Configuration();
            configuration.put(CHANNEL_CONFIGURATION_KEY, rsp.getKey());

            dynamicChannels.add(ChannelBuilder.create(new ChannelUID(thing.getUID(), rsp.getObjectId()))
                    .withLabel(rsp.getName()).withKind(ChannelKind.STATE)
                    .withType(new ChannelTypeUID(BindingConstants.BINDING_ID, BindingConstants.CHANNEL_TYPE_SWITCH))
                    .withConfiguration(configuration).build());
        } else if (message instanceof SwitchStateResponse rsp) {
            logger.debug("Received switch state response");
            findChannelByKey(rsp.getKey())
                    .ifPresent(channel -> updateState(channel.getUID(), rsp.getState() ? OnOffType.ON : OnOffType.OFF));

        } else if (message instanceof ListEntitiesDoneResponse) {
            updateThing(editThing().withChannels(dynamicChannels).build());
            logger.debug("Done updating channels");
            connection.send(SubscribeStatesRequest.getDefaultInstance());

        } else if (message instanceof DisconnectRequest) {
            connection.send(DisconnectResponse.getDefaultInstance());
            remoteDisconnect();

        } else if (message instanceof DisconnectResponse) {
            connection.close(true);
        } else {
            logger.debug("Unhandled message of type {}. This is lack of support in the binding. Content: '{}'.",
                    message.getClass().getName(), message);
        }
    }

    private void remoteDisconnect() {
        connection.close(true);
        connectionState = ConnectionState.UNINITIALIZED;
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE, "ESPHome requested disconnect");
        if (pingWatchdog != null) {
            pingWatchdog.cancel(true);
        }
    }

    private String channelType(String objectId) {
        switch (objectId) {
            case "humidity":
                return BindingConstants.CHANNEL_TYPE_HUMIDITY;
            case "temperature":
                return BindingConstants.CHANNEL_TYPE_TEMPERATURE;
            default:
                logger.warn(
                        "Not implemented channel type for {}. Defaulting to 'Number'. Create a PR or create an issue at https://github.com/seime/openhab-esphome/issues. Stack-trace to aid where to add support. Also remember to add appropriate channel-type in src/main/resources/thing/channel-types.xml: {}",
                        objectId, Thread.currentThread().getStackTrace());
                return BindingConstants.CHANNEL_TYPE_NUMBER;
        }
    }

    private State toNumberState(Configuration configuration, float state, boolean missingState) {
        if (missingState) {
            return UnDefType.UNDEF;
        } else {
            String unit = (String) configuration.get("unit");
            if (unit != null) {
                return new QuantityType<>(state + unit);
            }
            return new DecimalType(state);
        }
    }

    private State toContactState(boolean state, boolean missingState) {
        if (missingState) {
            return UnDefType.UNDEF;
        } else {
            return state ? OpenClosedType.OPEN : OpenClosedType.CLOSED;
        }
    }

    private Optional<Channel> findChannelByKey(int key) {
        return thing.getChannels().stream()
                .filter(e -> ((BigDecimal) e.getConfiguration().get(CHANNEL_CONFIGURATION_KEY)).intValue() == key)
                .findFirst();
    }

    private void handleLoginResponse(GeneratedMessageV3 message) throws ProtocolAPIError {
        if (message instanceof ConnectResponse connectResponse) {
            logger.debug("Received login response {}", connectResponse);

            if (connectResponse.getInvalidPassword()) {
                logger.error("Invalid password");
                connection.close(true);
                connectionState = ConnectionState.UNINITIALIZED;
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Invalid password");
                return;
            }
            connectionState = ConnectionState.CONNECTED;
            updateStatus(ThingStatus.ONLINE);

            // Reset last pong
            lastPong = Instant.now();

            pingWatchdog = scheduler.scheduleAtFixedRate(() -> {

                if (lastPong.plusSeconds(NUM_MISSED_PINGS_BEFORE_DISCONNECT * PING_INTERVAL_SECONDS)
                        .isBefore(Instant.now())) {
                    logger.warn(
                            "Ping responses lacking Waited {} times {} seconds, total of {}. Assuming connection lost and disconnecting",
                            NUM_MISSED_PINGS_BEFORE_DISCONNECT, PING_INTERVAL_SECONDS,
                            NUM_MISSED_PINGS_BEFORE_DISCONNECT * PING_INTERVAL_SECONDS);
                    pingWatchdog.cancel(false);
                    connection.close(true);
                    connectionState = ConnectionState.UNINITIALIZED;
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            String.format("ESPHome did not respond to ping requests. %d pings sent with %d s delay",
                                    NUM_MISSED_PINGS_BEFORE_DISCONNECT, PING_INTERVAL_SECONDS));
                    reconnectFuture = scheduler.schedule(() -> connect(), 30, TimeUnit.SECONDS);

                } else {

                    try {
                        logger.debug("Sending ping");
                        connection.send(PingRequest.getDefaultInstance());
                    } catch (ProtocolAPIError e) {
                        logger.warn("Error sending ping request");
                    }
                }
            }, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);

            connection.send(DeviceInfoRequest.getDefaultInstance());
            connection.send(ListEntitiesRequest.getDefaultInstance());

        }
    }

    private void handleHelloResponse(GeneratedMessageV3 message) throws ProtocolAPIError {
        if (message instanceof HelloResponse helloResponse) {
            logger.debug("Received hello response {}", helloResponse);
            logger.info("Server {} running {} on protocol version {}.{}", helloResponse.getName(),
                    helloResponse.getServerInfo(), helloResponse.getApiVersionMajor(),
                    helloResponse.getApiVersionMinor());
            connectionState = ConnectionState.LOGIN_SENT;

            if (config.password != null) {
                connection.send(ConnectRequest.newBuilder().setPassword(config.password).build());
            } else {
                connection.send(ConnectRequest.getDefaultInstance());

            }

        }

        // Check if
    }

    private enum ConnectionState {
        // Initial state, no connection
        UNINITIALIZED,
        // TCP connected to ESPHome, first handshake sent
        HELLO_SENT,

        // First handshake received, login sent (with password)
        LOGIN_SENT,

        // Connection established
        CONNECTED

    }
}
