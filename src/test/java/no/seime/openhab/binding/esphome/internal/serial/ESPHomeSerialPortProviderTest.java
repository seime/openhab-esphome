package no.seime.openhab.binding.esphome.internal.serial;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openhab.core.io.transport.serial.PortInUseException;
import org.openhab.core.io.transport.serial.SerialPort;
import org.openhab.core.io.transport.serial.SerialPortIdentifier;
import org.openhab.core.thing.internal.ThingImpl;

import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessage;

import io.esphome.api.SerialProxyConfigureRequest;
import io.esphome.api.SerialProxyDataReceived;
import io.esphome.api.SerialProxyGetModemPinsResponse;
import io.esphome.api.SerialProxyInfo;
import io.esphome.api.SerialProxyParity;
import io.esphome.api.SerialProxyRequest;
import io.esphome.api.SerialProxyRequestType;
import io.esphome.api.SerialProxySetModemPinsRequest;
import io.esphome.api.SerialProxyWriteRequest;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ESPHomeSerialPortProviderTest {
    @Mock
    private ESPHomeHandler handler;

    private ESPHomeSerialPortProvider provider;
    private ThingImpl thing;

    @BeforeEach
    void setUp() {
        provider = new ESPHomeSerialPortProvider();
        thing = new ThingImpl(BindingConstants.THING_TYPE_DEVICE, "thing-id");

        when(handler.getThing()).thenReturn(thing);
        when(handler.getPort()).thenReturn(6053);
        when(handler.getDeviceId()).thenReturn("device-id");
        when(handler.getHostname()).thenReturn("localhost");
        when(handler.getEspHomeDeviceName()).thenReturn("esphome-name");

        provider.register(handler, proxies());
    }

    @Test
    void matchesByThingIdDeviceIdHostnameResolvedHostnameAndEspHomeDeviceName() {
        assertIdentifier("esphome://thing-id/main");
        assertIdentifier("esphome://device-id/main");
        assertIdentifier("esphome://localhost/main");
        assertIdentifier("esphome://127.0.0.1/main");
        assertIdentifier("esphome://esphome-name/main");
    }

    @Test
    void defaultsToInstanceZeroWhenProxyPathIsEmpty() {
        SerialPortIdentifier identifier = provider.getPortIdentifier(URI.create("esphome://device-id/"));

        assertNotNull(identifier);
        assertEquals("esphome://device-id/main", identifier.getName());
    }

    @Test
    void matchesProxyByInstanceNumber() {
        SerialPortIdentifier identifier = provider.getPortIdentifier(URI.create("esphome://device-id/1"));

        assertNotNull(identifier);
        assertEquals("esphome://device-id/aux", identifier.getName());
    }

    @Test
    void requiresMatchingPortWhenPortIsSpecified() {
        assertNotNull(provider.getPortIdentifier(URI.create("esphome://device-id:6053/main")));
        assertNull(provider.getPortIdentifier(URI.create("esphome://device-id:6054/main")));
    }

    @Test
    void exposesAcceptedProtocolAndDiscoverableIdentifiers() {
        assertEquals("esphome", provider.getAcceptedProtocols().findFirst().orElseThrow().getScheme());

        assertEquals(2, provider.getSerialPortIdentifiers().count());
    }

    @Test
    void unregisterRemovesDeviceAndClosesOpenPorts() throws Exception {
        SerialPortIdentifier identifier = provider.getPortIdentifier(URI.create("esphome://device-id/main"));
        assertNotNull(identifier);
        SerialPort port = identifier.open("owner", 1000);

        provider.unregister(handler);

        assertNull(provider.getPortIdentifier(URI.create("esphome://device-id/main")));
        assertFalse(identifier.isCurrentlyOwned());
        assertEquals(-1, requireNonNull(port.getInputStream()).read());
    }

    @Test
    void openAndCloseSubscribeAndUnsubscribe() throws Exception {
        SerialPortIdentifier identifier = provider.getPortIdentifier(URI.create("esphome://device-id/main"));
        assertNotNull(identifier);

        SerialPort port = identifier.open("owner", 1000);
        assertTrue(identifier.isCurrentlyOwned());
        assertEquals("owner", identifier.getCurrentOwner());

        port.close();

        ArgumentCaptor<GeneratedMessage> captor = ArgumentCaptor.forClass(GeneratedMessage.class);
        verify(handler, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        assertTrue(captor.getAllValues().stream().filter(SerialProxyRequest.class::isInstance)
                .map(SerialProxyRequest.class::cast).anyMatch(request -> request.getInstance() == 0
                        && request.getType() == SerialProxyRequestType.SERIAL_PROXY_REQUEST_TYPE_SUBSCRIBE));
        assertTrue(captor.getAllValues().stream().filter(SerialProxyRequest.class::isInstance)
                .map(SerialProxyRequest.class::cast).anyMatch(request -> request.getInstance() == 0
                        && request.getType() == SerialProxyRequestType.SERIAL_PROXY_REQUEST_TYPE_UNSUBSCRIBE));
        assertFalse(identifier.isCurrentlyOwned());
    }

    @Test
    void rejectsSecondOpenOfSameProxy() throws Exception {
        SerialPortIdentifier identifier = provider.getPortIdentifier(URI.create("esphome://device-id/main"));
        assertNotNull(identifier);
        identifier.open("owner", 1000);

        org.junit.jupiter.api.Assertions.assertThrows(PortInUseException.class, () -> identifier.open("other", 1000));
    }

    @Test
    void routesReceivedDataToInputStream() throws Exception {
        SerialPortIdentifier identifier = provider.getPortIdentifier(URI.create("esphome://device-id/main"));
        assertNotNull(identifier);
        SerialPort port = identifier.open("owner", 1000);
        InputStream inputStream = requireNonNull(port.getInputStream());

        provider.handleData(handler, SerialProxyDataReceived.newBuilder().setInstance(0)
                .setData(ByteString.copyFrom(new byte[] { 1, 2, 3 })).build());

        byte[] data = inputStream.readNBytes(3);
        assertArrayEquals(new byte[] { 1, 2, 3 }, data);
    }

    @Test
    void writesOutputDataThroughSerialProxyWriteRequest() throws Exception {
        SerialPortIdentifier identifier = provider.getPortIdentifier(URI.create("esphome://device-id/main"));
        assertNotNull(identifier);
        SerialPort port = identifier.open("owner", 1000);
        OutputStream outputStream = requireNonNull(port.getOutputStream());

        outputStream.write(new byte[] { 4, 5, 6 });

        SerialProxyWriteRequest request = captureLast(SerialProxyWriteRequest.class);
        assertEquals(0, request.getInstance());
        assertArrayEquals(new byte[] { 4, 5, 6 }, request.getData().toByteArray());
    }

    @Test
    void configuresSerialParametersAndFlushes() throws Exception {
        SerialPortIdentifier identifier = provider.getPortIdentifier(URI.create("esphome://device-id/aux"));
        assertNotNull(identifier);
        SerialPort port = identifier.open("owner", 1000);

        port.setFlowControlMode(SerialPort.FLOWCONTROL_RTSCTS_IN);
        port.setSerialPortParams(115200, SerialPort.DATABITS_7, SerialPort.STOPBITS_2, SerialPort.PARITY_EVEN);
        requireNonNull(port.getOutputStream()).flush();

        SerialProxyConfigureRequest configureRequest = captureLast(SerialProxyConfigureRequest.class);
        assertEquals(1, configureRequest.getInstance());
        assertEquals(115200, configureRequest.getBaudrate());
        assertEquals(7, configureRequest.getDataSize());
        assertEquals(2, configureRequest.getStopBits());
        assertEquals(SerialProxyParity.SERIAL_PROXY_PARITY_EVEN, configureRequest.getParity());
        assertTrue(configureRequest.getFlowControl());

        SerialProxyRequest flushRequest = captureLast(SerialProxyRequest.class);
        assertEquals(1, flushRequest.getInstance());
        assertEquals(SerialProxyRequestType.SERIAL_PROXY_REQUEST_TYPE_FLUSH, flushRequest.getType());
    }

    @Test
    void routesModemPinSetAndResponses() throws Exception {
        SerialPortIdentifier identifier = provider.getPortIdentifier(URI.create("esphome://device-id/main"));
        assertNotNull(identifier);
        SerialPort port = identifier.open("owner", 1000);

        port.setRTS(true);
        port.setDTR(true);

        SerialProxySetModemPinsRequest request = captureLast(SerialProxySetModemPinsRequest.class);
        assertEquals(0, request.getInstance());
        assertEquals(3, request.getLineStates());
        assertTrue(port.isRTS());
        assertTrue(port.isDTR());

        provider.handleModemPins(handler,
                SerialProxyGetModemPinsResponse.newBuilder().setInstance(0).setLineStates(0).build());

        assertFalse(port.isRTS());
        assertFalse(port.isDTR());
    }

    private void assertIdentifier(String uri) {
        SerialPortIdentifier identifier = provider.getPortIdentifier(URI.create(uri));

        assertNotNull(identifier, uri);
        assertEquals("esphome://device-id/main", identifier.getName());
    }

    private <T extends GeneratedMessage> T captureLast(Class<T> type) throws Exception {
        ArgumentCaptor<GeneratedMessage> captor = ArgumentCaptor.forClass(GeneratedMessage.class);
        verify(handler, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream().filter(type::isInstance).map(type::cast).reduce((first, second) -> second)
                .orElseThrow();
    }

    private static java.util.List<SerialProxyInfo> proxies() {
        return java.util.List.of(SerialProxyInfo.newBuilder().setName("main").build(),
                SerialProxyInfo.newBuilder().setName("aux").build());
    }
}
