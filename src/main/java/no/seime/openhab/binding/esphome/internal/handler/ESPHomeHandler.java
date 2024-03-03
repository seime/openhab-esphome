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
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.GeneratedMessageV3;

import io.esphome.api.*;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.CommunicationListener;
import no.seime.openhab.binding.esphome.internal.ESPHomeConfiguration;
import no.seime.openhab.binding.esphome.internal.comm.*;
import no.seime.openhab.binding.esphome.internal.message.*;

/**
 * The {@link ESPHomeHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Arne Seime - Initial contribution
 */
@NonNullByDefault
public class ESPHomeHandler extends BaseThingHandler implements CommunicationListener {

    public static final int CONNECT_TIMEOUT = 20;
    private static final int API_VERSION_MAJOR = 1;
    private static final int API_VERSION_MINOR = 9;

    private final Logger logger = LoggerFactory.getLogger(ESPHomeHandler.class);
    private final ConnectionSelector connectionSelector;
    private final ESPChannelTypeProvider dynamicChannelTypeProvider;
    private @Nullable ESPHomeConfiguration config;

    private @Nullable AbstractFrameHelper frameHelper;
    @Nullable
    private ScheduledFuture<?> pingWatchdogFuture;
    private Instant lastPong = Instant.now();
    @Nullable
    private ScheduledFuture<?> reconnectFuture;
    private final Map<String, AbstractMessageHandler<? extends GeneratedMessageV3, ? extends GeneratedMessageV3>> commandTypeToHandlerMap = new HashMap<>();
    private final Map<Class<? extends GeneratedMessageV3>, AbstractMessageHandler<? extends GeneratedMessageV3, ? extends GeneratedMessageV3>> classToHandlerMap = new HashMap<>();
    private ConnectionState connectionState = ConnectionState.UNINITIALIZED;

    private final List<Channel> dynamicChannels = new ArrayList<>();

    private boolean disposed = false;
    private boolean interrogated;

    public ESPHomeHandler(Thing thing, ConnectionSelector connectionSelector,
            ESPChannelTypeProvider dynamicChannelTypeProvider) {
        super(thing);
        this.connectionSelector = connectionSelector;
        this.dynamicChannelTypeProvider = dynamicChannelTypeProvider;

        // Register message handlers for each type of message pairs
        registerMessageHandler("Select", new SelectMessageHandler(this), ListEntitiesSelectResponse.class,
                SelectStateResponse.class);
        registerMessageHandler("Sensor", new SensorMessageHandler(this), ListEntitiesSensorResponse.class,
                SensorStateResponse.class);
        registerMessageHandler("BinarySensor", new BinarySensorMessageHandler(this),
                ListEntitiesBinarySensorResponse.class, BinarySensorStateResponse.class);
        registerMessageHandler("TextSensor", new TextSensorMessageHandler(this), ListEntitiesTextSensorResponse.class,
                TextSensorStateResponse.class);
        registerMessageHandler("Switch", new SwitchMessageHandler(this), ListEntitiesSwitchResponse.class,
                SwitchStateResponse.class);
        registerMessageHandler("Climate", new ClimateMessageHandler(this), ListEntitiesClimateResponse.class,
                ClimateStateResponse.class);
        registerMessageHandler("Number", new NumberMessageHandler(this), ListEntitiesNumberResponse.class,
                NumberStateResponse.class);
        registerMessageHandler("Light", new LightMessageHandler(this), ListEntitiesLightResponse.class,
                LightStateResponse.class);
        registerMessageHandler("Button", new ButtonMessageHandler(this), ListEntitiesButtonResponse.class,
                ButtonCommandRequest.class);
        registerMessageHandler("Cover", new CoverMessageHandler(this), ListEntitiesCoverResponse.class,
                CoverStateResponse.class);
    }

    private void registerMessageHandler(String select,
            AbstractMessageHandler<? extends GeneratedMessageV3, ? extends GeneratedMessageV3> messageHandler,
            Class<? extends GeneratedMessageV3> listEntitiesClass, Class<? extends GeneratedMessageV3> stateClass) {

        commandTypeToHandlerMap.put(select, messageHandler);
        classToHandlerMap.put(listEntitiesClass, messageHandler);
        classToHandlerMap.put(stateClass, messageHandler);
    }

    @Override
    public void initialize() {
        logger.debug("[{}] Initializing ESPHome handler", thing.getUID());
        config = getConfigAs(ESPHomeConfiguration.class);

        if (config.hostname != null && !config.hostname.isEmpty()) {
            scheduleReconnect(0);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No hostname configured");
        }
    }

    @Override
    public void handleRemoval() {
        dynamicChannelTypeProvider.removeChannelTypesForThing(thing.getUID());
        super.handleRemoval();
    }

