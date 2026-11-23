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

import static no.seime.openhab.binding.esphome.internal.BindingConstants.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.audio.AudioHTTPServer;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.net.NetworkAddressService;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingActions;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.*;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessage;
import com.jano7.executor.KeySequentialExecutor;

import io.esphome.api.*;
import no.seime.openhab.binding.esphome.internal.*;
import no.seime.openhab.binding.esphome.internal.audio.DeviceAudioManager;
import no.seime.openhab.binding.esphome.internal.bluetooth.ESPHomeBluetoothProxyHandler;
import no.seime.openhab.binding.esphome.internal.comm.*;
import no.seime.openhab.binding.esphome.internal.comm.ConnectionTargetResolver.ResolvedConnectionTarget;
import no.seime.openhab.binding.esphome.internal.discovery.ESPHomeMDNSHostnameResolver;
import no.seime.openhab.binding.esphome.internal.handler.EncryptionKeyProvisioning.AdoptionFailedException;
import no.seime.openhab.binding.esphome.internal.handler.action.AbstractESPHomeThingAction;
import no.seime.openhab.binding.esphome.internal.handler.action.DynamicThingActionsGenerator;
import no.seime.openhab.binding.esphome.internal.handler.action.FirmwareUpgradeAction;
import no.seime.openhab.binding.esphome.internal.message.*;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.ESPHomeEventSubscriber;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.EventSubscription;
import no.seime.openhab.binding.esphome.internal.serial.ESPHomeSerialPortProvider;

/**
 * The {@link ESPHomeHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Arne Seime - Initial contribution
 */
@NonNullByDefault
public class ESPHomeHandler extends BaseThingHandler implements CommunicationListener, ESPHomeVersionListener {

    private static final int API_VERSION_MAJOR = 1;
    private static final int API_VERSION_MINOR = 14;
    private static final String DEVICE_LOGGER_NAME = "ESPHOMEDEVICE";

    private final Logger logger = LoggerFactory.getLogger(ESPHomeHandler.class);
    private final Logger deviceLogger = LoggerFactory.getLogger(DEVICE_LOGGER_NAME);

    private final @Nullable ManagedThingProvider managedThingProvider;
    private boolean provisioningConnection;
    private boolean provisioningKeyPending;
    private long connectionGeneration;
    private long connectionTimeoutGeneration;

    private final ConnectionSelector connectionSelector;
    private final ESPChannelTypeProvider dynamicChannelTypeProvider;
    private final ESPStateDescriptionProvider stateDescriptionProvider;
    private final Map<String, AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage>> commandTypeToHandlerMap = new HashMap<>();
    private final Map<Class<? extends GeneratedMessage>, AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage>> classToHandlerMap = new HashMap<>();
    private final List<Channel> dynamicChannels = new CopyOnWriteArrayList<>();
    private final ESPHomeEventSubscriber eventSubscriber;
    private final MonitoredCompositeExecutorService executorService;
    private final KeySequentialExecutor packetProcessor;
    private final HomeAssistantBridge homeAssistantBridge;
    @Nullable
    private final String bindingPropertyDefaultEncryptionKey;
    private final BundleContext bundleContext;
    private final ESPHomeVersionService versionService;
    private final FirmwareUpgradeService firmwareUpgradeService;
    private final ConnectionTargetResolver connectionTargetResolver;
    private final ESPHomeSerialPortProvider serialPortProvider;
    private @Nullable ESPHomeConfiguration config;
    private @Nullable EncryptedFrameHelper frameHelper;
    @Nullable
    private ScheduledFuture<?> pingWatchdogFuture;
    @Nullable
    private ScheduledFuture<?> deepSleepWatchdogFuture;
    @Nullable
    private ScheduledFuture<?> connectionTimeoutFuture;
    private Instant lastPong = Instant.now();
    @Nullable
    private ScheduledFuture<?> connectFuture;
    private final Object connectionStateLock = new Object();
    private ConnectionState connectionState = ConnectionState.UNINITIALIZED;
    private boolean disposed = false;
    private boolean interrogated;
    // Compilation time of the firmware the current channels, channel types and thing actions were created from
    @Nullable
    private String interrogatedCompilationTime;
    @Nullable
    private String pendingCompilationTime;
    private boolean bluetoothProxyStarted = false;
    // default is not used initialized in initialize()
    private ExponentialBackoff exponentialBackoff = new ExponentialBackoff(10, 500);
    @Nullable
    private String resolvedIpAddressForCurrentConnection;

    private final Set<ServiceRegistration<?>> thingActionServiceRegistrations = new HashSet<>();
    private final DeviceAudioManager audioManager;

    private String logPrefix;
    @Nullable
    private ESPHomeBluetoothProxyHandler espHomeBluetoothProxyHandler;
    private @Nullable String espHomeDeviceName;

    @Nullable
    private ClassLoader thingActionClassLoader;

