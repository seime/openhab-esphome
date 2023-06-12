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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.thing.type.ChannelTypeProvider;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.GeneratedMessageV3;

import io.esphome.api.BinarySensorStateResponse;
import io.esphome.api.ClimateStateResponse;
import io.esphome.api.ConnectRequest;
import io.esphome.api.ConnectResponse;
import io.esphome.api.DeviceInfoRequest;
import io.esphome.api.DeviceInfoResponse;
import io.esphome.api.DisconnectRequest;
import io.esphome.api.DisconnectResponse;
import io.esphome.api.HelloRequest;
import io.esphome.api.HelloResponse;
import io.esphome.api.ListEntitiesBinarySensorResponse;
import io.esphome.api.ListEntitiesClimateResponse;
import io.esphome.api.ListEntitiesDoneResponse;
import io.esphome.api.ListEntitiesRequest;
import io.esphome.api.ListEntitiesSelectResponse;
import io.esphome.api.ListEntitiesSensorResponse;
import io.esphome.api.ListEntitiesSwitchResponse;
import io.esphome.api.PingRequest;
import io.esphome.api.PingResponse;
import io.esphome.api.SelectStateResponse;
import io.esphome.api.SensorStateResponse;
import io.esphome.api.SubscribeStatesRequest;
import io.esphome.api.SwitchStateResponse;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.ESPHomeConfiguration;
import no.seime.openhab.binding.esphome.internal.PacketListener;
import no.seime.openhab.binding.esphome.internal.comm.PlainTextConnection;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolException;
import no.seime.openhab.binding.esphome.internal.message.AbstractMessageHandler;
import no.seime.openhab.binding.esphome.internal.message.BinarySensorMessageHandler;
import no.seime.openhab.binding.esphome.internal.message.ClimateMessageHandler;
import no.seime.openhab.binding.esphome.internal.message.SelectMessageHandler;
import no.seime.openhab.binding.esphome.internal.message.SensorMessageHandler;
import no.seime.openhab.binding.esphome.internal.message.SwitchMessageHandler;

/**
 * The {@link ESPHomeHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Arne Seime - Initial contribution
 */
@NonNullByDefault
public class ESPHomeHandler extends BaseThingHandler implements PacketListener, ChannelTypeProvider {

    public static final int NUM_MISSED_PINGS_BEFORE_DISCONNECT = 4;
    public static final int CONNECT_TIMEOUT = 20;
    private static final long PING_INTERVAL_SECONDS = 10;
    private static int API_VERSION_MAJOR = 1;
    private static int API_VERSION_MINOR = 7;

    private final Logger logger = LoggerFactory.getLogger(ESPHomeHandler.class);
    private final Map<ChannelTypeUID, ChannelType> generatedChannelTypes = new HashMap<>();
    private @Nullable ESPHomeConfiguration config;
    private @Nullable PlainTextConnection connection;
    @Nullable
    private ScheduledFuture<?> pingWatchdog;
    private Instant lastPong = Instant.now();
    @Nullable
    private ScheduledFuture<?> reconnectFuture;
    private Map<String, AbstractMessageHandler> commandTypeToHandlerMap = new HashMap<>();
    private Map<Class<? extends GeneratedMessageV3>, AbstractMessageHandler> classToHandlerMap = new HashMap<>();
    private ConnectionState connectionState = ConnectionState.UNINITIALIZED;
    private List<Channel> dynamicChannels = new ArrayList<>();

    public ESPHomeHandler(Thing thing) {
        super(thing);

        // Register message handlers for each type of message pairs
        registerMessageHandler("Select", new SelectMessageHandler(this), ListEntitiesSelectResponse.class,
                SelectStateResponse.class);
        registerMessageHandler("Sensor", new SensorMessageHandler(this), ListEntitiesSensorResponse.class,
                SensorStateResponse.class);
        registerMessageHandler("BinarySensor", new BinarySensorMessageHandler(this),
                ListEntitiesBinarySensorResponse.class, BinarySensorStateResponse.class);
        registerMessageHandler("Switch", new SwitchMessageHandler(this), ListEntitiesSwitchResponse.class,
                SwitchStateResponse.class);
        registerMessageHandler("Climate", new ClimateMessageHandler(this), ListEntitiesClimateResponse.class,
                ClimateStateResponse.class);
    }

