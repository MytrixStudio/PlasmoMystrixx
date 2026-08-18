package net.mytrix.voice.api.channel;

import java.util.Objects;

/**
 * Public owner identity for API-created channels.
 */
public record VoiceChannelOwner(String modId) {

    public VoiceChannelOwner {
        modId = Objects.requireNonNull(modId, "modId").trim();
        if (!modId.matches("[a-z0-9_.-]{2,64}")) {
            throw new IllegalArgumentException("Invalid owner mod id: " + modId);
        }
    }
}
