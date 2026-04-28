/**
 * Copyright (c) 2023 Contributors to the Seime Openhab Addons project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package no.seime.openhab.binding.esphome.internal.serial;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.TooManyListenersException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.io.transport.serial.SerialPort;
import org.openhab.core.io.transport.serial.SerialPortEvent;
import org.openhab.core.io.transport.serial.SerialPortEventListener;
import org.openhab.core.io.transport.serial.UnsupportedCommOperationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;

import io.esphome.api.SerialProxyConfigureRequest;
import io.esphome.api.SerialProxyGetModemPinsRequest;
import io.esphome.api.SerialProxyParity;
import io.esphome.api.SerialProxyRequest;
import io.esphome.api.SerialProxyRequestType;
import io.esphome.api.SerialProxySetModemPinsRequest;
import io.esphome.api.SerialProxyWriteRequest;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.serial.ESPHomeSerialPortProvider.ESPHomeSerialProxyDevice;

@NonNullByDefault
class ESPHomeSerialPort implements SerialPort {
    private static final int LINE_STATE_RTS = 1 << 0;
    private static final int LINE_STATE_DTR = 1 << 1;
    private static final int EOF = -1;

    private final Logger logger = LoggerFactory.getLogger(ESPHomeSerialPort.class);
    private final ESPHomeSerialProxyDevice device;
    private final int instance;
    private final String owner;
    private final QueueInputStream inputStream = new QueueInputStream();
    private final OutputStream outputStream = new SerialProxyOutputStream();

    private volatile boolean closed;
    private volatile int baudRate = 9600;
    private volatile int dataBits = DATABITS_8;
    private volatile int stopBits = STOPBITS_1;
    private volatile int parity = PARITY_NONE;
    private volatile int flowControlMode = FLOWCONTROL_NONE;
    private volatile int lineStates;
    private volatile boolean notifyOnDataAvailable;
    private volatile @Nullable SerialPortEventListener listener;

    ESPHomeSerialPort(ESPHomeSerialProxyDevice device, int instance, String owner) {
        this.device = device;
        this.instance = instance;
        this.owner = owner;
        sendRequest(SerialProxyRequestType.SERIAL_PROXY_REQUEST_TYPE_SUBSCRIBE);
        sendGetModemPinsRequest();
    }

    String getOwner() {
        return owner;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        sendRequest(SerialProxyRequestType.SERIAL_PROXY_REQUEST_TYPE_UNSUBSCRIBE);
        inputStream.closeStream();
        device.close(instance, this);
    }

    void closeFromProvider() {
        if (closed) {
            return;
        }
        closed = true;
        inputStream.closeStream();
    }

    @Override
    public void setSerialPortParams(int baudrate, int dataBits, int stopBits, int parity)
            throws UnsupportedCommOperationException {
        validateDataBits(dataBits);
        validateStopBits(stopBits);
        SerialProxyParity serialProxyParity = mapParity(parity);

        this.baudRate = baudrate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity;

        send(SerialProxyConfigureRequest.newBuilder().setInstance(instance).setBaudrate(baudrate)
                .setFlowControl(usesHardwareFlowControl()).setParity(serialProxyParity).setStopBits(stopBits)
                .setDataSize(dataBits).build());
    }

    @Override
    public int getBaudRate() {
        return baudRate;
    }

    @Override
    public int getDataBits() {
        return dataBits;
    }

    @Override
    public int getStopBits() {
        return stopBits;
    }

    @Override
    public int getParity() {
        return parity;
    }

    @Override
    public @Nullable InputStream getInputStream() {
        return inputStream;
    }

    @Override
    public @Nullable OutputStream getOutputStream() {
        return outputStream;
    }

    @Override
    public String getName() {
        return device.uri(instance);
    }

    @Override
    public void addEventListener(SerialPortEventListener listener) throws TooManyListenersException {
        if (this.listener != null) {
            throw new TooManyListenersException();
        }
        this.listener = listener;
    }

    @Override
    public void removeEventListener() {
        listener = null;
    }

    @Override
    public void notifyOnDataAvailable(boolean enable) {
        notifyOnDataAvailable = enable;
    }

    @Override
    public void notifyOnBreakInterrupt(boolean enable) {
    }

    @Override
    public void notifyOnFramingError(boolean enable) {
    }

    @Override
    public void notifyOnOverrunError(boolean enable) {
    }

    @Override
    public void notifyOnParityError(boolean enable) {
    }

    @Override
    public void notifyOnOutputEmpty(boolean enable) {
    }

    @Override
    public void notifyOnCTS(boolean enable) {
    }

    @Override
    public void notifyOnDSR(boolean enable) {
    }

    @Override
    public void notifyOnRingIndicator(boolean enable) {
    }

    @Override
    public void notifyOnCarrierDetect(boolean enable) {
    }

    @Override
    public void enableReceiveTimeout(int timeout) throws IllegalArgumentException {
        if (timeout < 0) {
            throw new IllegalArgumentException("Receive timeout must be greater than or equal to zero");
        }
        inputStream.setReceiveTimeout(timeout);
    }

    @Override
    public void disableReceiveTimeout() {
        inputStream.setReceiveTimeout(-1);
    }

    @Override
    public void setFlowControlMode(int flowcontrol) throws UnsupportedCommOperationException {
        if ((flowcontrol & ~(FLOWCONTROL_RTSCTS_IN | FLOWCONTROL_RTSCTS_OUT)) != 0) {
            throw new UnsupportedCommOperationException();
        }
        flowControlMode = flowcontrol;
        send(SerialProxyConfigureRequest.newBuilder().setInstance(instance).setBaudrate(baudRate)
                .setFlowControl(usesHardwareFlowControl()).setParity(mapParity(parity)).setStopBits(stopBits)
                .setDataSize(dataBits).build());
    }

    @Override
    public int getFlowControlMode() {
        return flowControlMode;
    }

    @Override
    public void enableReceiveThreshold(int i) throws UnsupportedCommOperationException {
        if (i < 0) {
            throw new UnsupportedCommOperationException();
        }
    }

    @Override
    public void setRTS(boolean rts) {
        updateLineState(LINE_STATE_RTS, rts);
    }

    @Override
    public boolean isRTS() {
        return (lineStates & LINE_STATE_RTS) != 0;
    }

    @Override
    public void setDTR(boolean state) {
        updateLineState(LINE_STATE_DTR, state);
    }

    @Override
    public boolean isDTR() {
        return (lineStates & LINE_STATE_DTR) != 0;
    }

    @Override
    public boolean isCTS() {
        sendGetModemPinsRequest();
        return false;
    }

    @Override
    public boolean isDSR() {
        sendGetModemPinsRequest();
        return false;
    }

    @Override
    public boolean isCD() {
        sendGetModemPinsRequest();
        return false;
    }

    @Override
    public boolean isRI() {
        sendGetModemPinsRequest();
        return false;
    }

    @Override
    public void sendBreak(int duration) {
    }

    void handleData(byte[] data) {
        if (closed || data.length == 0) {
            return;
        }
        inputStream.offer(data);
        if (notifyOnDataAvailable) {
            SerialPortEventListener eventListener = listener;
            if (eventListener != null) {
                eventListener.serialEvent(new SimpleSerialPortEvent(SerialPortEvent.DATA_AVAILABLE, true));
            }
        }
    }

    void handleModemPins(int lineStates) {
        this.lineStates = lineStates;
    }

    private void updateLineState(int flag, boolean enabled) {
        if (enabled) {
            lineStates |= flag;
        } else {
            lineStates &= ~flag;
        }
        send(SerialProxySetModemPinsRequest.newBuilder().setInstance(instance).setLineStates(lineStates).build());
    }

    private void sendRequest(SerialProxyRequestType type) {
        send(SerialProxyRequest.newBuilder().setInstance(instance).setType(type).build());
    }

    private void sendGetModemPinsRequest() {
        send(SerialProxyGetModemPinsRequest.newBuilder().setInstance(instance).build());
    }

    private void send(com.google.protobuf.GeneratedMessage message) {
        try {
            device.handler().sendMessage(message);
        } catch (ProtocolAPIError e) {
            logger.warn("Error sending ESPHome serial proxy message for {}: {}", getName(), e.getMessage());
        }
    }

    private boolean usesHardwareFlowControl() {
        return (flowControlMode & (FLOWCONTROL_RTSCTS_IN | FLOWCONTROL_RTSCTS_OUT)) != 0;
    }

    private static void validateDataBits(int dataBits) throws UnsupportedCommOperationException {
        if (dataBits < DATABITS_5 || dataBits > DATABITS_8) {
            throw new UnsupportedCommOperationException();
        }
    }

    private static void validateStopBits(int stopBits) throws UnsupportedCommOperationException {
        if (stopBits != STOPBITS_1 && stopBits != STOPBITS_2) {
            throw new UnsupportedCommOperationException();
        }
    }

    private static SerialProxyParity mapParity(int parity) throws UnsupportedCommOperationException {
        return switch (parity) {
            case PARITY_NONE -> SerialProxyParity.SERIAL_PROXY_PARITY_NONE;
            case PARITY_EVEN -> SerialProxyParity.SERIAL_PROXY_PARITY_EVEN;
            case PARITY_ODD -> SerialProxyParity.SERIAL_PROXY_PARITY_ODD;
            default -> throw new UnsupportedCommOperationException();
        };
    }

    private class SerialProxyOutputStream extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            write(new byte[] { (byte) b });
        }

        @Override
        public void write(byte @Nullable [] b, int off, int len) throws IOException {
            if (closed) {
                throw new IOException("Serial port is closed");
            }
            if (b == null) {
                throw new NullPointerException("b");
            }
            byte[] data = new byte[len];
            System.arraycopy(b, off, data, 0, len);
            send(SerialProxyWriteRequest.newBuilder().setInstance(instance).setData(ByteString.copyFrom(data)).build());
        }

        @Override
        public void flush() {
            sendRequest(SerialProxyRequestType.SERIAL_PROXY_REQUEST_TYPE_FLUSH);
        }
    }

    private static class QueueInputStream extends InputStream {
        private final LinkedBlockingQueue<Integer> queue = new LinkedBlockingQueue<>();
        private volatile int receiveTimeout = -1;
        private volatile boolean closed;

        @Override
        public int read() throws IOException {
            if (closed && queue.isEmpty()) {
                return EOF;
            }
            try {
                Integer value = receiveTimeout >= 0 ? queue.poll(receiveTimeout, TimeUnit.MILLISECONDS) : queue.take();
                if (value == null) {
                    return EOF;
                }
                return value;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for serial data", e);
            }
        }

        @Override
        public int read(byte @Nullable [] b, int off, int len) throws IOException {
            if (b == null) {
                throw new NullPointerException("b");
            }
            if (len == 0) {
                return 0;
            }
            int first = read();
            if (first == EOF) {
                return EOF;
            }
            b[off] = (byte) first;
            int count = 1;
            while (count < len) {
                Integer value = queue.poll();
                if (value == null || value == EOF) {
                    break;
                }
                b[off + count++] = (byte) (int) value;
            }
            return count;
        }

        @Override
        public int available() {
            return queue.size();
        }

        void offer(byte[] data) {
            for (byte value : data) {
                queue.offer(Byte.toUnsignedInt(value));
            }
        }

        void setReceiveTimeout(int receiveTimeout) {
            this.receiveTimeout = receiveTimeout;
        }

        void closeStream() {
            closed = true;
            queue.offer(EOF);
        }
    }

    private record SimpleSerialPortEvent(int eventType, boolean newValue) implements SerialPortEvent {
        @Override
        public int getEventType() {
            return eventType;
        }

        @Override
        public boolean getNewValue() {
            return newValue;
        }
    }
}