    private void registerMessageHandler(String select, AbstractMessageHandler messageHandler,
            Class<? extends GeneratedMessageV3> listEntitiesClass, Class<? extends GeneratedMessageV3> stateClass) {

        commandTypeToHandlerMap.put(select, messageHandler);
        classToHandlerMap.put(listEntitiesClass, messageHandler);
        classToHandlerMap.put(stateClass, messageHandler);
    }

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
            dynamicChannels.clear();
            generatedChannelTypes.clear();

            logger.info("Trying to connect to {}:{}", config.hostname, config.port);
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE,
                    String.format("Connecting to %s:%d", config.hostname, config.port));

            connection = new PlainTextConnection(this);

            connection.connect(config.hostname, config.port, CONNECT_TIMEOUT);

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

    public void sendMessage(GeneratedMessageV3 message) throws ProtocolAPIError {
        connection.send(message);
    }

    @Override
    public synchronized void handleCommand(ChannelUID channelUID, Command command) {

        if (command == RefreshType.REFRESH) {
            try {
                connection.send(SubscribeStatesRequest.getDefaultInstance());
            } catch (ProtocolAPIError e) {
                logger.error("Error sending command {} to channel {}: {}", command, channelUID, e.getMessage());
            }
            return;
        }

        Optional<Channel> optionalChannel = thing.getChannels().stream().filter(e -> e.getUID().equals(channelUID))
                .findFirst();
        optionalChannel.ifPresent(channel -> {
            try {
                String commandClass = (String) channel.getConfiguration().get(BindingConstants.COMMAND_CLASS);
                if (commandClass == null) {
                    logger.warn("No command class for channel {}", channelUID);
                    return;
                }

                AbstractMessageHandler abstractMessageHandler = commandTypeToHandlerMap.get(commandClass);
                if (abstractMessageHandler == null) {
                    logger.warn("No message handler for command class {}", commandClass);
                } else {
                    int key = ((BigDecimal) channel.getConfiguration().get(BindingConstants.COMMAND_KEY)).intValue();
                    abstractMessageHandler.handleCommand(channel, command, key);
                }

            } catch (Exception e) {
                logger.error("Error sending command {} to channel {}: {}", command, channelUID, e.getMessage(), e);
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
        } else if (message instanceof ListEntitiesDoneResponse) {
            updateThing(editThing().withChannels(dynamicChannels).build());
            logger.debug("Done updating channels");
            connection.send(SubscribeStatesRequest.getDefaultInstance());
        } else if (message instanceof PingRequest) {
            logger.debug("Responding to ping request");
            connection.send(PingResponse.getDefaultInstance());

        } else if (message instanceof PingResponse) {
            logger.debug("Received ping response");
            lastPong = Instant.now();
        } else if (message instanceof DisconnectRequest) {
            connection.send(DisconnectResponse.getDefaultInstance());
            remoteDisconnect();
        } else if (message instanceof DisconnectResponse) {
            connection.close(true);
        } else {
            // Regular messages handled by message handlers
            AbstractMessageHandler abstractMessageHandler = classToHandlerMap.get(message.getClass());
            if (abstractMessageHandler != null) {
                abstractMessageHandler.handleMessage(message);
            } else {
                logger.warn("Unhandled message of type {}. This is lack of support in the binding. Content: '{}'.",
                        message.getClass().getName(), message);
            }
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

    @Override
    public void updateState(ChannelUID channelUID, State state) {
        super.updateState(channelUID, state);
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

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Collections.singleton(CallbackChannelsTypeProvider.class);
    }

    public void addChannelType(ChannelType channelType) {
        generatedChannelTypes.put(channelType.getUID(), channelType);
    }

    @Override
    public Collection<ChannelType> getChannelTypes(@Nullable final Locale locale) {
        return generatedChannelTypes.values();
    }

    @Override
    public @Nullable ChannelType getChannelType(final ChannelTypeUID channelTypeUID, @Nullable final Locale locale) {
        return generatedChannelTypes.get(channelTypeUID);
    }

    public void addChannel(Channel channel) {
        dynamicChannels.add(channel);
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
