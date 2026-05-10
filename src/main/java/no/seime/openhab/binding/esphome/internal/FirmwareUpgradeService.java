package no.seime.openhab.binding.esphome.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FirmwareUpgradeService {

    private final Logger logger = LoggerFactory.getLogger(FirmwareUpgradeService.class);

    private String bindingPropertyEspHomeExecutable;
    private String bindingPropertyEspHomeUpgradeExecutable;

    public @Nullable String getInstalledVersion() {
        if (bindingPropertyEspHomeExecutable == null) {
            return null;
        }

        try {
            Process process = new ProcessBuilder(bindingPropertyEspHomeExecutable, "version").redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Version: ")) {
                        return line.substring("Version: ".length()).trim();
                    }
                }
            }
            process.waitFor();
        } catch (IOException e) {
            logger.warn("Failed to run esphome version command", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    public synchronized boolean upgradeLocalInstallation() {
        if (bindingPropertyEspHomeUpgradeExecutable == null) {
            logger.warn("No esphome upgrade executable configured");
            return false;
        }
        try {
            Process process = new ProcessBuilder(bindingPropertyEspHomeUpgradeExecutable).redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("esphome upgrade: {}", line);
                }
            }
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return true;
            } else {
                logger.warn("esphome upgrade command exited with code {}", exitCode);
                return false;
            }
        } catch (IOException e) {
            logger.warn("Failed to run esphome upgrade command", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    public boolean upgradeDevice(String configFilePath) {
        if (bindingPropertyEspHomeExecutable == null) {
            logger.warn("No esphome executable configured");
            return false;
        }
        try {
            Process process = new ProcessBuilder(bindingPropertyEspHomeExecutable, "run", configFilePath, "--no-logs")
                    .redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.debug("esphome run: {}", line);
                }
            }
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                logger.warn("esphome run command timed out after 5 minutes for config: {}", configFilePath);
                return false;
            }
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return true;
            } else {
                logger.warn("esphome run command exited with code {} for config: {}", exitCode, configFilePath);
                return false;
            }
        } catch (IOException e) {
            logger.warn("Failed to run esphome run command for config: {}", configFilePath, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    public void setBindingPropertyEspHomeExecutable(@Nullable String bindingPropertyEspHomeExecutable) {
        this.bindingPropertyEspHomeExecutable = bindingPropertyEspHomeExecutable;
    }

    public void setBindingPropertyEspHomeUpgradeExecutable(@Nullable String bindingPropertyEspHomeUpgradeExecutable) {
        this.bindingPropertyEspHomeUpgradeExecutable = bindingPropertyEspHomeUpgradeExecutable;
    }
}
