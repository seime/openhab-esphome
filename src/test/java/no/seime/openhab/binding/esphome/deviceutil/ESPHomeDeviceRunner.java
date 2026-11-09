package no.seime.openhab.binding.esphome.deviceutil;

import java.io.*;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ESPHomeDeviceRunner {

    private final Logger logger = LoggerFactory.getLogger(ESPHomeDeviceRunner.class);

    /**
     * Root folder for isolated per-run ESPHome working directories. Each emulator instance gets its own copy of the
     * device configuration folder so that concurrently running tests never share an ESPHome build directory
     * (.esphome/build, .esphome/pio_components, .esphome/storage etc).
     */
    private static final Path WORK_ROOT = Paths.get("target", "esphome-devicetests");

    private static final Pattern NAME_PATTERN = Pattern.compile("^\\s{2}name:\\s*(\\S+)\\s*$", Pattern.MULTILINE);

    private final ExecutorService executorService = Executors.newFixedThreadPool(1);
    private final File espDeviceConfigurationYamlFileName;
    private final int apiPort;
    private final String deviceName;
    private final Path workDir;
    private Process emulatorProcess;

    public ESPHomeDeviceRunner(File sourceYamlFile, int apiPort) throws IOException {
        this.apiPort = apiPort;
        this.deviceName = extractDeviceName(sourceYamlFile);
        String baseName = sourceYamlFile.getName().replaceFirst("\\.ya?ml$", "");
        this.workDir = WORK_ROOT.resolve(baseName + "-" + apiPort + "-" + UUID.randomUUID()).toAbsolutePath();
        copyConfigurationFolder(sourceYamlFile.getAbsoluteFile().getParentFile().toPath(), workDir);
        this.espDeviceConfigurationYamlFileName = workDir.resolve(sourceYamlFile.getName()).toFile();
    }

    /**
     * Copy the device configuration folder (yaml files, secrets, external components) to an isolated working folder,
     * skipping any ESPHome build output and python caches.
     */
    private static void copyConfigurationFolder(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String name = dir.getFileName().toString();
                if (!dir.equals(source) && (".esphome".equals(name) || "__pycache__".equals(name))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String extractDeviceName(File yaml) throws IOException {
        String content = Files.readString(yaml.toPath());
        int esphomeIdx = content.indexOf("esphome:");
        if (esphomeIdx < 0) {
            throw new IOException("No 'esphome:' section in " + yaml);
        }
        Matcher m = NAME_PATTERN.matcher(content.substring(esphomeIdx));
        if (!m.find()) {
            throw new IOException("Could not find 'name:' under 'esphome:' in " + yaml);
        }
        return m.group(1);
    }

    public void compileAndRun() throws IOException {

        logger.info("Compiling ESPHome device {} in {}", deviceName, workDir);
        ProcessBuilder compilationBuilder = new ProcessBuilder();
        compilationBuilder.redirectErrorStream(true);
        compilationBuilder.directory(workDir.toFile());
        compilationBuilder.command("esphome", "-s", "api_port", String.valueOf(apiPort), "compile",
                espDeviceConfigurationYamlFileName.getPath());
        Consumer<String> compilationListener = s -> {
            logger.info("{}", s);
        };
        Process compilationProcess = compilationBuilder.start();
        StreamGobbler streamGobblerCompilationProcess = new StreamGobbler(compilationProcess.getInputStream(),
                compilationListener);
        executorService.submit(streamGobblerCompilationProcess);

        try {

            int compilationExitCode = compilationProcess.waitFor();
            if (compilationExitCode != 0) {
                throw new RuntimeException("ESPHome compilation failed with exit code " + compilationExitCode);
            }

            // Now launch the compiled binary
            logger.info("ESPHome compilation completed successfully for {} on port {}, now starting emulator",
                    deviceName, apiPort);

            File parentFile = espDeviceConfigurationYamlFileName.getParentFile();
            File emulatorBinary = new File(parentFile,
                    ".esphome/build/" + deviceName + "/.pioenvs/" + deviceName + "/program");
            if (!emulatorBinary.exists()) {
                throw new RuntimeException("Compiled binary not found in " + emulatorBinary);
            }

            ProcessBuilder emulatorBuilder = new ProcessBuilder(emulatorBinary.toString());
            emulatorBuilder.redirectErrorStream(true);
            emulatorBuilder.directory(workDir.toFile());
            emulatorProcess = emulatorBuilder.start();
            emulatorProcess.onExit().thenRun(() -> {
                logger.info("ESPHome emulator {} exited", deviceName);
            });

            // For some reason cannot get console output from the emulator, so just wait a second before proceeding.
            // Startup is quick
            Thread.sleep(2000);
            logger.info("ESPHome emulator {} started successfully on port {}", deviceName, apiPort);

        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for ESPHome to start", e);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for ESPHome to start", e);

        }
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void shutdown() throws InterruptedException {
        // shutdown the executor service now
        if (emulatorProcess != null) {
            emulatorProcess.destroy();
            if (!emulatorProcess.waitFor(5, TimeUnit.SECONDS)) {
                emulatorProcess.destroyForcibly();
                emulatorProcess.waitFor(5, TimeUnit.SECONDS);
            }
        }
        executorService.shutdown();
        executorService.awaitTermination(5, TimeUnit.SECONDS);
        deleteWorkDir();
    }

    private void deleteWorkDir() {
        try (var paths = Files.walk(workDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    logger.debug("Could not delete {}", p, e);
                }
            });
        } catch (IOException e) {
            logger.warn("Could not clean up ESPHome work dir {}", workDir, e);
        }
    }

    private static class StreamGobbler implements Runnable {
        private final InputStream inputStream;
        private final Consumer<String> consumer;

        public StreamGobbler(InputStream inputStream, Consumer<String> consumer) {
            this.inputStream = inputStream;
            this.consumer = consumer;
        }

        @Override
        public void run() {
            new BufferedReader(new InputStreamReader(inputStream)).lines().forEach(consumer);
        }
    }
}
