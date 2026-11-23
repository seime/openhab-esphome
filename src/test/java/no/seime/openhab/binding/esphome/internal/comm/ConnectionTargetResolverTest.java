package no.seime.openhab.binding.esphome.internal.comm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import no.seime.openhab.binding.esphome.internal.comm.ConnectionTargetResolver.ResolvedConnectionTarget;
import no.seime.openhab.binding.esphome.internal.discovery.ESPHomeMDNSHostnameResolver;

@ExtendWith(MockitoExtension.class)
class ConnectionTargetResolverTest {

    @Mock
    private ESPHomeMDNSHostnameResolver mdnsHostnameResolver;

    private ConnectionTargetResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ConnectionTargetResolver(mdnsHostnameResolver);
    }

    @Test
    void usesMdnsCacheBeforeDns() throws Exception {
        when(mdnsHostnameResolver.resolve("device.local")).thenReturn(Optional.of("192.0.2.10"));

        ResolvedConnectionTarget target = resolver.resolve("device.local", null, "device");

        assertEquals("192.0.2.10", target.connectHost());
        assertEquals("192.0.2.10", target.ipAddress());
        assertEquals("device.local", target.statusTarget());
        assertTrue(target.cacheLastKnownIpAddress());
    }

    @Test
    void fallsBackToCachedIpAddressWhenHostnameResolutionFails() throws Exception {
        ResolvedConnectionTarget target = resolver.resolve("device.invalid", "127.0.0.1", "device");

        assertEquals("127.0.0.1", target.connectHost());
        assertEquals("127.0.0.1", target.ipAddress());
    }

    @Test
    void fallsBackToTrimmedCachedIpAddressWhenHostnameResolutionFails() throws Exception {
        ResolvedConnectionTarget target = resolver.resolve("device.invalid", " 127.0.0.1 ", "device");

        assertEquals("127.0.0.1", target.connectHost());
        assertEquals("127.0.0.1", target.ipAddress());
    }

    @Test
    void throwsWhenHostnameResolutionFailsWithoutCachedIpAddress() {
        ProtocolAPIError error = assertThrows(ProtocolAPIError.class,
                () -> resolver.resolve("device.invalid", null, "device"));

        assertEquals("Failed to resolve hostname 'device.invalid'", error.getMessage());
    }

    @Test
    void throwsWhenHostnameResolutionFailsWithInvalidCachedIpAddress() {
        ProtocolAPIError error = assertThrows(ProtocolAPIError.class,
                () -> resolver.resolve("device.invalid", " not-an-ip ", "device"));

        assertEquals("Failed to resolve hostname 'device.invalid'", error.getMessage());
    }

    @Test
    void doesNotUseCachedIpAddressWhenConfiguredHostnameIsLiteralIp() throws Exception {
        ResolvedConnectionTarget target = resolver.resolve("192.0.2.55", "127.0.0.1", "device");

        assertEquals("192.0.2.55", target.connectHost());
        assertEquals("192.0.2.55", target.ipAddress());
        assertFalse(target.cacheLastKnownIpAddress());
    }
}