    public ESPHomeHandler(Thing thing, ConnectionSelector connectionSelector,
            ESPChannelTypeProvider dynamicChannelTypeProvider, ESPStateDescriptionProvider stateDescriptionProvider,
            ESPHomeEventSubscriber eventSubscriber, MonitoredCompositeExecutorService executorService,
            KeySequentialExecutor packetProcessor, EventPublisher eventPublisher,
            @Nullable String bindingPropertyDefaultEncryptionKey, @Nullable String bindingPropertyAudioBaseUrl,
            BundleContext bundleContext, ESPHomeVersionService versionService,
            FirmwareUpgradeService firmwareUpgradeService, ESPHomeMDNSHostnameResolver mdnsHostnameResolver,
            AudioHTTPServer audioHTTPServer, NetworkAddressService networkAddressService,
            @Nullable ManagedThingProvider managedThingProvider, ESPHomeSerialPortProvider serialPortProvider) {
        super(thing);
        this.managedThingProvider = managedThingProvider;
        this.connectionSelector = connectionSelector;
        this.dynamicChannelTypeProvider = dynamicChannelTypeProvider;
        this.stateDescriptionProvider = stateDescriptionProvider;
        logPrefix = thing.getUID().getId();
        this.eventSubscriber = eventSubscriber;
        this.executorService = executorService;
        this.packetProcessor = packetProcessor;
        this.homeAssistantBridge = new HomeAssistantBridge(this, eventPublisher, eventSubscriber);
        this.bindingPropertyDefaultEncryptionKey = bindingPropertyDefaultEncryptionKey;
        this.bundleContext = bundleContext;
        this.versionService = versionService;
        this.firmwareUpgradeService = firmwareUpgradeService;
        this.connectionTargetResolver = new ConnectionTargetResolver(mdnsHostnameResolver);
        this.serialPortProvider = serialPortProvider;
        this.audioManager = new DeviceAudioManager(this, bundleContext, audioHTTPServer, networkAddressService,
                bindingPropertyAudioBaseUrl, () -> config != null && config.voiceDialog, this::runIfConnected);

        // Register message handlers for each type of message pairs
        registerMessageHandler(EntityTypes.SELECT, new SelectMessageHandler(this), ListEntitiesSelectResponse.class,
                SelectStateResponse.class);
        registerMessageHandler(EntityTypes.SENSOR, new SensorMessageHandler(this), ListEntitiesSensorResponse.class,
                SensorStateResponse.class);
        registerMessageHandler(EntityTypes.BINARY_SENSOR, new BinarySensorMessageHandler(this),
                ListEntitiesBinarySensorResponse.class, BinarySensorStateResponse.class);
        registerMessageHandler(EntityTypes.TEXT_SENSOR, new TextSensorMessageHandler(this),
                ListEntitiesTextSensorResponse.class, TextSensorStateResponse.class);
        registerMessageHandler(EntityTypes.TEXT, new TextMessageHandler(this), ListEntitiesTextResponse.class,
                TextStateResponse.class);
        registerMessageHandler(EntityTypes.SWITCH, new SwitchMessageHandler(this), ListEntitiesSwitchResponse.class,
                SwitchStateResponse.class);
        registerMessageHandler(EntityTypes.CLIMATE, new ClimateMessageHandler(this), ListEntitiesClimateResponse.class,
                ClimateStateResponse.class);
        registerMessageHandler(EntityTypes.NUMBER, new NumberMessageHandler(this), ListEntitiesNumberResponse.class,
                NumberStateResponse.class);
        registerMessageHandler(EntityTypes.LIGHT, new LightMessageHandler(this), ListEntitiesLightResponse.class,
                LightStateResponse.class);
        registerMessageHandler(EntityTypes.BUTTON, new ButtonMessageHandler(this), ListEntitiesButtonResponse.class,
                ButtonCommandRequest.class);
        registerMessageHandler(EntityTypes.COVER, new CoverMessageHandler(this), ListEntitiesCoverResponse.class,
                CoverStateResponse.class);
        registerMessageHandler(EntityTypes.FAN, new FanMessageHandler(this), ListEntitiesFanResponse.class,
                FanStateResponse.class);
        registerMessageHandler(EntityTypes.DATE, new DateMessageHandler(this), ListEntitiesDateResponse.class,
                DateStateResponse.class);
        registerMessageHandler(EntityTypes.DATE_TIME, new DateTimeMessageHandler(this),
                ListEntitiesDateTimeResponse.class, DateTimeStateResponse.class);
        registerMessageHandler(EntityTypes.TIME, new TimeMessageHandler(this), ListEntitiesTimeResponse.class,
                TimeStateResponse.class);
        registerMessageHandler(EntityTypes.LOCK, new LockMessageHandler(this), ListEntitiesLockResponse.class,
                LockStateResponse.class);
        registerMessageHandler(EntityTypes.VALVE, new ValveMessageHandler(this), ListEntitiesValveResponse.class,
                ValveStateResponse.class);
        registerMessageHandler(EntityTypes.EVENT, new EventMessageHandler(this), ListEntitiesEventResponse.class,
                EventResponse.class);
        registerMessageHandler(EntityTypes.MEDIA_PLAYER, new MediaPlayerMessageHandler(this),
                ListEntitiesMediaPlayerResponse.class, MediaPlayerStateResponse.class);
    }

    private void registerMessageHandler(String entityType,
            AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage> messageHandler,
            Class<? extends GeneratedMessage> listEntitiesClass, Class<? extends GeneratedMessage> stateClass) {

        commandTypeToHandlerMap.put(entityType, messageHandler);
        classToHandlerMap.put(listEntitiesClass, messageHandler);
        classToHandlerMap.put(stateClass, messageHandler);
    }

