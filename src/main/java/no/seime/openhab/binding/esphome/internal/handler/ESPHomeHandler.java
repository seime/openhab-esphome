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

import org.apache.commons.lang3.StringUtils;
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
import no.seime.openhab.binding.esphome.internal.LogLevel;
import no.seime.openhab.binding.esphome.internal.bluetooth.ESPHomeBluetoothProxyHandler;
import no.seime.openhab.binding.esphome.internal.comm.*;
import no.seime.openhab.binding.esphome.internal.message.*;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.ESPHomeEventSubscriber;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.EventSubscription;

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
    private static final String DEVICE_LOGGER_NAME = "ESPHOMEDEVICE";

    private final Logger logger = LoggerFactory.getLogger(ESPHomeHandler.class);
    private final Logger deviceLogger = LoggerFactory.getLogger(DEVICE_LOGGER_NAME);

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
    private boolean bluetoothProxyStarted = false;
    private boolean bluetoothProxyRequested = false;

    @Nullable
    private String logPrefix = null;
    private final ESPHomeEventSubscriber eventSubscriber;
    @Nullable
    private ESPHomeBluetoothProxyHandler espHomeBluetoothProxyHandler;

    public ESPHomeHandler(Thing thing, ConnectionSelector connectionSelector,
            ESPChannelTypeProvider dynamicChannelTypeProvider, ESPHomeEventSubscriber eventSubscriber) {
        super(thing);
        this.connectionSelector = connectionSelector;
        this.dynamicChannelTypeProvider = dynamicChannelTypeProvider;
        logPrefix = thing.getUID().getId();
        this.eventSubscriber = eventSubscriber;

        // Register message handlers for each type of message pairs
        registerMessageHandler("Select", new SelectMessageHandler(this), ListEntitiesSelectResponse.class,
                SelectStateResponse.class);
        registerMessageHandler("Sensor", new SensorMessageHandler(this), ListEntitiesSensorResponse.class,
                SensorStateResponse.class);
        registerMessageHandler("BinarySensor", new BinarySensorMessageHandler(this),
                ListEntitiesBinarySensorResponse.class, BinarySensorStateResponse.class);
        registerMessageHandler("TextSensor", new TextSensorMessageHandler(this), ListEntitiesTextSensorResponse.class,
                TextSensorStateResponse.class);
        registerMessageHandler("Text", new TextMessageHandler(this), ListEntitiesTextResponse.class,
                TextStateResponse.class);
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
        registerMessageHandler("Fan", new FanMessageHandler(this), ListEntitiesFanResponse.class,
                FanStateResponse.class);
        registerMessageHandler("Date", new DateMessageHandler(this), ListEntitiesDateResponse.class,
                DateStateResponse.class);
        registerMessageHandler("DateTime", new DateTimeMessageHandler(this), ListEntitiesDateTimeResponse.class,
                DateTimeStateResponse.class);
        registerMessageHandler("Time", new TimeMessageHandler(this), ListEntitiesTimeResponse.class,
                TimeStateResponse.class);
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

        // Use configured logprefix instead of default thingId
        if (config.logPrefix != null) {
            logPrefix = config.logPrefix;
        }

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

            logger.info("[{}] Trying to connect to {}:{}", logPrefix, config.hostname, config.port);
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE,
                    String.format("Connecting to %s:%d", config.hostname, config.port));

            boolean useEncryption = config.encryptionKey != null;
            if (!useEncryption) {
                logger.warn(
                        "[{}] Using unencrypted connection. This is deprecated and will be removed in the future. Please use encryption.",
                        logPrefix);
            }

            frameHelper = useEncryption
                    ? new EncryptedFrameHelper(connectionSelector, this, config.encryptionKey, config.server, logPrefix)
                    : new PlainTextFrameHelper(connectionSelector, this, logPrefix);

            frameHelper.connect(new InetSocketAddress(config.hostname, config.port));

        } catch (ProtocolException e) {
            logger.warn("[{}] Error initial connection", logPrefix, e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            if (!disposed) { // Don't reconnect if we've been disposed
                scheduleReconnect(CONNECT_TIMEOUT * 2);
            }
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        eventSubscriber.removeEventSubscriptions(this);
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
            logger.warn("[{}] Not connected, ignoring command {}", logPrefix, command);
            return;
        }

        if (command == RefreshType.REFRESH) {
            try {
                frameHelper.send(SubscribeStatesRequest.getDefaultInstance());
            } catch (ProtocolAPIError e) {
                logger.error("[{}] Error sending command {} to channel {}: {}", logPrefix, command, channelUID,
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
                    logger.warn("[{}] No command class for channel {}", logPrefix, channelUID);
                    return;
                }

                AbstractMessageHandler<? extends GeneratedMessageV3, ? extends GeneratedMessageV3> abstractMessageHandler = commandTypeToHandlerMap
                        .get(commandClass);
                if (abstractMessageHandler == null) {
                    logger.warn("[{}] No message handler for command class {}", logPrefix, commandClass);
                } else {
                    int key = ((BigDecimal) channel.getConfiguration().get(BindingConstants.COMMAND_KEY)).intValue();
                    abstractMessageHandler.handleCommand(channel, command, key);
                }

            } catch (Exception e) {
                logger.error("[{}] Error sending command {} to channel {}: {}", logPrefix, command, channelUID,
                        e.getMessage(), e);
            }
        });
    }

    @Override
    public void onConnect() throws ProtocolAPIError {
        logger.debug("[{}] Connection established", logPrefix);
        HelloRequest helloRequest = HelloRequest.newBuilder().setClientInfo("openHAB")
                .setApiVersionMajor(API_VERSION_MAJOR).setApiVersionMinor(API_VERSION_MINOR).build();
        connectionState = ConnectionState.HELLO_SENT;
        frameHelper.send(helloRequest);
    }

    @Override
    public void onPacket(@NonNull GeneratedMessageV3 message) throws ProtocolAPIError {
        switch (connectionState) {
            case UNINITIALIZED -> logger.warn("[{}] Received packet {} while uninitialized.", logPrefix,
                    message.getClass().getSimpleName());
            case HELLO_SENT -> handleHelloResponse(message);
            case LOGIN_SENT -> handleLoginResponse(message);
            case CONNECTED -> handleConnected(message);
        }
    }

    @Override
    public void onEndOfStream() {
        eventSubscriber.removeEventSubscriptions(this);
        if (!disposed) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "ESPHome device abruptly closed connection.");
            setUndefToAllChannels();
            frameHelper.close();
            cancelPingWatchdog();
            connectionState = ConnectionState.UNINITIALIZED;
            scheduleReconnect(CONNECT_TIMEOUT * 2);
        }
    }

    @Override
    public void onParseError(CommunicationError error) {
        eventSubscriber.removeEventSubscriptions(this);
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
        eventSubscriber.removeEventSubscriptions(this);
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
        if (logger.isDebugEnabled()) {
            // ToString method costs a bit
            logger.debug("[{}] Received message type {} with content '{}'", logPrefix,
                    message.getClass().getSimpleName(), StringUtils.trimToEmpty(message.toString()));
        }
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
            logger.debug("[{}] Device interrogation complete, done updating thing channels", logPrefix);
            interrogated = true;
            frameHelper.send(SubscribeStatesRequest.getDefaultInstance());
        } else if (message instanceof PingRequest) {
            logger.debug("[{}] Responding to ping request", logPrefix);
            frameHelper.send(PingResponse.getDefaultInstance());
        } else if (message instanceof PingResponse) {
            logger.debug("[{}] Received ping response", logPrefix);
            lastPong = Instant.now();
        } else if (message instanceof DisconnectRequest) {
            frameHelper.send(DisconnectResponse.getDefaultInstance());
            remoteDisconnect();
        } else if (message instanceof DisconnectResponse) {
            frameHelper.close();
        } else if (message instanceof SubscribeLogsResponse subscribeLogsResponse) {
            deviceLogger.info("[{}] {}", logPrefix, subscribeLogsResponse.getMessage());
        } else if (message instanceof SubscribeHomeAssistantStateResponse subscribeHomeAssistantStateResponse) {
            initializeStateSubscription(subscribeHomeAssistantStateResponse);
        } else if (message instanceof GetTimeRequest) {
            logger.debug("[{}] Received time sync request", logPrefix);
            GetTimeResponse getTimeResponse = GetTimeResponse.newBuilder()
                    .setEpochSeconds((int) (System.currentTimeMillis() / 1000)).build();
            frameHelper.send(getTimeResponse);
        } else if (message instanceof BluetoothLEAdvertisementResponse rsp) {
            if (espHomeBluetoothProxyHandler != null) {
                espHomeBluetoothProxyHandler.handleAdvertisement(rsp);
            }
        } else {
            // Regular messages handled by message handlers
            AbstractMessageHandler<? extends GeneratedMessageV3, ? extends GeneratedMessageV3> abstractMessageHandler = classToHandlerMap
                    .get(message.getClass());
            if (abstractMessageHandler != null) {
                abstractMessageHandler.handleMessage(message);
            } else {
                logger.warn("[{}] Unhandled message of type {}. This is lack of support in the binding. Content: '{}'.",
                        logPrefix, message.getClass().getName(), message);
            }
        }
    }

    private void initializeStateSubscription(SubscribeHomeAssistantStateResponse rsp) {
        // Setup event subscriber
        logger.debug("[{}] Start subscribe to OH events entity: {}, attribute: {}", logPrefix, rsp.getEntityId(),
                rsp.getAttribute());

        EventSubscription subscription = eventSubscriber.createEventSubscription(rsp.getEntityId(), rsp.getAttribute(),
                this);
        eventSubscriber.addEventSubscription(this, subscription);

        String state = eventSubscriber.getInitialState(logPrefix, subscription);

        HomeAssistantStateResponse ohStateUpdate = HomeAssistantStateResponse.newBuilder()
                .setEntityId(subscription.getEntityId()).setAttribute(subscription.getAttribute()).setState(state)
                .build();
        try {
            frameHelper.send(ohStateUpdate);
        } catch (ProtocolAPIError e) {
            logger.warn("[{}] Error sending OpenHAB state update to ESPHome", logPrefix, e);
        }
    }

    public void handleOpenHABEvent(EventSubscription subscription, String esphomeState) {
        HomeAssistantStateResponse ohStateUpdate = HomeAssistantStateResponse.newBuilder()
                .setEntityId(subscription.getEntityId()).setAttribute(subscription.getAttribute())
                .setState(esphomeState).build();
        try {
            frameHelper.send(ohStateUpdate);
        } catch (ProtocolAPIError e) {
            logger.warn("[{}] Error sending OpenHAB state update to ESPHome", logPrefix, e);
        }
    }

    private void handleLoginResponse(GeneratedMessageV3 message) throws ProtocolAPIError {
        if (message instanceof ConnectResponse connectResponse) {
            logger.debug("[{}] Received login response {}", logPrefix, connectResponse);

            if (connectResponse.getInvalidPassword()) {
                logger.error("[{}] Invalid password", logPrefix);
                frameHelper.close();
                connectionState = ConnectionState.UNINITIALIZED;
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Invalid password");
                return;
            }
            connectionState = ConnectionState.CONNECTED;

            if (config.deviceLogLevel != LogLevel.NONE) {
                logger.info("[{}] Starting to stream logs to logger " + DEVICE_LOGGER_NAME, logPrefix);

                frameHelper.send(SubscribeLogsRequest.newBuilder()
                        .setLevel(io.esphome.api.LogLevel.valueOf("LOG_LEVEL_" + config.deviceLogLevel.name()))
                        .build());
            }

            updateStatus(ThingStatus.ONLINE);
            logger.debug("[{}] Device login complete, starting device interrogation", logPrefix);

            // Reset last pong
            lastPong = Instant.now();

            pingWatchdogFuture = scheduler.scheduleAtFixedRate(() -> {

                if (lastPong.plusSeconds((long) config.maxPingTimeouts * config.pingInterval).isBefore(Instant.now())) {
                    logger.warn(
                            "[{}] Ping responses lacking. Waited {} times {} seconds, total of {}. Assuming connection lost and disconnecting",
                            logPrefix, config.maxPingTimeouts, config.pingInterval,
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
                        logger.debug("[{}] Sending ping", logPrefix);
                        frameHelper.send(PingRequest.getDefaultInstance());
                    } catch (ProtocolAPIError e) {
                        logger.warn("[{}] Error sending ping request", logPrefix, e);
                    }
                }
            }, config.pingInterval, config.pingInterval, TimeUnit.SECONDS);

            // Start interrogation
            frameHelper.send(DeviceInfoRequest.getDefaultInstance());
            frameHelper.send(ListEntitiesRequest.getDefaultInstance());
            frameHelper.send(SubscribeHomeAssistantStatesRequest.getDefaultInstance());
        }
    }

    @Override
    public void updateState(ChannelUID channelUID, State state) {
        super.updateState(channelUID, state);
    }

    private void handleHelloResponse(GeneratedMessageV3 message) throws ProtocolAPIError {
        if (message instanceof HelloResponse helloResponse) {
            logger.debug("[{}] Received hello response {}", logPrefix, helloResponse);
            logger.info("[{}] Connected. Device '{}' running '{}' on protocol version '{}.{}'", logPrefix,
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

    public boolean isDisposed() {
        return disposed;
    }

    public void listenForBLEAdvertisements(ESPHomeBluetoothProxyHandler espHomeBluetoothProxyHandler) {
        this.espHomeBluetoothProxyHandler = espHomeBluetoothProxyHandler;
        if (config.enableBluetoothProxy && !bluetoothProxyStarted && connectionState == ConnectionState.CONNECTED) {
            try {
                frameHelper.send(SubscribeBluetoothLEAdvertisementsRequest.getDefaultInstance());
                bluetoothProxyStarted = true;
            } catch (ProtocolAPIError e) {
                logger.error("[{}] Error starting BLE proxy", logPrefix, e);
            }
        } else {
            bluetoothProxyRequested = true;
        }
    }

    public void stopListeningForBLEAdvertisements() {
        try {
            frameHelper.send(UnsubscribeBluetoothLEAdvertisementsRequest.getDefaultInstance());
            bluetoothProxyStarted = false;
        } catch (ProtocolAPIError e) {
            logger.error("[{}] Error starting BLE proxy", logPrefix, e);
        }

        espHomeBluetoothProxyHandler = null;
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
