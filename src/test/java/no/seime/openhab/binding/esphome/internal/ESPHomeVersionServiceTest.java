package no.seime.openhab.binding.esphome.internal;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class ESPHomeVersionServiceTest {

    @Test
    void testFetchVersion() {

        ESPHomeVersionService versionService = new ESPHomeVersionService(
                Mockito.mock(MonitoredCompositeExecutorService.class));
        versionService.fetchVersion();
        assertNotNull(versionService.getLatestVersion());
    }
}
