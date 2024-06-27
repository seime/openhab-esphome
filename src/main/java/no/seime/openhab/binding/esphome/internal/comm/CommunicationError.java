package no.seime.openhab.binding.esphome.internal.comm;

public enum CommunicationError {
    DEVICE_REQUIRES_ENCRYPTION("Device is configured with encrypted api endpoint, but binding isn't using encryption."),
    DEVICE_REQUIRES_PLAINTEXT("Device is configured with plaintext api endpoint, but binding is using encryption."),
    PACKET_ERROR("Error parsing packet"),
    ENCRYPTION_KEY_INVALID("Invalid api encryption key"),
    INVALID_PROTOCOL_PREAMBLE(
            "Invalid protocol preamble - this indicates a new major protocol change has arrived, but this binding does not support it yet"),
    DEVICE_NAME_MISMATCH("ESPHome device reported a different esphome.name than configured for the thing");

    private final String text;

    CommunicationError(String text) {

        this.text = text;
    }

    public String toString() {
        return name() + ": " + text;
    }
}
