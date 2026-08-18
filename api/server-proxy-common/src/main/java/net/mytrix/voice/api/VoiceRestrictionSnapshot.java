package net.mytrix.voice.api;

import java.time.Instant;
import java.util.Optional;

public record VoiceRestrictionSnapshot(
        VoiceRestrictionHandle handle,
        String ownerNamespace,
        String reason,
        RestrictionTarget target,
        VoiceRestrictionType type,
        int priority,
        Optional<Instant> expiresAt
) {

    public VoiceRestrictionSnapshot {
        expiresAt = expiresAt == null ? Optional.empty() : expiresAt;
    }
}
