package no.seime.openhab.binding.esphome.internal.comm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConnectionSelector {

    private final Logger logger = LoggerFactory.getLogger(ConnectionSelector.class);

    private final Selector selector;
    private final Map<SocketChannel, AbstractFrameHelper> connectionMap = new ConcurrentHashMap<>();
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
                    selector.select(1000);
                    // token representing the registration of a SelectableChannel with a Selector
                    Set<SelectionKey> keys = selector.selectedKeys();
                    logger.trace("Selected keys: {}", keys.size());
                    Iterator<SelectionKey> keyIterator = keys.iterator();
                    while (keyIterator.hasNext()) {
                        SelectionKey readyKey = keyIterator.next();
                        processKey(readyKey);
                        keyIterator.remove();
                    }
                } catch (ClosedSelectorException e) {
                    logger.debug("Selector closed");
                    keepRunning = false;
                } catch (Exception e) {
                    logger.warn("Error while selecting", e);
                    keepRunning = false;
                }
            }
            logger.debug(
                    "Selector thread stopped. This should only happen on bundle stop, not during regular operation. See previous log statements for more information.");
        });
        selectorThread.setName("ESPHome Reader");
        selectorThread.start();
    }

    private void processKey(SelectionKey readyKey) {
        AbstractFrameHelper frameHelper = (AbstractFrameHelper) readyKey.attachment();
        logger.trace("Processing key {}", readyKey);
        // Tests whether this key's channel is ready to accept a new socket connection
        try {
            if (readyKey.isReadable()) {
                SocketChannel channel = (SocketChannel) readyKey.channel();
                ByteBuffer buffer = ByteBuffer.allocate(128);
                int read = channel.read(buffer);
                if (read == -1) {
                    frameHelper.endOfStream();
                } else {
                    processReceivedData(frameHelper, buffer, channel);
                }

            } else {
                logger.trace("Key not readable");
            }
        } catch (IOException e) {
            logger.debug("Socket exception", e);
            frameHelper.endOfStream();
        }
    }

    private void processReceivedData(AbstractFrameHelper frameHelper, ByteBuffer buffer, SocketChannel channel)
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

    public void register(SocketChannel socketChannel, AbstractFrameHelper frameHelper) {
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
