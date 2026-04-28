package no.seime.openhab.binding.esphome.internal.handler;

import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.core.audio.AudioHTTPServer;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.net.NetworkAddressService;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.internal.ThingImpl;
import org.osgi.framework.BundleContext;

import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessage;
import com.jano7.executor.KeySequentialExecutor;

import io.esphome.api.DeviceInfoResponse;
import io.esphome.api.SerialProxyDataReceived;
import io.esphome.api.SerialProxyGetModemPinsResponse;
import io.esphome.api.SerialProxyInfo;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.ESPHomeConfiguration;
import no.seime.openhab.binding.esphome.internal.ESPHomeVersionService;
import no.seime.openhab.binding.esphome.internal.FirmwareUpgradeService;
import no.seime.openhab.binding.esphome.internal.MonitoredCompositeExecutorService;
import no.seime.openhab.binding.esphome.internal.comm.ConnectionSelector;
import no.seime.openhab.binding.esphome.internal.comm.EncryptedFrameHelper;
import no.seime.openhab.binding.esphome.internal.discovery.ESPHomeMDNSHostnameResolver;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.ESPHomeEventSubscriber;
import no.seime.openhab.binding.esphome.internal.serial.ESPHomeSerialPortProvider;

@ExtendWith(MockitoExtension.class)
class ESPHomeHandlerSerialProxyTest {
    @Mock
    private ESPChannelTypeProvider channelTypeProvider;
    @Mock
    private ESPStateDescriptionProvider stateDescriptionProvider;
    @Mock
    private ESPHomeEventSubscriber eventSubscriber;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private BundleContext bundleContext;
    @Mock
    private AudioHTTPServer audioHTTPServer;
    @Mock
    private NetworkAddressService networkAddressService;
    @Mock
    private ThingHandlerCallback callback;
    @Mock
    private ESPHomeSerialPortProvider serialPortProvider;
    @Mock
    private ESPHomeMDNSHostnameResolver mdnsHostnameResolver;
    @Mock
    private ESPHomeVersionService versionService;
    @Mock
    private FirmwareUpgradeService firmwareUpgradeService;
    @Mock
    private EncryptedFrameHelper frameHelper;

    private ESPHomeHandler handler;
    private MonitoredCompositeExecutorService executor;
    private ExecutorService packetProcessorExecutor;

    @BeforeEach
    void setUp() throws Exception {
        executor = new MonitoredCompositeExecutorService(Executors.newScheduledThreadPool(1),
                (ThreadPoolExecutor) Executors.newCachedThreadPool(), 1000);
        packetProcessorExecutor = Executors.newSingleThreadExecutor();
        handler = new ESPHomeHandler(new ThingImpl(BindingConstants.THING_TYPE_DEVICE, "device"),
                new ConnectionSelector(), channelTypeProvider, stateDescriptionProvider, eventSubscriber, executor,
                new KeySequentialExecutor(packetProcessorExecutor), eventPublisher, null, null, bundleContext,
                versionService, firmwareUpgradeService, mdnsHostnameResolver, audioHTTPServer, networkAddressService,
                null, serialPortProvider);
        setField("config", new ESPHomeConfiguration());
        setField("frameHelper", frameHelper);
        handler.setCallback(callback);
    }

    @AfterEach
    void tearDown() {
        handler.dispose();
        executor.shutdownNow();
        packetProcessorExecutor.shutdownNow();
    }

    @Test
    void registersAdvertisedSerialProxiesFromDeviceInfoResponse() throws Exception {
        SerialProxyInfo serialProxy = SerialProxyInfo.newBuilder().setName("main").build();
        DeviceInfoResponse response = DeviceInfoResponse.newBuilder().setName("virtual").setMacAddress("AA")
                .setModel("ESP32").setManufacturer("Espressif").setEsphomeVersion("2026.1.0").setCompilationTime("now")
                .addSerialProxies(serialProxy).build();

        invokeHandleConnected(response);

        verify(serialPortProvider).register(handler, response.getSerialProxiesList());
    }

    @Test
    void delegatesSerialProxyDataAndModemPinResponsesToProvider() throws Exception {
        SerialProxyDataReceived data = SerialProxyDataReceived.newBuilder().setInstance(0)
                .setData(ByteString.copyFromUtf8("abc")).build();
        SerialProxyGetModemPinsResponse modemPins = SerialProxyGetModemPinsResponse.newBuilder().setInstance(0)
                .setLineStates(3).build();

        invokeHandleConnected(data);
        invokeHandleConnected(modemPins);

        verify(serialPortProvider).handleData(handler, data);
        verify(serialPortProvider).handleModemPins(handler, modemPins);
    }

    @Test
    void unregistersSerialProxiesOnDisconnect() throws Exception {
        setField("connectionState", enumValue("CONNECTED"));

        invokeMethod("handleDisconnection", new Class<?>[] { ThingStatusDetail.class, String.class },
                ThingStatusDetail.COMMUNICATION_ERROR, "test");

        verify(serialPortProvider).unregister(handler);
    }

    private void invokeHandleConnected(GeneratedMessage message) throws Exception {
        invokeMethod("handleConnected", new Class<?>[] { GeneratedMessage.class }, message);
    }

    private Object invokeMethod(String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = ESPHomeHandler.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(handler, args);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = ESPHomeHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(handler, value);
    }

    private Object enumValue(String name) throws Exception {
        Field field = ESPHomeHandler.class.getDeclaredField("connectionState");
        return Enum.valueOf(field.getType().asSubclass(Enum.class), name);
    }
}
