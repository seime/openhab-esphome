package no.seime.openhab.binding.esphome.internal.comm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConnectionSelector {

    public static final int READ_BUFFER_SIZE = 10 * 2048;
    private final Logger logger = LoggerFactory.getLogger(ConnectionSelector.class);

    private final Selector selector;
    private final Map<SocketChannel, EncryptedFrameHelper> connectionMap = new ConcurrentHashMap<>();
    private boolean keepRunning = true;
    private boolean selectorOpen;

    public ConnectionSelector() throws IOException {
        selector = Selector.open();
        selectorOpen = true;
    }

    public void start() {

        Thread selectorThread = new Thread(() -> {
            logger.debug("Starting selector thread");
            while (keepRunning) {
                try {
                    selector.select(10000);
                    // token representing the registration of a SelectableChannel with a Selector
                    Set<SelectionKey> keys = selector.selectedKeys();
                    logger.trace("Num selected keys: {}", keys.size());
                    Iterator<SelectionKey> keyIterator = keys.iterator();
                    while (keyIterator.hasNext()) {
                        SelectionKey readyKey = keyIterator.next();
                        processKey(readyKey);
                    }
                    keys.clear();
                } catch (ClosedSelectorException e) {
                    logger.debug("Selector closed, stopping thread");
                    keepRunning = false;
                } catch (Exception e) {
                    logger.error("Error while selecting, stopping thread", e);
                    keepRunning = false;
                }
            }
            logger.debug(
                    "Selector thread stopped. This should only happen on bundle stop, not during regular operation. See previous log statements for more information.");
        });
        selectorThread.setName("ESPHome Socket Reader");
        selectorThread.start();
    }

    private void processKey(SelectionKey readyKey) {
        EncryptedFrameHelper frameHelper = (EncryptedFrameHelper) readyKey.attachment();
        logger.trace("Processing key readable={}", readyKey.isReadable());
        // Tests whether this key's channel is ready to accept a new socket connection
        try {
            if (readyKey.isReadable()) {
                SocketChannel channel = (SocketChannel) readyKey.channel();
                ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
                int read = channel.read(buffer);
                if (read == -1) {
                    logger.debug("End of stream, closing");
                    channel.keyFor(selector).cancel();
                    frameHelper.endOfStream("No more bytes available in connection stream");
                } else {
                    if (read == READ_BUFFER_SIZE) {
                        logger.warn(
                                "Socket read provided more data than buffer capacity of {}. Buffer capacity should be increased. Things still work, but performance is suboptimal. File a report on github to the developer",
                                READ_BUFFER_SIZE);
                    }

                    processReceivedData(frameHelper, buffer, channel);
                }
            } else {
                logger.trace("Key not readable");
            }
        } catch (IOException | CancelledKeyException e) {
            logger.debug("Socket exception", e);
            frameHelper.endOfStream(e.getMessage());
        } catch (Exception e) {
            logger.warn("Error processing key", e);
        }
    }

    private void processReceivedData(EncryptedFrameHelper frameHelper, ByteBuffer buffer, SocketChannel channel)
            throws IOException {
        try {
            logger.trace("Received data");
            frameHelper.processReceivedData(buffer);
        } catch (Exception e) {
            channel.close();
            frameHelper.onParseError(CommunicationError.PACKET_ERROR);
        }
    }

    public void stop() {
        if (selectorOpen) {
            keepRunning = false;
            selector.wakeup();
            try {
                selector.close();
            } catch (IOException e) {
                logger.debug("Error closing selector", e);
            }
            selectorOpen = false;
        }
    }

    public void register(SocketChannel socketChannel, EncryptedFrameHelper frameHelper) {
        connectionMap.put(socketChannel, frameHelper);
        try {
            SelectionKey key = socketChannel.register(selector, SelectionKey.OP_READ);
            key.attach(frameHelper);
            selector.wakeup();
        } catch (IOException e) {
            logger.warn("Error while registering channel", e);
        }
    }

    public void unregister(SocketChannel socketChannel) {
        connectionMap.remove(socketChannel);

        try {
            socketChannel.close();
        } catch (IOException e) {
            logger.warn("Error while closing channel", e);
        }
    }
}