    private void connect() {
        try {
            dynamicChannels.clear();

            logger.info("[{}] Trying to connect to {}:{}", config.hostname, config.hostname, config.port);
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE,
                    String.format("Connecting to %s:%d", config.hostname, config.port));

            frameHelper = config.encryptionKey != null
                    ? new EncryptedFrameHelper(connectionSelector, this, config.encryptionKey, config.server,
                            config.hostname)
                    : new PlainTextFrameHelper(connectionSelector, this, config.hostname);

            frameHelper.connect(new InetSocketAddress(config.hostname, config.port));

        } catch (ProtocolException e) {
            logger.warn("[{}] Error initial connection", config.hostname, e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            if (!disposed) { // Don't reconnect if we've been disposed
                scheduleReconnect(CONNECT_TIMEOUT * 2);
            }
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        setUndefToAllChannels();
        cancelReconnectFuture();
        if (frameHelper != null) {
            cancelPingWatchdog();

            if (connectionState == ConnectionState.CONNECTED) {
                try {
                    frameHelper.send(DisconnectRequest.getDefaultInstance());
                } catch (ProtocolAPIError e) {
                    // Quietly ignore
                }
            } else {
                frameHelper.close();
            }
        }
        super.dispose();
    }

    public void sendMessage(GeneratedMessageV3 message) throws ProtocolAPIError {
        frameHelper.send(message);
    }

    private void setUndefToAllChannels() {
        // Update all channels to UNDEF to avoid stale values
        getThing().getChannels().forEach(channel -> updateState(channel.getUID(), UnDefType.UNDEF));
    }

    @Override
    public synchronized void handleCommand(ChannelUID channelUID, Command command) {

        if (connectionState != ConnectionState.CONNECTED) {
            logger.warn("[{}] Not connected, ignoring command {}", config.hostname, command);
            return;
        }

        if (command == RefreshType.REFRESH) {
            try {
                frameHelper.send(SubscribeStatesRequest.getDefaultInstance());
            } catch (ProtocolAPIError e) {
                logger.error("[{}] Error sending command {} to channel {}: {}", config.hostname, command, channelUID,
                        e.getMessage());
            }
            return;
        }

        Optional<Channel> optionalChannel = thing.getChannels().stream().filter(e -> e.getUID().equals(channelUID))
                .findFirst();
        optionalChannel.ifPresent(channel -> {
            try {
                String commandClass = (String) channel.getConfiguration().get(BindingConstants.COMMAND_CLASS);
                if (commandClass == null) {
                    logger.warn("[{}] No command class for channel {}", config.hostname, channelUID);
                    return;
                }

                AbstractMessageHandler<? extends GeneratedMessageV3, ? extends GeneratedMessageV3> abstractMessageHandler = commandTypeToHandlerMap
                        .get(commandClass);
                if (abstractMessageHandler == null) {
                    logger.warn("[{}] No message handler for command class {}", config.hostname, commandClass);
                } else {
                    int key = ((BigDecimal) channel.getConfiguration().get(BindingConstants.COMMAND_KEY)).intValue();
                    abstractMessageHandler.handleCommand(channel, command, key);
                }

            } catch (Exception e) {
                logger.error("[{}] Error sending command {} to channel {}: {}", config.hostname, command, channelUID,
                        e.getMessage(), e);
            }
        });
    }

    @Override
    public void onConnect() throws ProtocolAPIError {
        logger.debug("[{}] Connection established", config.hostname);
        HelloRequest helloRequest = HelloRequest.newBuilder().setClientInfo("openHAB")
                .setApiVersionMajor(API_VERSION_MAJOR).setApiVersionMinor(API_VERSION_MINOR).build();
        connectionState = ConnectionState.HELLO_SENT;
        frameHelper.send(helloRequest);
    }

    @Override
    public void onPacket(@NonNull GeneratedMessageV3 message) throws ProtocolAPIError {
        switch (connectionState) {
            case UNINITIALIZED -> logger.warn("[{}] Received packet while uninitialized.", config.hostname);
            case HELLO_SENT -> handleHelloResponse(message);
            case LOGIN_SENT -> handleLoginResponse(message);
            case CONNECTED -> handleConnected(message);
        }
    }

    @Override
    public void onEndOfStream() {
        if (!disposed) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "ESPHome device closed connection.");
            setUndefToAllChannels();
            frameHelper.close();
            cancelPingWatchdog();
            connectionState = ConnectionState.UNINITIALIZED;
            scheduleReconnect(CONNECT_TIMEOUT * 2);
        }
    }

    @Override
    public void onParseError(CommunicationError error) {
        if (!disposed) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, error.toString());
            setUndefToAllChannels();
            cancelPingWatchdog();
            frameHelper.close();
            connectionState = ConnectionState.UNINITIALIZED;
            scheduleReconnect(CONNECT_TIMEOUT * 2);
        }
    }

    private void remoteDisconnect() {
        if (!disposed) {
            int reconnectDelaySeconds = CONNECT_TIMEOUT;
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE, String.format(
                    "ESPHome device requested disconnect. Will reconnect in %d seconds", reconnectDelaySeconds));

            frameHelper.close();
            setUndefToAllChannels();
            connectionState = ConnectionState.UNINITIALIZED;
            cancelPingWatchdog();
            scheduleReconnect(reconnectDelaySeconds);
        }
    }

    private void handleConnected(GeneratedMessageV3 message) throws ProtocolAPIError {
        logger.debug("[{}] Received message {}", config.hostname, message);
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
            logger.debug("[{}] Device interrogation complete, done updating thing channels", config.hostname);
            interrogated = true;
            frameHelper.send(SubscribeStatesRequest.getDefaultInstance());
        } else if (message instanceof PingRequest) {
            logger.debug("[{}] Responding to ping request", config.hostname);
            frameHelper.send(PingResponse.getDefaultInstance());

        } else if (message instanceof PingResponse) {
            logger.debug("[{}] Received ping response", config.hostname);
            lastPong = Instant.now();
        } else if (message instanceof DisconnectRequest) {
            frameHelper.send(DisconnectResponse.getDefaultInstance());
            remoteDisconnect();
        } else if (message instanceof DisconnectResponse) {
            frameHelper.close();
        } else {
            // Regular messages handled by message handlers
            AbstractMessageHandler<? extends GeneratedMessageV3, ? extends GeneratedMessageV3> abstractMessageHandler = classToHandlerMap
                    .get(message.getClass());
            if (abstractMessageHandler != null) {
                abstractMessageHandler.handleMessage(message);
            } else {
                logger.warn("[{}] Unhandled message of type {}. This is lack of support in the binding. Content: '{}'.",
                        config.hostname, message.getClass().getName(), message);
            }
        }
    }

    private void handleLoginResponse(GeneratedMessageV3 message) throws ProtocolAPIError {
        if (message instanceof ConnectResponse connectResponse) {
            logger.debug("[{}] Received login response {}", config.hostname, connectResponse);

            if (connectResponse.getInvalidPassword()) {
                logger.error("[{}] Invalid password", config.hostname);
                frameHelper.close();
                connectionState = ConnectionState.UNINITIALIZED;
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Invalid password");
                return;
            }
            connectionState = ConnectionState.CONNECTED;
            updateStatus(ThingStatus.ONLINE);
            logger.debug("[{}] Device login complete, starting device interrogation", config.hostname);

            // Reset last pong
            lastPong = Instant.now();

            pingWatchdogFuture = scheduler.scheduleAtFixedRate(() -> {

                if (lastPong.plusSeconds((long) config.maxPingTimeouts * config.pingInterval).isBefore(Instant.now())) {
                    logger.warn(
                            "[{}] Ping responses lacking Waited {} times {} seconds, total of {}. Assuming connection lost and disconnecting",
                            config.hostname, config.maxPingTimeouts, config.pingInterval,
                            config.maxPingTimeouts * config.pingInterval);
                    pingWatchdogFuture.cancel(false);
                    frameHelper.close();
                    connectionState = ConnectionState.UNINITIALIZED;
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            String.format("ESPHome did not respond to ping requests. %d pings sent with %d s delay",
                                    config.maxPingTimeouts, config.pingInterval));
                    scheduleReconnect(10);

                } else {

                    try {
                        logger.debug("[{}] Sending ping", config.hostname);
                        frameHelper.send(PingRequest.getDefaultInstance());
                    } catch (ProtocolAPIError e) {
                        logger.warn("[{}] Error sending ping request", config.hostname, e);
                    }
                }
            }, config.pingInterval, config.pingInterval, TimeUnit.SECONDS);

            frameHelper.send(DeviceInfoRequest.getDefaultInstance());
            frameHelper.send(ListEntitiesRequest.getDefaultInstance());

        }
    }

    @Override
    public void updateState(ChannelUID channelUID, State state) {
        super.updateState(channelUID, state);
    }

    private void handleHelloResponse(GeneratedMessageV3 message) throws ProtocolAPIError {
        if (message instanceof HelloResponse helloResponse) {
            logger.debug("[{}] Received hello response {}", config.hostname, helloResponse);
            logger.info("[{}] Connected. Device '{}' running '{}' on protocol version '{}.{}'", config.hostname,
                    helloResponse.getName(), helloResponse.getServerInfo(), helloResponse.getApiVersionMajor(),
                    helloResponse.getApiVersionMinor());
            connectionState = ConnectionState.LOGIN_SENT;

            if (config.password != null && !config.password.isEmpty()) {
                frameHelper.send(ConnectRequest.newBuilder().setPassword(config.password).build());
            } else {
                frameHelper.send(ConnectRequest.getDefaultInstance());

            }

        }

        // Check if
    }

    public void addChannelType(ChannelType channelType) {
        dynamicChannelTypeProvider.putChannelType(channelType);
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

    private void cancelPingWatchdog() {
        if (pingWatchdogFuture != null) {
            pingWatchdogFuture.cancel(true);
            pingWatchdogFuture = null;
        }
    }

    private void cancelReconnectFuture() {
        if (reconnectFuture != null) {
            reconnectFuture.cancel(true);
            reconnectFuture = null;
        }
    }

    private void scheduleReconnect(int delaySeconds) {
        cancelReconnectFuture();
        reconnectFuture = scheduler.schedule(this::connect, delaySeconds, TimeUnit.SECONDS);
    }

    public boolean isInterrogated() {
        return interrogated;
    }

    public List<Channel> getDynamicChannels() {
        return dynamicChannels;
    }
}
