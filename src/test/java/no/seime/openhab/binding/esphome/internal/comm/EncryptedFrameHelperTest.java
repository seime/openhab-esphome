package no.seime.openhab.binding.esphome.internal.comm;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessage;
import com.jano7.executor.KeySequentialExecutor;
import com.southernstorm.noise.protocol.CipherState;
import com.southernstorm.noise.protocol.CipherStatePair;
import com.southernstorm.noise.protocol.Noise;

import io.esphome.api.PingResponse;
import io.esphome.api.SerialProxyDataReceived;
import io.esphome.api.SerialProxyGetModemPinsResponse;
import io.esphome.api.SerialProxyRequestResponse;
import io.esphome.api.SerialProxyRequestType;
import no.seime.openhab.binding.esphome.internal.CommunicationListener;

class EncryptedFrameHelperTest {
    static Stream<Arguments> incomingMessages() {
        return Stream.of(Arguments.of(8, PingResponse.getDefaultInstance()),
                Arguments.of(139,
                        SerialProxyDataReceived.newBuilder().setInstance(1).setData(ByteString.copyFromUtf8("crazy"))
                                .build()),
                Arguments.of(143, SerialProxyGetModemPinsResponse.newBuilder().setLineStates(3).build()),
                Arguments.of(147, SerialProxyRequestResponse.newBuilder()
                        .setType(SerialProxyRequestType.SERIAL_PROXY_REQUEST_TYPE_FLUSH).build()));
    }

    @ParameterizedTest
    @MethodSource("incomingMessages")
    void dispatchesIncomingEncryptedMessages(int messageType, GeneratedMessage expected) throws Exception {
        CommunicationListener listener = mock(CommunicationListener.class);
        EncryptedFrameHelper helper = new EncryptedFrameHelper(mock(ConnectionSelector.class), listener, "", null,
                "test", new KeySequentialExecutor(Runnable::run));

        CipherState sender = Noise.createCipher("ChaChaPoly");
        CipherState receiver = Noise.createCipher("ChaChaPoly");
        try {
            byte[] key = new byte[32];
            sender.initializeKey(key, 0);
            receiver.initializeKey(key, 0);
            // Start after the handshake so this tests the production encrypted receive path.
            Field stateField = EncryptedFrameHelper.class.getDeclaredField("state");
            Object ready = Arrays.stream(stateField.getType().getEnumConstants())
                    .filter(value -> value.toString().equals("READY")).findFirst().orElseThrow();
            setField(helper, "state", ready);
            setField(helper, "cipherStatePair", new CipherStatePair(sender, receiver));

            byte[] payload = expected.toByteArray();
            byte[] plaintext = ByteBuffer.allocate(4 + payload.length).putShort((short) messageType)
                    .putShort((short) payload.length).put(payload).array();
            byte[] ciphertext = new byte[plaintext.length + sender.getMACLength()];
            int length = sender.encryptWithAd(null, plaintext, 0, ciphertext, 0, plaintext.length);
            ByteBuffer frame = ByteBuffer.allocate(3 + length).put((byte) EncryptedFrameHelper.PROTOCOL_ENCRYPTED)
                    .putShort((short) length).put(ciphertext, 0, length);

            helper.processReceivedData(frame);

            verify(listener).onPacket(expected);
            verifyNoMoreInteractions(listener);
        } finally {
            sender.destroy();
            receiver.destroy();
        }
    }

    private static void setField(EncryptedFrameHelper helper, String name, Object value) throws Exception {
        Field field = EncryptedFrameHelper.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(helper, value);
    }
}
