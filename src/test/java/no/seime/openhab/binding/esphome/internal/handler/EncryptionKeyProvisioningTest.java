package no.seime.openhab.binding.esphome.internal.handler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import io.esphome.api.DeviceInfoResponse;
import io.esphome.api.HomeassistantActionRequest;
import io.esphome.api.ListEntitiesDoneResponse;
import io.esphome.api.PingRequest;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;

class EncryptionKeyProvisioningTest {

    @Test
    void generatesRandom32ByteKeysThatAreNotTheBootstrapKey() {
        String key = EncryptionKeyProvisioning.generateKey();

        assertEquals(32, Base64.getDecoder().decode(key).length);
        assertNotEquals(EncryptionKeyProvisioning.PROVISIONING_KEY, key);
        assertNotEquals(key, EncryptionKeyProvisioning.generateKey());
    }

    @Test
    void rejectsBootstrapAndInvalidKeys() throws Exception {
        assertThrows(ProtocolAPIError.class,
                () -> EncryptionKeyProvisioning.rejectProvisioningKey(EncryptionKeyProvisioning.PROVISIONING_KEY));
        assertThrows(ProtocolAPIError.class, () -> EncryptionKeyProvisioning.rejectProvisioningKey("not base64!"));

        EncryptionKeyProvisioning.rejectProvisioningKey(EncryptionKeyProvisioning.generateKey());
    }

    @Test
    void allowsOnlyControlMessagesDuringProvisioning() {
        assertTrue(EncryptionKeyProvisioning.isAllowedDuringProvisioning(PingRequest.getDefaultInstance()));
        assertTrue(EncryptionKeyProvisioning.isAllowedDuringProvisioning(DeviceInfoResponse.getDefaultInstance()));
        assertFalse(
                EncryptionKeyProvisioning.isAllowedDuringProvisioning(HomeassistantActionRequest.getDefaultInstance()));
        assertFalse(
                EncryptionKeyProvisioning.isAllowedDuringProvisioning(ListEntitiesDoneResponse.getDefaultInstance()));
    }
}
