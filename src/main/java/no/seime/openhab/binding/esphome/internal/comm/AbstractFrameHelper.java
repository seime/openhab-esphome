package no.seime.openhab.binding.esphome.internal.comm;

import static no.seime.openhab.binding.esphome.internal.comm.ConnectionSelector.READ_BUFFER_SIZE;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.GeneratedMessage;

import no.seime.openhab.binding.esphome.internal.CommunicationListener;

public abstract class AbstractFrameHelper {

    public static final int PROTOCOL_PLAINTEXT = 0x00;
    public static final int PROTOCOL_ENCRYPTED = 0x01;

    protected final Logger logger = LoggerFactory.getLogger(AbstractFrameHelper.class);
    private final MessageTypeToClassConverter messageTypeToClassConverter = new MessageTypeToClassConverter();
    protected CommunicationListener listener;
    protected ByteBuffer internalBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE * 2);
    protected ESPHomeConnection connection;
    protected String logPrefix;

    public AbstractFrameHelper(String logPrefix, CommunicationListener listener) {
        this.logPrefix = logPrefix;
        this.listener = listener;
    }

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
    public abstract ByteBuffer encodeFrame(GeneratedMessage message) throws ProtocolAPIError;

    public void setPacketListener(CommunicationListener listener) {
        this.listener = listener;
    }

    public void close() {
        connection.close();
    }

    protected void processBuffer() throws ProtocolException {
        internalBuffer.limit(internalBuffer.position());
        internalBuffer.position(0);
        if (internalBuffer.remaining() > 2) {
            byte[] headerData = readBytes(3);
            headerReceived(headerData);
        } else {
            internalBuffer.position(internalBuffer.limit());
            internalBuffer.limit(internalBuffer.capacity());
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
        if (internalBuffer.remaining() < numBytes) {
            return new byte[0];
        }
        byte[] data = new byte[numBytes];
        internalBuffer.get(data);
        return data;
    }

    protected void decodeProtoMessage(int messageType, byte[] bytes) {
        logger.trace("[{}] Received packet of type {} with data {}", logPrefix, messageType, bytes);

        try {
            Method parseMethod = messageTypeToClassConverter.getMethod(messageType);
            if (parseMethod != null) {
                GeneratedMessage invoke = (GeneratedMessage) parseMethod.invoke(null, bytes);
                if (invoke != null) {
                    listener.onPacket(invoke);
                } else {
                    logger.warn("[{}] Received null packet of type {}", logPrefix, parseMethod);
                }
            }
        } catch (Exception e) {
            logger.warn("[{}] Error parsing packet", logPrefix, e);
            listener.onParseError(CommunicationError.PACKET_ERROR);
        }
    }

    public void processReceivedData(ByteBuffer newDataBuffer) throws ProtocolException, IOException {
        // Copy new data into buffer
        newDataBuffer.flip();
        internalBuffer.put(newDataBuffer);
        processBuffer();
    }

    public void endOfStream(String message) {
        listener.onEndOfStream(message);
    }

    public void onParseError(CommunicationError error) {
        listener.onParseError(error);
    }

    public void send(GeneratedMessage message) throws ProtocolAPIError {
        if (logger.isDebugEnabled()) {
            // ToString method costs a bit
            logger.debug("[{}] Sending message type {} with content '{}'", logPrefix,
                    message.getClass().getSimpleName(), StringUtils.trimToEmpty(message.toString()));
        }
        try {
            if (connection != null) {
                connection.send(encodeFrame(message));
            } else {
                logger.debug("Connection is null, cannot send message");
            }
        } catch (ProtocolAPIError e) {
            logger.warn("Error sending message", e);
        }
    }
}
