package net.mytrix.voice.api.channel;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable player context passed to permission and membership callbacks.
 */
public record VoicePlayerContext(UUID playerId) {

    public VoicePlayerContext {
        Objects.requireNonNull(playerId, "playerId");
    }
}
