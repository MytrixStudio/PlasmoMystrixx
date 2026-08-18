package net.mytrix.voice.api;

import java.util.Objects;
import java.util.UUID;

public record VoiceRestrictionHandle(UUID id, String ownerNamespace) {

    public VoiceRestrictionHandle {
        Objects.requireNonNull(id, "id");
        VoiceIdentifierValidator.validateNamespace(ownerNamespace);
    }

    public static VoiceRestrictionHandle create(String ownerNamespace) {
        return new VoiceRestrictionHandle(UUID.randomUUID(), ownerNamespace);
    }
}
