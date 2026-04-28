package no.seime.openhab.binding.esphome.devicetest;

import static java.util.Objects.requireNonNull;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openhab.core.io.transport.serial.SerialPort;
import org.openhab.core.io.transport.serial.SerialPortIdentifier;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SerialProxyDeviceTest extends AbstractESPHomeDeviceTest {
    private PseudoTerminalDevice pseudoTerminalDevice;

    protected File getEspDeviceConfigurationYamlFileName() throws Exception {
        Path uartPath = Path.of("/tmp", "openhab-esphome-serial-proxy-uart");
        Path peerPath = Path.of("/tmp", "openhab-esphome-serial-proxy-peer");
        pseudoTerminalDevice = new PseudoTerminalDevice(uartPath, peerPath);
        pseudoTerminalDevice.start();

        return writeDeviceConfiguration(uartPath);
    }

    @AfterEach
    void shutDown() throws Exception {
        super.shutdown();
        if (pseudoTerminalDevice != null) {
            pseudoTerminalDevice.close();
        }
    }

    @Test
    void writesSerialDataToVirtualEsphomeDevice() throws Exception {
        thingHandler.initialize();
        await().atMost(Duration.ofSeconds(30)).until(thingHandler::isInterrogated);

        SerialPortIdentifier identifier = serialPortProvider
                .getPortIdentifier(URI.create("esphome://localhost:" + thingHandler.getPort() + "/test-uart"));
        assertNotNull(identifier);

        try (SerialPort port = identifier.open("test", 5000)) {
            port.setSerialPortParams(9600, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);

            byte[] bindingToDevice = "openhab-to-esphome".getBytes(StandardCharsets.UTF_8);
            requireNonNull(port.getOutputStream()).write(bindingToDevice);
            assertArrayEquals(bindingToDevice, pseudoTerminalDevice.read(bindingToDevice.length));
        }
    }

    private static File writeDeviceConfiguration(Path uartPath) throws IOException {
        Path directory = Path.of("target", "serial-proxy-device-test");
        Files.createDirectories(directory);
        Files.copy(Path.of("src/test/resources/device_configurations/secrets.yaml"), directory.resolve("secrets.yaml"),
                StandardCopyOption.REPLACE_EXISTING);
        Path yamlFile = directory.resolve("serial_proxy.yaml");
        Files.writeString(yamlFile, """
                esphome:
                  name: virtual_serial_proxy

                host:
                  mac_address: "06:35:69:ab:f6:79"

                logger:
                  level: VERBOSE

                api:
                  port: ${api_port}
                  encryption:
                    key: !secret emulator_encryption_key

                uart:
                  id: test_uart
                  port: %s
                  baud_rate: 9600

                serial_proxy:
                  name: test-uart
                  uart_id: test_uart
                  port_type: TTL
                """.formatted(uartPath.toString()));
        return yamlFile.toFile();
    }

    private static class PseudoTerminalDevice implements AutoCloseable {
        private final Path linkPath;
        private final Path peerPath;
        private Process process;
        private RandomAccessFile peer;

        PseudoTerminalDevice(Path linkPath, Path peerPath) {
            this.linkPath = linkPath;
            this.peerPath = peerPath;
        }

        void start() throws Exception {
            Files.deleteIfExists(linkPath);
            Files.deleteIfExists(peerPath);
            ProcessBuilder builder = new ProcessBuilder("socat", "-d", "-d",
                    "PTY,raw,echo=0,link=" + linkPath.toString(), "PTY,raw,echo=0,link=" + peerPath.toString());
            builder.redirectErrorStream(true);
            process = builder.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> Files.exists(linkPath) && Files.exists(peerPath));
            peer = new RandomAccessFile(peerPath.toFile(), "rw");
        }

        byte[] read(int length) throws IOException {
            byte[] data = new byte[length];
            int offset = 0;
            while (offset < length) {
                int read = peer.read(data, offset, length - offset);
                if (read == -1) {
                    throw new IOException("Pseudo-terminal closed before reading " + length + " bytes");
                }
                offset += read;
            }
            return data;
        }

        @Override
        public void close() throws Exception {
            Files.deleteIfExists(linkPath);
            Files.deleteIfExists(peerPath);
            if (peer != null) {
                peer.close();
            }
            if (process != null) {
                process.destroy();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
