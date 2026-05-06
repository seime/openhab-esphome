package no.seime.openhab.binding.esphome.internal;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class ESPHomeVersionServiceTest {

    @Test
    void testFetchVersion() {

        ESPHomeVersionService versionService = new ESPHomeVersionService(Mockito.mock(ScheduledExecutorService.class));
        versionService.fetchVersion();
        assertNotNull(versionService.getLatestVersion());
    }
}
