package no.seime.openhab.binding.esphome.internal.handler;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openhab.core.storage.Storage;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.thing.type.ChannelTypeBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;

class ESPChannelTypeProviderTest {
    @Test
    void cleanupPreservesOtherThingsAndPendingInterrogations() {
        StorageService storage = mock(StorageService.class);
        when(storage.getStorage(anyString(), any())).thenReturn(mock(Storage.class));
        ThingRegistry registry = mock(ThingRegistry.class);
        ESPChannelTypeProvider provider = spy(new ESPChannelTypeProvider(storage, registry));
        ThingUID first = new ThingUID("esphome:device:first");
        ThingUID second = new ThingUID("esphome:device:second");
        ChannelType shared = ChannelTypeBuilder.state(new ChannelTypeUID("esphome:shared"), "Shared", "Number").build();
        ChannelType pending = ChannelTypeBuilder.state(new ChannelTypeUID("esphome:pending"), "Pending", "Number")
                .build();
        ChannelType legacy = ChannelTypeBuilder.state(new ChannelTypeUID("esphome:first_old"), "Old", "Number").build();
        doReturn(List.of(shared, pending, legacy)).when(provider).getChannelTypes(null);
        doNothing().when(provider).removeChannelType(any());
        Thing other = mock(Thing.class);
        when(other.getUID()).thenReturn(second);
        when(other.getChannels()).thenReturn(List.of(
                ChannelBuilder.create(new ChannelUID(second, "value"), "Number").withType(shared.getUID()).build()));
        when(registry.getAll()).thenReturn(List.of(other));
        provider.addChannelType(second, pending);
        provider.removeChannelTypesForThing(first);
        verify(provider, never()).removeChannelType(shared.getUID());
        verify(provider, never()).removeChannelType(pending.getUID());
        verify(provider).removeChannelType(legacy.getUID());

        provider.discardPendingTypes(second);
        when(registry.getAll()).thenReturn(List.of());
        provider.removeOrphanedChannelTypesForThing(first, Set.of());
        verify(provider).removeChannelType(shared.getUID());
        verify(provider).removeChannelType(pending.getUID());
    }
}
