package no.seime.openhab.binding.esphome.internal.comm;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.GeneratedMessageV3;

import no.seime.openhab.binding.esphome.internal.CommunicationListener;

public abstract class AbstractFrameHelper {

    public static final int PROTOCOL_PLAINTEXT = 0x00;
    public static final int PROTOCOL_ENCRYPTED = 0x01;

    protected final Logger logger = LoggerFactory.getLogger(AbstractFrameHelper.class);
    private final MessageTypeToClassConverter messageTypeToClassConverter = new MessageTypeToClassConverter();
    protected CommunicationListener listener;
    protected ByteBuffer buffer = ByteBuffer.allocate(1024);
    protected ESPHomeConnection connection;

    protected static byte[] concatArrays(byte[] length, byte[] additionalLength) {
        byte[] result = new byte[length.length + additionalLength.length];
        System.arraycopy(length, 0, result, 0, length.length);
        System.arraycopy(additionalLength, 0, result, length.length, additionalLength.length);
        return result;
    }

    /**
     * Initiate connection to the ESPHome device. When connection is ready for use, call onConnect on the listener.
     * 
     * @param espHomeAddress
     * @throws ProtocolException
     */
    public abstract void connect(InetSocketAddress espHomeAddress) throws ProtocolException;

    /**
     * Encode the given message as a ready to send frame
     * 
     * @param message message to encode
     * @return byte buffer with the encoded frame
     * @throws ProtocolAPIError
     */
    public abstract ByteBuffer encodeFrame(GeneratedMessageV3 message) throws ProtocolAPIError;

    public void setPacketListener(CommunicationListener listener) {
        this.listener = listener;
    }

    public void close() {
        connection.close();
    }

    protected void processBuffer() throws ProtocolException {
        buffer.limit(buffer.position());
        buffer.position(0);
        if (buffer.remaining() > 2) {
            byte[] headerData = readBytes(3);
            headerReceived(headerData);
        } else {
            buffer.position(buffer.limit());
            buffer.limit(buffer.capacity());
        }
    }

    /**
     * Called when the header of a new packet has been received
     * 
     * @param headerData first 3 bytes of the available data
     * @throws ProtocolException
     */
    protected abstract void headerReceived(byte[] headerData) throws ProtocolException;

    protected byte[] readBytes(int numBytes) {
        if (buffer.remaining() < numBytes) {
            return new byte[0];
        }
        byte[] data = new byte[numBytes];
        buffer.get(data);
        return data;
    }

    protected void decodeProtoMessage(int messageType, byte[] bytes) {
        logger.debug("Received packet of type {} with data {}", messageType, bytes);

        try {
            Method parseMethod = messageTypeToClassConverter.getMethod(messageType);
            if (parseMethod != null) {
                GeneratedMessageV3 invoke = (GeneratedMessageV3) parseMethod.invoke(null, bytes);
                if (invoke != null) {
                    listener.onPacket(invoke);
                } else {
                    logger.warn("Received null packet of type {}", parseMethod);
                }
            }
        } catch (Exception e) {
            logger.warn("Error parsing packet", e);
            listener.onParseError(CommunicationError.PACKET_ERROR);
        }
    }

    public void processReceivedData(ByteBuffer newDataBuffer) throws ProtocolException, IOException {
        // Copy new data into buffer
        newDataBuffer.flip();
        buffer.put(newDataBuffer);
        processBuffer();
    }

    public void endOfStream() {
        listener.onEndOfStream();
    }

    public void onParseError(CommunicationError error) {
        listener.onParseError(error);
    }

    public void send(GeneratedMessageV3 message) throws ProtocolAPIError {
        if (connection != null) {
            connection.send(encodeFrame(message));
        } else {
            logger.debug("Connection is null, cannot send message");
        }
    }
}
