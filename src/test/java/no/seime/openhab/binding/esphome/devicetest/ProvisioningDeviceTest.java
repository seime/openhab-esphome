package no.seime.openhab.binding.esphome.devicetest;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;

public class ProvisioningDeviceTest extends AbstractESPHomeDeviceTest {

    @Override
    protected File getEspDeviceConfigurationYamlFileName() {
        return new File("src/test/resources/device_configurations/provisioning.yaml");
    }

    @Test
    public void provisionsKeyAndReconnectsToExchangeStatesAndCommands() {
        deviceConfiguration.encryptionKey = "";
        // Use a real, editable configuration so the saved key is visible to the provider.
        thing.setConfiguration(new Configuration(Map.of("hostname", deviceConfiguration.hostname, "port",
                deviceConfiguration.port, "deviceId", deviceConfiguration.deviceId)));
        when(managedThingProvider.get(thing.getUID())).thenReturn(thing);
        AtomicReference<String> savedKey = new AtomicReference<>();
        doAnswer(invocation -> {
            Thing updated = invocation.getArgument(0);
            Object key = updated.getConfiguration().get("encryptionKey");
            if (key instanceof String encodedKey) {
                assertEquals(32, Base64.getDecoder().decode(encodedKey).length);
                savedKey.set(encodedKey);
            }
            return null;
        }).when(thingHandlerCallback).thingUpdated(any(Thing.class));

        thingHandler.initialize();

        await().atMost(Duration.ofSeconds(30)).until(() -> savedKey.get() != null && thingHandler.isInterrogated());
        assertEquals(savedKey.get(), thing.getConfiguration().get("encryptionKey"));
        verify(thingHandlerCallback, timeout(5000)).statusUpdated(eq(thing),
                argThat(status -> status.getStatus() == ThingStatus.ONLINE));
        ChannelUID channel = new ChannelUID(thing.getUID(), "provisioned_number");
        verify(thingHandlerCallback, timeout(5000).atLeastOnce()).stateUpdated(eq(channel), eq(new DecimalType(10)));
        thingHandler.handleCommand(channel, new DecimalType(42));
        verify(thingHandlerCallback, timeout(5000)).stateUpdated(eq(channel), eq(new DecimalType(42)));
    }
}