    @Override
    public void initialize() {
        disposed = false;

        logger.debug("[{}] Initializing ESPHome handler", thing.getUID());
        config = getConfigAs(ESPHomeConfiguration.class);

        // Use configured logprefix instead of default thingId
        logPrefix = thing.getUID().getId();
        if (config.logPrefix != null && !config.logPrefix.isEmpty()) {
            logPrefix = String.format("%s", config.logPrefix); // To avoid nullness warning
        }

        versionService.addListener(this);

        if (config.deepSleep) {
            if (config.deepSleepAssumeOnline) {
                logger.debug("[{}] Deep sleep assume online", logPrefix);
                updateStatus(ThingStatus.ONLINE);
                scheduleDeepSleepWatchdog();
            } else {
                updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE,
                        "Waiting for device to wake up from deep sleep");
            }
        } else {
            exponentialBackoff = new ExponentialBackoff(config.reconnectInterval, config.maxReconnectInterval);
            if (config.hostname != null && !config.hostname.isEmpty()) {
                scheduleConnect(0);
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No hostname configured");
            }
        }
    }

    @Override
    public void dispose() {
        synchronized (connectionStateLock) {
            disposed = true;
            connectionGeneration++;
            versionService.removeListener(this);
            eventSubscriber.removeEventSubscriptions(this);
            stateDescriptionProvider.removeDescriptionsForThing(thing.getUID());
            setUndefToAllChannels();
            cancelConnectFuture();
            cancelPingWatchdog();
            cancelConnectionTimeoutWatchdog();
            cancelDeepSleepWatchdog();
            audioManager.dispose();
            clearThingActions();
            unregisterSerialProxies();

            if (frameHelper != null) {
                if (connectionState == ConnectionState.CONNECTED) {
                    try {
                        frameHelper.send(DisconnectRequest.getDefaultInstance());
                    } catch (ProtocolAPIError e) {
                        // Quietly ignore
                    }
                }
                // ALWAYS close the connection to ensure the socket and I/O threads
                // are terminated and resources are released.
                frameHelper.close();
                frameHelper = null;
            }

            connectionState = ConnectionState.UNINITIALIZED;

            thingActionClassLoader = null;
            interrogatedCompilationTime = null;
            pendingCompilationTime = null;
        }
        dynamicChannelTypeProvider.discardPendingTypes(thing.getUID());
        super.dispose();
    }

    private void clearThingActions() {
        thingActionServiceRegistrations.stream().filter(e -> e != null).forEach(ServiceRegistration::unregister);
        thingActionServiceRegistrations.clear();
    }

    @Override
    public void handleRemoval() {
        dynamicChannelTypeProvider.removeChannelTypesForThing(thing.getUID());

        super.handleRemoval();
    }

    private void refreshAudioServices() {
        audioManager.refresh(interrogated && !disposed && connectionState == ConnectionState.CONNECTED);
    }

    private void runIfConnected(Runnable task) {
        synchronized (connectionStateLock) {
            if (!disposed && interrogated && connectionState == ConnectionState.CONNECTED) {
                task.run();
            }
        }
    }

    private void unregisterSerialProxies() {
        serialPortProvider.unregister(this);
    }

    private void connect() {
        synchronized (connectionStateLock) {
            try {
                if (disposed) {
                    return;
                }
                long generation = ++connectionGeneration;
                connectionState = ConnectionState.CONNECTING;

                String hostname = config.hostname;
                int port = config.port;
                ResolvedConnectionTarget connectionTarget = connectionTargetResolver.resolve(hostname,
                        thing.getProperties().get(PROPERTY_LAST_KNOWN_IP_ADDRESS), logPrefix);
                resolvedIpAddressForCurrentConnection = connectionTarget.cacheLastKnownIpAddress()
                        ? connectionTarget.ipAddress()
                        : null;
                applyLastKnownIpAddressPolicy(connectionTarget);

                logger.info("[{}] Trying to connect to {}:{}", logPrefix, connectionTarget.logTarget(), port);
                if (config.deepSleep && getThing().getStatus() != ThingStatus.ONLINE) {
                    updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE,
                            String.format("Connecting to %s:%d", connectionTarget.statusTarget(), port));
                }

                provisioningConnection = false;
                provisioningKeyPending = false;
                String encryptionKey = config.encryptionKey;
                if (StringUtils.isBlank(encryptionKey)) {
                    encryptionKey = StringUtils.defaultString(bindingPropertyDefaultEncryptionKey);
                }
                if (StringUtils.isBlank(encryptionKey)) {
                    if (managedThingProvider == null || managedThingProvider.get(thing.getUID()) == null) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                                "No encryption key configured. Automatic adoption requires an editable Thing; configure encryptionKey manually");
                        return;
                    }
                    encryptionKey = EncryptionKeyProvisioning.PROVISIONING_KEY;
                    provisioningConnection = true;
                } else {
                    EncryptionKeyProvisioning.rejectProvisioningKey(Objects.requireNonNull(encryptionKey));
                }

                frameHelper = new EncryptedFrameHelper(connectionSelector, createConnectionListener(generation),
                        encryptionKey, config.deviceId, logPrefix, packetProcessor);

                frameHelper.connect(connectionTarget.connectHost(), port);

                scheduleConnectionTimeoutWatchdog("Connection watchdog", "Connection attempt timed out");

            } catch (ProtocolException e) {
                connectionGeneration++;
                Throwable cause = e.getCause();
                String message = e.getMessage();
                if (cause != null) {
                    if (message == null) {
                        message = cause.getMessage();
                    } else {
                        String causeMessage = cause.getMessage();
                        if (causeMessage != null) {
                            message += " (" + cause.getMessage() + ")";
                        }
                    }
                }
                if (logger.isDebugEnabled()) {
                    logger.warn("[{}] Error initial connection: {}", logPrefix, message, e);
                } else {
                    logger.warn("[{}] Error initial connection: {}", logPrefix, message);
                }
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, message);
                connectionState = ConnectionState.UNINITIALIZED;
                resolvedIpAddressForCurrentConnection = null;

                if (config.deepSleep) {
                    if (logger.isDebugEnabled()) {
                        logger.info("[{}] Error connecting to device in deep sleep mode. Will wait for next wakeup: {}",
                                logPrefix, message, e);
                    } else {
                        logger.info("[{}] Error connecting to device in deep sleep mode. Will wait for next wakeup: {}",
                                logPrefix, message);
                    }
                } else {
                    scheduleConnect(exponentialBackoff.getNextDelay());
                }
            }
        }
    }

    private CommunicationListener createConnectionListener(long generation) {
        return new CommunicationListener() {
            @Override
            public void onConnect() throws ProtocolAPIError {
                synchronized (connectionStateLock) {
                    if (!disposed && generation == connectionGeneration) {
                        ESPHomeHandler.this.onConnect();
                    }
                }
            }

            @Override
            public void onPacket(GeneratedMessage message) {
                synchronized (connectionStateLock) {
                    if (!disposed && generation == connectionGeneration) {
                        ESPHomeHandler.this.onPacket(message);
                    }
                }
            }

            @Override
            public void onEndOfStream(String message) {
                synchronized (connectionStateLock) {
                    if (!disposed && generation == connectionGeneration) {
                        ESPHomeHandler.this.onEndOfStream(message);
                    }
                }
            }

            @Override
            public void onParseError(CommunicationError error) {
                synchronized (connectionStateLock) {
                    if (!disposed && generation == connectionGeneration) {
                        ESPHomeHandler.this.onParseError(error);
                    }
                }
            }
        };
    }

    public void sendMessage(GeneratedMessage message) throws ProtocolAPIError {
        synchronized (connectionStateLock) {
            if (provisioningConnection) {
                throw new ProtocolAPIError("Device adoption is in progress; commands require the saved encryption key");
            }
            EncryptedFrameHelper helper = frameHelper;
            if (helper != null) {
                helper.send(message);
            } else {
                throw new ProtocolAPIError(String.format("[%s] Device is not connected", logPrefix));
            }
        }
    }

    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return executorService.schedule(command, delay, unit);
    }

    private void setUndefToAllChannels() {
        // Update all channels to UNDEF to avoid stale values
        getThing().getChannels().forEach(channel -> updateState(channel.getUID(), UnDefType.UNDEF));
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        synchronized (connectionStateLock) {
            if (connectionState != ConnectionState.CONNECTED || provisioningConnection) {
                logger.warn("[{}] Not connected, ignoring command {}", logPrefix, command);
                return;
            }

            if (command == RefreshType.REFRESH) {
                if (channelUID.getId().equals(BindingConstants.CHANNEL_LATEST_FIRMWARE_VERSION)
                        || channelUID.getId().equals(BindingConstants.CHANNEL_FIRMWARE_UPDATE_AVAILABLE)) {
                    updateVersionChannels(thing.getProperties().get(Thing.PROPERTY_FIRMWARE_VERSION),
                            versionService.getLatestVersion());
                    return;
                }

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
                    String entityType = (String) channel.getConfiguration()
                            .get(BindingConstants.CHANNEL_CONFIGURATION_ENTITY_TYPE);
                    if (entityType == null) {
                        logger.warn("[{}] No entity type configuration found for channel {}", logPrefix, channelUID);
                        return;
                    }

                    AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage> abstractMessageHandler = commandTypeToHandlerMap
                            .get(entityType);
                    if (abstractMessageHandler == null) {
                        logger.warn("[{}] No message handler for entity type {}", logPrefix, entityType);
                    } else {
                        int key = ((BigDecimal) channel.getConfiguration()
                                .get(BindingConstants.CHANNEL_CONFIGURATION_ENTITY_KEY)).intValue();
                        abstractMessageHandler.handleCommand(channel, command, key);
                    }

                } catch (Exception e) {
                    logger.error("[{}] Error sending command {} to channel {}: {}", logPrefix, command, channelUID,
                            e.getMessage(), e);
                }
            });
        }
    }

    @Override
    public void onConnect() throws ProtocolAPIError {
        synchronized (connectionStateLock) {
            if (!provisioningConnection) {
                cancelConnectionTimeoutWatchdog();
            }
            exponentialBackoff.reset();
            logger.debug("[{}] Encrypted connection established. Starting API handshake.", logPrefix);
            HelloRequest helloRequest = HelloRequest.newBuilder().setClientInfo("openHAB")
                    .setApiVersionMajor(API_VERSION_MAJOR).setApiVersionMinor(API_VERSION_MINOR).build();
            connectionState = ConnectionState.HELLO_SENT;
            frameHelper.send(helloRequest);
            // Send this at the same time; no need to wait
            frameHelper.send(AuthenticationRequest.getDefaultInstance());
        }
    }

    @Override
    public void onPacket(@NonNull GeneratedMessage message) {
        synchronized (connectionStateLock) {
            try {
                switch (connectionState) {
                    case UNINITIALIZED -> logger.debug(
                            "[{}] Received packet {} while uninitialized, this can happen when the socket is closed while unprocessed packets exists. Ignoring",
                            logPrefix, message.getClass().getSimpleName());
                    case CONNECTING -> // We are still connecting, so we ignore any packets
                        logger.debug("[{}] Received packet {} while connecting, ignoring", logPrefix,
                                message.getClass().getSimpleName());
                    case HELLO_SENT -> handleHelloResponse(message);
                    case CONNECTED -> handleConnected(message);
                }
            } catch (ProtocolAPIError e) {
                logger.warn("[{}] Error parsing packet", logPrefix, e);
                onParseError(CommunicationError.PACKET_ERROR);
            }
        }
    }

    @Override
    public void onEndOfStream(String message) {
        String reason = "ESPHome device abruptly closed connection: " + message;
        handleDisconnection(ThingStatusDetail.COMMUNICATION_ERROR, reason);
    }

    @Override
    public void onParseError(CommunicationError error) {
        handleDisconnection(ThingStatusDetail.COMMUNICATION_ERROR, error.toString());
    }

    private void remoteDisconnect() {
        if (config.deepSleep && !provisioningConnection) {
            logger.info("[{}] Disconnecting from device in deep sleep mode", logPrefix);
            updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE, "Deep sleep");
            scheduleDeepSleepWatchdog();
        }
        String reason = "ESPHome device requested disconnect";
        handleDisconnection(ThingStatusDetail.NONE, reason);
    }

    private void handleDisconnection(ThingStatusDetail detail, String message) {
        synchronized (connectionStateLock) {
            if (connectionState == ConnectionState.UNINITIALIZED || disposed) {
                return;
            }

            connectionGeneration++;
            eventSubscriber.removeEventSubscriptions(this);
            cancelPingWatchdog();
            cancelConnectionTimeoutWatchdog();
            audioManager.disconnect();
            unregisterSerialProxies();

            if (frameHelper != null) {
                frameHelper.close();
                frameHelper = null;
            }

            connectionState = ConnectionState.UNINITIALIZED;
            resolvedIpAddressForCurrentConnection = null;

            if (!config.deepSleep || (provisioningConnection && StringUtils.isNotBlank(config.encryptionKey))) {
                setUndefToAllChannels();

                int nextDelay = exponentialBackoff.getNextDelay();
                String finalMessage = String.format("%s. Will reconnect in %d seconds", message, nextDelay);

                logger.warn("[{}] Disconnecting. Reason: {}", logPrefix, finalMessage);
                updateStatus(ThingStatus.OFFLINE, detail, finalMessage);

                scheduleConnect(nextDelay);
            } else if (provisioningConnection) {
                updateStatus(ThingStatus.OFFLINE, detail, message);
            }
        }
    }

    private void handleConnected(GeneratedMessage message) throws ProtocolAPIError {
        cancelDeepSleepWatchdog();

        if (logger.isTraceEnabled()) {
            // ToString method costs a bit
            logger.trace("[{}] Received message type {} with content '{}'", logPrefix,
                    message.getClass().getSimpleName(), StringUtils.trimToEmpty(message.toString()));
        }
        if (disposed) {
            return;
        }

        if (provisioningConnection && !EncryptionKeyProvisioning.isAllowedDuringProvisioning(message)) {
            return;
        }

        if (message instanceof AuthenticationResponse connectResponse) {
            if (connectResponse.getInvalidPassword()) {
                logger.trace("[{}] Received login response {}", logPrefix, connectResponse);

                handleDisconnection(ThingStatusDetail.CONFIGURATION_ERROR, "Invalid password");
            }
            return;
        }

        if (message instanceof NoiseEncryptionSetKeyResponse response && provisioningKeyPending) {
            provisioningKeyPending = false;
            if (!response.getSuccess()) {
                handleDisconnection(ThingStatusDetail.CONFIGURATION_ERROR,
                        "Device rejected the encryption key. The generated key remains in the Thing configuration");
            } else {
                logger.info("[{}] Device encryption key provisioned", logPrefix);
                handleDisconnection(ThingStatusDetail.NONE, "Encryption key provisioned; reconnecting");
            }
            return;
        }

        if (message instanceof DeviceInfoResponse rsp) {
            if (provisioningConnection) {
                provisionEncryptionKey(rsp);
                return;
            }
            Map<String, String> props = new HashMap<>(thing.getProperties());
            props.put(Thing.PROPERTY_FIRMWARE_VERSION, rsp.getEsphomeVersion());
            props.put(Thing.PROPERTY_MAC_ADDRESS, rsp.getMacAddress());
            props.put(Thing.PROPERTY_MODEL_ID, rsp.getModel());
            props.put("name", rsp.getName());
            props.put(Thing.PROPERTY_VENDOR, rsp.getManufacturer());
            props.put("compilationTime", rsp.getCompilationTime());
            espHomeDeviceName = rsp.getName();
            if (!rsp.getProjectName().isEmpty()) {
                props.put("projectName", rsp.getProjectName());
            } else {
                props.remove("projectName");
            }
            if (!rsp.getProjectVersion().isEmpty()) {
                props.put("projectVersion", rsp.getProjectVersion());
            } else {
                props.remove("projectVersion");
            }
            if (!rsp.getHasDeepSleep() && config.deepSleep) {
                logger.warn(
                        "[{}] Thing is configured with deep sleep, but the ESPHome device does not support it. Disabling deep sleep mode",
                        logPrefix);
                config.deepSleep = false;
            }

            audioManager.setVoiceAssistantFeatureFlags(rsp.getVoiceAssistantFeatureFlags());

            serialPortProvider.register(this, rsp.getSerialProxiesList());

            updateThing(editThing().withProperties(props).build());

            String compilationTime = rsp.getCompilationTime();
            if (!compilationTime.isEmpty() && compilationTime.equals(interrogatedCompilationTime)) {
                logger.debug(
                        "[{}] Firmware compilation time '{}' unchanged since last interrogation, reusing existing channels and actions",
                        logPrefix, compilationTime);
                frameHelper.send(SubscribeHomeAssistantStatesRequest.getDefaultInstance());
                frameHelper.send(SubscribeStatesRequest.getDefaultInstance());
                updateVersionChannels(thing.getProperties().get(Thing.PROPERTY_FIRMWARE_VERSION),
                        versionService.getLatestVersion());
                refreshAudioServices();
            } else {
                logger.debug("[{}] Firmware compilation time changed from '{}' to '{}', starting device interrogation",
                        logPrefix, interrogatedCompilationTime, compilationTime);
                startInterrogation(compilationTime);
            }
        } else if (message instanceof ListEntitiesMediaPlayerResponse mediaPlayerResponse) {
            audioManager.onMediaPlayerEntity(mediaPlayerResponse);
            classToHandlerMap.get(message.getClass()).handleMessage(message);
        } else if (message instanceof SerialProxyDataReceived serialProxyDataReceived) {
            serialPortProvider.handleData(this, serialProxyDataReceived);
        } else if (message instanceof SerialProxyGetModemPinsResponse serialProxyGetModemPinsResponse) {
            serialPortProvider.handleModemPins(this, serialProxyGetModemPinsResponse);
        } else if (message instanceof ListEntitiesDoneResponse) {

            addFirmwareChannels();

            thingActionServiceRegistrations.add(bundleContext.registerService(ThingActions.class,
                    new FirmwareUpgradeAction(this), new Hashtable<>()));

            updateThing(editThing().withChannels(dynamicChannels).build());
            sweepOrphanedChannelTypes();
            logger.debug("[{}] Device interrogation complete, done updating thing channels", logPrefix);
            interrogated = true;
            interrogatedCompilationTime = pendingCompilationTime;
            refreshAudioServices();
            frameHelper.send(SubscribeStatesRequest.getDefaultInstance());

            updateVersionChannels(thing.getProperties().get(Thing.PROPERTY_FIRMWARE_VERSION),
                    versionService.getLatestVersion());

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
            remoteDisconnect();
        } else if (message instanceof SubscribeLogsResponse subscribeLogsResponse) {
            deviceLogger.info("[{}] {}", logPrefix, subscribeLogsResponse.getMessage().toStringUtf8());
        } else if (message instanceof VoiceAssistantRequest voiceAssistantRequest) {
            audioManager.onVoiceAssistantRequest(voiceAssistantRequest);
        } else if (message instanceof VoiceAssistantAudio voiceAssistantAudio) {
            audioManager.onVoiceAssistantAudio(voiceAssistantAudio);
        } else if (message instanceof VoiceAssistantAnnounceFinished) {
            // the media player state already shows when a reply has played
            logger.debug("[{}] Device finished playing a voice assistant reply", logPrefix);
        } else if (message instanceof HomeassistantActionRequest actionRequest) {
            homeAssistantBridge.onActionRequest(actionRequest, config.deviceId);
        } else if (message instanceof SubscribeHomeAssistantStateResponse subscribeHomeAssistantStateResponse) {
            sendStateToDevice(homeAssistantBridge.subscribe(subscribeHomeAssistantStateResponse));
        } else if (message instanceof GetTimeRequest) {
            logger.debug("[{}] Received time sync request", logPrefix);
            GetTimeResponse getTimeResponse = GetTimeResponse.newBuilder()
                    .setEpochSeconds((int) (System.currentTimeMillis() / 1000)).build();
            frameHelper.send(getTimeResponse);
        } else if (message instanceof BluetoothLEAdvertisementResponse
                || message instanceof BluetoothLERawAdvertisementsResponse
                || message instanceof BluetoothDeviceConnectionResponse
                || message instanceof BluetoothGATTGetServicesResponse
                || message instanceof BluetoothGATTGetServicesDoneResponse
                || message instanceof BluetoothGATTReadResponse || message instanceof BluetoothGATTNotifyDataResponse
                || message instanceof BluetoothConnectionsFreeResponse || message instanceof BluetoothGATTErrorResponse
                || message instanceof BluetoothGATTWriteResponse || message instanceof BluetoothGATTNotifyResponse
                || message instanceof BluetoothDevicePairingResponse
                || message instanceof BluetoothDeviceUnpairingResponse
                || message instanceof BluetoothDeviceClearCacheResponse
                || message instanceof BluetoothScannerStateResponse) {
            if (espHomeBluetoothProxyHandler != null) {
                espHomeBluetoothProxyHandler.handleBluetoothMessage(message, this);
            }
        } else if (message instanceof ListEntitiesServicesResponse listEntitiesServicesResponse) {
            logger.debug("[{}] Received list entities services response {}", logPrefix, listEntitiesServicesResponse);

            try {
                AbstractESPHomeThingAction thingAction = DynamicThingActionsGenerator
                        .generateDynamicThingAction(listEntitiesServicesResponse, thingActionClassLoader);
                thingAction.setListEntitiesServicesResponse(listEntitiesServicesResponse);
                thingAction.setThingHandler(this);
                thingActionServiceRegistrations
                        .add(bundleContext.registerService(ThingActions.class, thingAction, new Hashtable<>()));

            } catch (Exception e) {
                logger.warn("[{}] Error generating dynamic actions from device: {}", logPrefix, e.getMessage(), e);
            }

        } else {
            if (message instanceof MediaPlayerStateResponse mediaPlayerStateResponse) {
                audioManager.onMediaPlayerState(mediaPlayerStateResponse);
            }
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

    private void addFirmwareChannels() {
        dynamicChannels.add(0, versionService.createFirmwareUpdateAvailableChannel(thing.getUID()));
        dynamicChannels.add(0, versionService.createLatestFirmwareVersionChannel(thing.getUID()));
    }

    private void sweepOrphanedChannelTypes() {
        Set<ChannelTypeUID> stillReferenced = new HashSet<>();
        for (Channel channel : thing.getChannels()) {
            ChannelTypeUID typeUID = channel.getChannelTypeUID();
            if (typeUID != null) {
                stillReferenced.add(typeUID);
            }
        }
        dynamicChannelTypeProvider.removeOrphanedChannelTypesForThing(thing.getUID(), stillReferenced);
    }

    public void sendBluetoothCommand(GeneratedMessage message) {
        synchronized (connectionStateLock) {
            try {
                if (connectionState == ConnectionState.CONNECTED && !provisioningConnection) {
                    frameHelper.send(message);
                } else {
                    logger.warn("[{}] Not connected, ignoring bluetooth command {}", logPrefix, message);
                }
            } catch (ProtocolAPIError e) {
                logger.error("[{}] Error sending bluetooth command", logPrefix, e);
            }
        }
    }

    public void handleOpenHABEvent(EventSubscription subscription, String esphomeState) {
        synchronized (connectionStateLock) {
            if (disposed || provisioningConnection || connectionState != ConnectionState.CONNECTED) {
                logger.debug("[{}] Not connected, skipping OpenHAB event for {}", logPrefix,
                        subscription.getEntityId());
                return;
            }
            sendStateToDevice(HomeAssistantBridge.stateResponse(subscription, esphomeState));
        }
    }

    private void sendStateToDevice(HomeAssistantStateResponse stateResponse) {
        try {
            frameHelper.send(stateResponse);
        } catch (ProtocolAPIError e) {
            logger.warn("[{}] Error sending OpenHAB state update to ESPHome", logPrefix, e);
        }
    }

    @Override
    public void updateState(ChannelUID channelUID, State state) {
        super.updateState(channelUID, state);
    }

    @Override
    public void triggerChannel(ChannelUID channelUID, String event) {
        ThingHandlerCallback callback = getCallback();
        if (callback != null) {
            callback.channelTriggered(thing, channelUID, event);
        }
    }

    private void provisionEncryptionKey(DeviceInfoResponse response) throws ProtocolAPIError {
        if (provisioningKeyPending) {
            return;
        }
        String encodedKey;
        try {
            encodedKey = EncryptionKeyProvisioning.generateAndSaveKey(response, managedThingProvider, thing.getUID(),
                    this::updateConfiguration);
        } catch (AdoptionFailedException e) {
            handleDisconnection(ThingStatusDetail.CONFIGURATION_ERROR, String.valueOf(e.getMessage()));
            return;
        }
        config.encryptionKey = encodedKey;
        provisioningKeyPending = true;
        // The API's bytes field carries the base64 text, not the decoded 32-byte key.
        frameHelper.send(NoiseEncryptionSetKeyRequest.newBuilder().setKey(ByteString.copyFromUtf8(encodedKey)).build());
        scheduleConnectionTimeoutWatchdog("Adoption watchdog",
                "Timed out waiting for encryption key confirmation; reconnecting with the saved key");
    }

    private void handleHelloResponse(GeneratedMessage message) throws ProtocolAPIError {
        if (message instanceof HelloResponse helloResponse) {
            synchronized (connectionStateLock) {
                logger.trace("[{}] Received hello response {}", logPrefix, helloResponse);
                logger.info("[{}] Connected successfully. Device '{}' is running '{}' on protocol version '{}.{}'",
                        logPrefix, helloResponse.getName(), helloResponse.getServerInfo(),
                        helloResponse.getApiVersionMajor(), helloResponse.getApiVersionMinor());
                connectionState = ConnectionState.CONNECTED;
                if (provisioningConnection) {
                    updateStatus(ThingStatus.UNKNOWN);
                    frameHelper.send(DeviceInfoRequest.getDefaultInstance());
                    return;
                }
                persistLastKnownIpAddress();

                if (config.allowActions) {
                    logger.debug("[{}] Requesting device to send actions and events", logPrefix);
                    frameHelper.send(SubscribeHomeassistantServicesRequest.getDefaultInstance());
                }
                if (config.deviceLogLevel != no.seime.openhab.binding.esphome.internal.LogLevel.NONE) {
                    logger.info("[{}] Starting to stream logs to logger " + DEVICE_LOGGER_NAME, logPrefix);

                    frameHelper.send(SubscribeLogsRequest.newBuilder()
                            .setLevel(io.esphome.api.LogLevel.valueOf("LOG_LEVEL_" + config.deviceLogLevel.name()))
                            .build());
                }

                updateStatus(provisioningConnection ? ThingStatus.UNKNOWN : ThingStatus.ONLINE);
                logger.debug("[{}] Device login complete, requesting device info", logPrefix);

                if (!config.deepSleep) {
                    // Reset last pong
                    lastPong = Instant.now();

                    pingWatchdogFuture = executorService.scheduleAtFixedRate(() -> {
                        synchronized (connectionStateLock) {
                            if (lastPong.plusSeconds((long) config.maxPingTimeouts * config.pingInterval)
                                    .isBefore(Instant.now())) {
                                logger.warn(
                                        "[{}] Ping responses lacking. Waited {} times {}s, total of {}s. Last pong received at {}. Assuming connection lost and disconnecting",
                                        logPrefix, config.maxPingTimeouts, config.pingInterval,
                                        config.maxPingTimeouts * config.pingInterval, lastPong);

                                String reason = String.format(
                                        "ESPHome did not respond to ping requests. %d pings sent with %d s delay",
                                        config.maxPingTimeouts, config.pingInterval);
                                handleDisconnection(ThingStatusDetail.COMMUNICATION_ERROR, reason);
                            } else {
                                if (connectionState == ConnectionState.CONNECTED) {
                                    try {
                                        logger.debug("[{}] Sending ping", logPrefix);
                                        frameHelper.send(PingRequest.getDefaultInstance());
                                    } catch (ProtocolAPIError e) {
                                        logger.warn("[{}] Error sending ping request", logPrefix, e);
                                    }
                                }
                            }
                        }
                    }, config.pingInterval, config.pingInterval, TimeUnit.SECONDS,
                            String.format("[%s] Ping watchdog", logPrefix));
                } else {
                    scheduleDeepSleepWatchdog();
                }

                // Entity interrogation is decided upon receiving DeviceInfoResponse
                frameHelper.send(DeviceInfoRequest.getDefaultInstance());
            }
        }
    }

    private void startInterrogation(String compilationTime) throws ProtocolAPIError {
        pendingCompilationTime = compilationTime;
        // Clear the local list of channels; the previous set stays on the Thing (and its channel-type
        // references stay resolvable in the type provider) until updateThing() in ListEntitiesDoneResponse replaces
        // them. Orphaned types are swept later, after updateThing() has propagated, to avoid racing with
        // ThingManagerImpl.normalizeConfiguration.
        dynamicChannelTypeProvider.discardPendingTypes(thing.getUID());
        dynamicChannels.clear();

        // Clean up old actions
        clearThingActions();
        audioManager.clearEntities();
        // Create a new classloader for the ThingActions generated from this device, to allow for proper
        // unloading of old classes when the device reappears with different actions after a reconnect
        thingActionClassLoader = new ClassLoader(getClass().getClassLoader()) {
        };

        frameHelper.send(ListEntitiesRequest.getDefaultInstance());
        frameHelper.send(SubscribeHomeAssistantStatesRequest.getDefaultInstance());
    }

    private void scheduleDeepSleepWatchdog() {
        cancelDeepSleepWatchdog();
        if (config.deepSleepTimeoutSeconds > 0) {
            deepSleepWatchdogFuture = executorService
                    .schedule(
                            () -> updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.GONE,
                                    String.format(
                                            "Device in deep sleep mode was not made connectable within timeout of %ds",
                                            config.deepSleepTimeoutSeconds)),
                            config.deepSleepTimeoutSeconds, TimeUnit.SECONDS);
        }
    }

    private void applyLastKnownIpAddressPolicy(ResolvedConnectionTarget connectionTarget) {
        if (!connectionTarget.cacheLastKnownIpAddress()) {
            setThingProperty(PROPERTY_LAST_KNOWN_IP_ADDRESS, null);
        }
    }

    private void persistLastKnownIpAddress() {
        String ipAddress = resolvedIpAddressForCurrentConnection;
        if (StringUtils.isNotBlank(ipAddress)) {
            setThingProperty(PROPERTY_LAST_KNOWN_IP_ADDRESS, ipAddress);
        }
    }

    private void setThingProperty(String key, @Nullable String value) {
        if (Objects.equals(thing.getProperties().get(key), value)) {
            return;
        }
        Map<String, String> props = new HashMap<>(thing.getProperties());
        if (value == null) {
            props.remove(key);
        } else {
            props.put(key, value);
        }
        updateThing(editThing().withProperties(props).build());
    }

    public void addChannelType(ChannelType channelType) {
        dynamicChannelTypeProvider.addChannelType(thing.getUID(), channelType);
    }

    public void addDescription(ChannelUID channelUID, StateDescription stateDescription) {
        stateDescriptionProvider.setDescription(channelUID, stateDescription);
    }

    public void addDescription(ChannelUID channelUID, CommandDescription commandDescription) {
        stateDescriptionProvider.setDescription(channelUID, commandDescription);
    }

    public void addChannel(Channel channel) {
        dynamicChannels.add(channel);
    }

    public boolean isDisposed() {
        return disposed;
    }

    public void listenForBLEAdvertisements(ESPHomeBluetoothProxyHandler espHomeBluetoothProxyHandler) {
        synchronized (connectionStateLock) {
            this.espHomeBluetoothProxyHandler = espHomeBluetoothProxyHandler;
            if (config.enableBluetoothProxy && !bluetoothProxyStarted && connectionState == ConnectionState.CONNECTED
                    && !provisioningConnection) {
                try {
                    logger.info("[{}] Starting BLE proxy", logPrefix);
                    frameHelper.send(SubscribeBluetoothLEAdvertisementsRequest.getDefaultInstance());
                    bluetoothProxyStarted = true;
                } catch (Exception e) {
                    logger.error("[{}] Error starting BLE proxy", logPrefix, e);
                }
            }
        }
    }

    public void stopListeningForBLEAdvertisements() {
        synchronized (connectionStateLock) {
            if (connectionState == ConnectionState.CONNECTED && !provisioningConnection) {
                try {
                    logger.info("[{}] Stopping BLE proxy", logPrefix);
                    frameHelper.send(UnsubscribeBluetoothLEAdvertisementsRequest.getDefaultInstance());
                } catch (Exception e) {
                    logger.warn("[{}] Error stopping BLE proxy", logPrefix, e);
                }
            }

            bluetoothProxyStarted = false;
            espHomeBluetoothProxyHandler = null;
        }
    }

    public void executeAPIAction(ExecuteServiceRequest cmd) {
        synchronized (connectionStateLock) {
            if (disposed || provisioningConnection || connectionState != ConnectionState.CONNECTED) {
                logger.warn("[{}] Not connected, cannot execute API action {}", logPrefix, cmd.getKey());
            } else {
                try {
                    frameHelper.send(cmd);
                } catch (ProtocolAPIError e) {
                    logger.warn("[{}] Error sending API action {}", logPrefix, e.getMessage(), e);
                }
            }
        }
    }

    private void cancelPingWatchdog() {
        if (pingWatchdogFuture != null) {
            pingWatchdogFuture.cancel(true);
            pingWatchdogFuture = null;
        }
    }

    private void cancelDeepSleepWatchdog() {
        if (deepSleepWatchdogFuture != null) {
            deepSleepWatchdogFuture.cancel(true);
            deepSleepWatchdogFuture = null;
        }
    }

    private void cancelConnectFuture() {
        if (connectFuture != null) {
            connectFuture.cancel(true);
            connectFuture = null;
        }
    }

    private void scheduleConnectionTimeoutWatchdog(String name, String message) {
        synchronized (connectionStateLock) {
            cancelConnectionTimeoutWatchdog();
            long generation = connectionGeneration;
            long timeoutGeneration = connectionTimeoutGeneration;
            connectionTimeoutFuture = executorService.schedule(() -> {
                synchronized (connectionStateLock) {
                    // Cancellation cannot stop a task already waiting for this lock.
                    if (!disposed && generation == connectionGeneration
                            && timeoutGeneration == connectionTimeoutGeneration) {
                        handleDisconnection(ThingStatusDetail.COMMUNICATION_ERROR, message);
                    }
                }
            }, config.connectTimeout, TimeUnit.SECONDS, String.format("[%s] %s", logPrefix, name));
        }
    }

    private void cancelConnectionTimeoutWatchdog() {
        connectionTimeoutGeneration++;
        if (connectionTimeoutFuture != null) {
            connectionTimeoutFuture.cancel(true);
            connectionTimeoutFuture = null;
        }
    }

    private void scheduleConnect(int delaySeconds) {
        synchronized (connectionStateLock) {
            cancelConnectFuture();
            connectFuture = executorService.schedule(this::connect, delaySeconds, TimeUnit.SECONDS,
                    String.format("[%s] Connect", logPrefix), 7000);
        }
    }

    public boolean isInterrogated() {
        return interrogated;
    }

    public List<Channel> getDynamicChannels() {
        return dynamicChannels;
    }

    public String getLogPrefix() {
        return logPrefix;
    }

    public void onDeviceReappeared() {
        logger.debug("[{}] Device reappeared via mDNS, connection state {}", logPrefix, connectionState);
        synchronized (connectionStateLock) {
            ScheduledFuture<?> cF = connectFuture;
            if (connectionState == ConnectionState.UNINITIALIZED
                    && (cF == null || cF.getDelay(TimeUnit.SECONDS) > 0 || config.deepSleep)) {
                logger.info("[{}] Device reappeared via mDNS, triggering immediate reconnect", logPrefix);
                exponentialBackoff.reset();
                scheduleConnect(0);
            }
        }
    }

    public @Nullable String getDeviceId() {
        return config != null ? config.deviceId : null;
    }

    @Override
    public void onVersionUpdate(String version) {
        String currentVersion = thing.getProperties().get(Thing.PROPERTY_FIRMWARE_VERSION);
        updateVersionChannels(currentVersion, version);
    }

    private void updateVersionChannels(@Nullable String currentVersion, @Nullable String latestVersion) {
        if (latestVersion != null) {
            updateState(new ChannelUID(thing.getUID(), BindingConstants.CHANNEL_LATEST_FIRMWARE_VERSION),
                    new StringType(latestVersion));
            if (currentVersion != null) {
                boolean updateAvailable = ESPHomeVersionService.isVersionNewer(latestVersion, currentVersion,
                        logPrefix);
                updateState(new ChannelUID(thing.getUID(), BindingConstants.CHANNEL_FIRMWARE_UPDATE_AVAILABLE),
                        updateAvailable ? OpenClosedType.OPEN : OpenClosedType.CLOSED);
            }
        }
    }

    public @Nullable String getHostname() {
        return config != null ? config.hostname : null;
    }

    /**
     * Serial proxy lifecycle and writes share the connection lock so disconnect cannot race a subscription.
     */
    public Object getSerialProxyLock() {
        return connectionStateLock;
    }

    public @Nullable String getResolvedIpAddress() {
        return resolvedIpAddressForCurrentConnection;
    }

    public int getPort() {
        return config != null ? config.port : 6053;
    }

    public @Nullable String getEspHomeDeviceName() {
        return espHomeDeviceName;
    }

    private enum ConnectionState {
        // Initial state, no connection
        UNINITIALIZED,
        // TCP connect ongoing
        CONNECTING,
        // TCP connected to ESPHome, first handshake sent
        HELLO_SENT,

        // Connection established
        CONNECTED

    }

    public FirmwareUpgradeService getFirmwareUpgradeService() {
        return firmwareUpgradeService;
    }

    public ESPHomeVersionService getVersionService() {
        return versionService;
    }
}
