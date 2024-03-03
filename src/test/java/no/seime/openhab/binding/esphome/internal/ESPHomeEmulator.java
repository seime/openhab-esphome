package no.seime.openhab.binding.esphome.internal;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.GeneratedMessageV3;

import no.seime.openhab.binding.esphome.internal.comm.AbstractFrameHelper;
import no.seime.openhab.binding.esphome.internal.comm.CommunicationError;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolException;

public class ESPHomeEmulator {

    private final Logger logger = LoggerFactory.getLogger(ESPHomeEmulator.class);

    private final InetSocketAddress listenAddress;
    private final AbstractFrameHelper frameHelper;
    private boolean keepRunning = true;

    private boolean ready = false;

    public boolean isReady() {
        return ready;
    }

    private Selector selector;

    private SocketChannel channel;

    public ESPHomeEmulator(InetSocketAddress listenAddress, AbstractFrameHelper frameHelper) {
        this.listenAddress = listenAddress;
        this.frameHelper = frameHelper;
    }

    public void start() {

        Thread serverThread = new Thread(() -> {
            try {

                selector = Selector.open(); // selector is open here
                ServerSocketChannel serverChannel = ServerSocketChannel.open();
                serverChannel.bind(listenAddress);
                serverChannel.configureBlocking(false);

                SelectionKey selectKy = serverChannel.register(selector, SelectionKey.OP_ACCEPT, null);

                ready = true;

                while (keepRunning) {
                    selector.select();
                    Set<SelectionKey> readyKeys = selector.selectedKeys();
                    Iterator<SelectionKey> iterator = readyKeys.iterator();
                    while (iterator.hasNext()) {
                        SelectionKey myKey = iterator.next();
                        // NB: only single channel supported
                        if (myKey.isAcceptable() && channel == null) {
                            logger.debug("Accepting connection");
                            channel = serverChannel.accept();
                            channel.configureBlocking(false);
                            channel.register(selector, SelectionKey.OP_READ);

                            logger.debug("Connection Accepted: " + channel.getLocalAddress() + "\n");

                        } else if (myKey.isReadable()) {
                            logger.debug("Data available");
                            channel = (SocketChannel) myKey.channel();

                            ByteBuffer buffer = ByteBuffer.allocate(256);
                            int numBytes = channel.read(buffer);
                            if (numBytes > 0) {
                                try {
                                    frameHelper.processReceivedData(buffer);
                                } catch (ProtocolException e) {
                                    channel.close();
                                    frameHelper.onParseError(CommunicationError.PACKET_ERROR);
                                }
                            } else {
                                logger.debug("No data");
                            }

                        }
                        iterator.remove();
                    }
                }
            } catch (IOException e) {
                logger.debug("Error in server", e);
            }

        });
        serverThread.setName("ESPHome emulator at " + listenAddress);
        serverThread.start();
    }

    public void stop() throws IOException {

        keepRunning = false;
        selector.wakeup();
        selector.close();
    }

    public void sendPacket(GeneratedMessageV3 message) throws IOException, ProtocolAPIError {
        ByteBuffer buffer = frameHelper.encodeFrame(message);

        while (buffer.hasRemaining()) {
            logger.trace("Writing data");
            channel.write(buffer);
        }
    }

    public void setPacketListener(CommunicationListener communicationListener) {
        frameHelper.setPacketListener(communicationListener);
    }
}
