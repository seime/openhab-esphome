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

import com.google.protobuf.GeneratedMessage;
import com.jano7.executor.KeySequentialExecutor;

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

    private static final int API_VERSION_MAJOR = 1;
    private static final int API_VERSION_MINOR = 9;
    private static final String DEVICE_LOGGER_NAME = "ESPHOMEDEVICE";

    private final Logger logger = LoggerFactory.getLogger(ESPHomeHandler.class);
    private final Logger deviceLogger = LoggerFactory.getLogger(DEVICE_LOGGER_NAME);

    private final ConnectionSelector connectionSelector;
    private final ESPChannelTypeProvider dynamicChannelTypeProvider;
    private final Map<String, AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage>> commandTypeToHandlerMap = new HashMap<>();
    private final Map<Class<? extends GeneratedMessage>, AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage>> classToHandlerMap = new HashMap<>();
    private final List<Channel> dynamicChannels = new ArrayList<>();
    private final ESPHomeEventSubscriber eventSubscriber;
    private final MonitoredScheduledThreadPoolExecutor executorService;
    private final KeySequentialExecutor packetProcessor;
    @Nullable
    private final String defaultEncryptionKey;
    private @Nullable ESPHomeConfiguration config;
    private @Nullable EncryptedFrameHelper frameHelper;
    @Nullable
    private ScheduledFuture<?> pingWatchdogFuture;
    private Instant lastPong = Instant.now();
    @Nullable
    private ScheduledFuture<?> connectFuture;
    private ConnectionState connectionState = ConnectionState.UNINITIALIZED;
    private boolean disposed = false;
    private boolean interrogated;
    private boolean bluetoothProxyStarted = false;
    @Nullable
    private String logPrefix;
    @Nullable
    private ESPHomeBluetoothProxyHandler espHomeBluetoothProxyHandler;

    public ESPHomeHandler(Thing thing, ConnectionSelector connectionSelector,
            ESPChannelTypeProvider dynamicChannelTypeProvider, ESPHomeEventSubscriber eventSubscriber,
            MonitoredScheduledThreadPoolExecutor executorService, KeySequentialExecutor packetProcessor,
            @Nullable String defaultEncryptionKey) {
        super(thing);
        this.connectionSelector = connectionSelector;
        this.dynamicChannelTypeProvider = dynamicChannelTypeProvider;
        logPrefix = thing.getUID().getId();
        this.eventSubscriber = eventSubscriber;
        this.executorService = executorService;
        this.packetProcessor = packetProcessor;
        this.defaultEncryptionKey = defaultEncryptionKey;

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
        registerMessageHandler("Lock", new LockMessageHandler(this), ListEntitiesLockResponse.class,
                LockStateResponse.class);
    }

    private void registerMessageHandler(String select,
            AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage> messageHandler,
            Class<? extends GeneratedMessage> listEntitiesClass, Class<? extends GeneratedMessage> stateClass) {

        commandTypeToHandlerMap.put(select, messageHandler);
        classToHandlerMap.put(listEntitiesClass, messageHandler);
        classToHandlerMap.put(stateClass, messageHandler);
    }

    @Override
    public void initialize() {
        disposed = false;
        logger.debug("[{}] Initializing ESPHome handler", thing.getUID());
        config = getConfigAs(ESPHomeConfiguration.class);

        // Use configured logprefix instead of default thingId
        if (config.logPrefix != null) {
            logPrefix = config.logPrefix;
        }

        if (config.hostname != null && !config.hostname.isEmpty()) {
            scheduleConnect(0);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No hostname configured");
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        eventSubscriber.removeEventSubscriptions(this);
        setUndefToAllChannels();
        cancelConnectFuture();
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
        connectionState = ConnectionState.UNINITIALIZED;

        super.dispose();
    }

    @Override
    public void handleRemoval() {
        dynamicChannelTypeProvider.removeChannelTypesForThing(thing.getUID());
        super.handleRemoval();
    }

    private void connect() {
        try {
            dynamicChannels.clear();

            String hostname = config.hostname;
            int port = config.port;

            logger.info("[{}] Trying to connect to {}:{}", logPrefix, hostname, port);
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE,
                    String.format("Connecting to %s:%d", hostname, port));

            // Default to using the default encryption key from the binding if not set in device configuration
            String encryptionKey = config.encryptionKey;
            if (encryptionKey == null || encryptionKey.isEmpty()) {
                if (defaultEncryptionKey != null) {
                    encryptionKey = defaultEncryptionKey;
                    logger.info("[{}] Using binding default encryption key", logPrefix);
                }
            }

            frameHelper = new EncryptedFrameHelper(connectionSelector, this, encryptionKey, config.deviceId, logPrefix,
                    packetProcessor);

            frameHelper.connect(hostname, port);

        } catch (ProtocolException e) {
            logger.warn("[{}] Error initial connection", logPrefix, e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            if (!disposed) { // Don't reconnect if we've been disposed
                scheduleConnect(config.reconnectInterval);
            }
        }
    }

    public void sendMessage(GeneratedMessage message) throws ProtocolAPIError {
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

                AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage> abstractMessageHandler = commandTypeToHandlerMap
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
    public void onPacket(@NonNull GeneratedMessage message) {
        try {
            switch (connectionState) {
                case UNINITIALIZED -> logger.debug(
                        "[{}] Received packet {} while uninitialized, this can happen when the socket is closed while unprocessed packets exists. Ignoring",
                        logPrefix, message.getClass().getSimpleName());
                case HELLO_SENT -> handleHelloResponse(message);
                case LOGIN_SENT -> handleLoginResponse(message);
                case CONNECTED -> handleConnected(message);
            }
        } catch (ProtocolAPIError e) {
            logger.warn("[{}] Error parsing packet", logPrefix, e);
            onParseError(CommunicationError.PACKET_ERROR);
        }
    }

    @Override
    public void onEndOfStream(String message) {
        eventSubscriber.removeEventSubscriptions(this);
        if (!disposed) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "ESPHome device abruptly closed connection: " + message);
            setUndefToAllChannels();
            frameHelper.close();
            cancelPingWatchdog();
            connectionState = ConnectionState.UNINITIALIZED;
            scheduleConnect(config.reconnectInterval);
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
            scheduleConnect(config.reconnectInterval);
        }
    }

    private void remoteDisconnect() {
        eventSubscriber.removeEventSubscriptions(this);
        if (!disposed) {
            int reconnectDelaySeconds = config.reconnectInterval;
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE, String.format(
                    "ESPHome device requested disconnect. Will reconnect in %d seconds", reconnectDelaySeconds));

            frameHelper.close();
            setUndefToAllChannels();
            connectionState = ConnectionState.UNINITIALIZED;
            cancelPingWatchdog();
            scheduleConnect(reconnectDelaySeconds);
        }
    }

    private void handleConnected(GeneratedMessage message) throws ProtocolAPIError {
        if (logger.isDebugEnabled()) {
            // ToString method costs a bit
            logger.debug("[{}] Received message type {} with content '{}'", logPrefix,
                    message.getClass().getSimpleName(), StringUtils.trimToEmpty(message.toString()));
        }
        if (disposed) {
            return;
        }

        if (message instanceof DeviceInfoResponse rsp) {
            Map<String, String> props = new HashMap<>();
            props.put(Thing.PROPERTY_FIRMWARE_VERSION, rsp.getEsphomeVersion());
            props.put(Thing.PROPERTY_MAC_ADDRESS, rsp.getMacAddress());
            props.put(Thing.PROPERTY_MODEL_ID, rsp.getModel());
            props.put("name", rsp.getName());
            props.put(Thing.PROPERTY_VENDOR, rsp.getManufacturer());
            props.put("compilationTime", rsp.getCompilationTime());
            if (!rsp.getProjectName().isEmpty()) {
                props.put("projectName", rsp.getProjectName());
            }
            if (!rsp.getProjectVersion().isEmpty()) {
                props.put("projectVersion", rsp.getProjectVersion());
            }
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
        } else if (message instanceof BluetoothLEAdvertisementResponse
                | message instanceof BluetoothLERawAdvertisementsResponse
                | message instanceof BluetoothDeviceConnectionResponse
                | message instanceof BluetoothGATTGetServicesResponse
                | message instanceof BluetoothGATTGetServicesDoneResponse | message instanceof BluetoothGATTReadResponse
                | message instanceof BluetoothGATTNotifyDataResponse
                | message instanceof BluetoothConnectionsFreeResponse | message instanceof BluetoothGATTErrorResponse
                | message instanceof BluetoothGATTWriteResponse | message instanceof BluetoothGATTNotifyResponse
                | message instanceof BluetoothDevicePairingResponse
                | message instanceof BluetoothDeviceUnpairingResponse
                | message instanceof BluetoothDeviceClearCacheResponse
                | message instanceof BluetoothScannerStateResponse) {
            if (espHomeBluetoothProxyHandler != null) {
                espHomeBluetoothProxyHandler.handleBluetoothMessage(message, this);
            }
        } else {
            // Regular messages handled by message handlers
            AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage> abstractMessageHandler = classToHandlerMap
                    .get(message.getClass());
            if (abstractMessageHandler != null) {
                abstractMessageHandler.handleMessage(message);
            } else {
                logger.warn("[{}] Unhandled message of type {}. This is lack of support in the binding. Content: '{}'.",
                        logPrefix, message.getClass().getName(), message);
            }
        }
    }

    public void sendBluetoothCommand(GeneratedMessage message) {
        try {
            if (connectionState == ConnectionState.CONNECTED) {
                frameHelper.send(message);
            } else {
                logger.warn("[{}] Not connected, ignoring bluetooth command {}", logPrefix, message);
            }
        } catch (ProtocolAPIError e) {
            logger.error("[{}] Error sending bluetooth command", logPrefix, e);
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

        logger.debug("[{}] Sending initial state for subscription {} with state '{}'", logPrefix, subscription, state);

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

    private void handleLoginResponse(GeneratedMessage message) throws ProtocolAPIError {
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

            pingWatchdogFuture = executorService.scheduleAtFixedRate(() -> {

                if (lastPong.plusSeconds((long) config.maxPingTimeouts * config.pingInterval).isBefore(Instant.now())) {
                    logger.warn(
                            "[{}] Ping responses lacking. Waited {} times {}s, total of {}s. Last pong received at {}. Assuming connection lost and disconnecting",
                            logPrefix, config.maxPingTimeouts, config.pingInterval,
                            config.maxPingTimeouts * config.pingInterval, lastPong);
                    pingWatchdogFuture.cancel(false);
                    frameHelper.close();
                    connectionState = ConnectionState.UNINITIALIZED;
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            String.format("ESPHome did not respond to ping requests. %d pings sent with %d s delay",
                                    config.maxPingTimeouts, config.pingInterval));
                    scheduleConnect(config.reconnectInterval);

                } else {

                    try {
                        logger.debug("[{}] Sending ping", logPrefix);
                        frameHelper.send(PingRequest.getDefaultInstance());
                    } catch (ProtocolAPIError e) {
                        logger.warn("[{}] Error sending ping request", logPrefix, e);
                    }
                }
            }, config.pingInterval, config.pingInterval, TimeUnit.SECONDS,
                    String.format("[%s] Ping watchdog", logPrefix));

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

    private void handleHelloResponse(GeneratedMessage message) throws ProtocolAPIError {
        if (message instanceof HelloResponse helloResponse) {
            logger.debug("[{}] Received hello response {}", logPrefix, helloResponse);
            logger.info(
                    "[{}] Connected, continuing with protocol handshake. Device '{}' running '{}' on protocol version '{}.{}'",
                    logPrefix, helloResponse.getName(), helloResponse.getServerInfo(),
                    helloResponse.getApiVersionMajor(), helloResponse.getApiVersionMinor());
            connectionState = ConnectionState.LOGIN_SENT;

            frameHelper.send(ConnectRequest.getDefaultInstance());
        }
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
            } catch (Exception e) {
                logger.error("[{}] Error starting BLE proxy", logPrefix, e);
            }
        }
    }

    public void stopListeningForBLEAdvertisements() {

        if (connectionState == ConnectionState.CONNECTED) {
            try {
                frameHelper.send(UnsubscribeBluetoothLEAdvertisementsRequest.getDefaultInstance());
            } catch (Exception e) {
                logger.warn("[{}] Error stopping BLE proxy", logPrefix, e);
            }
        }

        bluetoothProxyStarted = false;
        espHomeBluetoothProxyHandler = null;
    }

    private void cancelPingWatchdog() {
        if (pingWatchdogFuture != null) {
            pingWatchdogFuture.cancel(true);
            pingWatchdogFuture = null;
        }
    }

    private void cancelConnectFuture() {
        if (connectFuture != null) {
            connectFuture.cancel(true);
            connectFuture = null;
        }
    }

    private void scheduleConnect(int delaySeconds) {
        cancelConnectFuture();
        connectFuture = executorService.schedule(this::connect, delaySeconds, TimeUnit.SECONDS,
                String.format("[%s] Connect", logPrefix), 7000);
    }

    public boolean isInterrogated() {
        return interrogated;
    }

    public List<Channel> getDynamicChannels() {
        return dynamicChannels;
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
