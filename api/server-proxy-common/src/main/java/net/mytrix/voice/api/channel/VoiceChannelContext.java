package net.mytrix.voice.api.channel;

import java.util.Objects;

/**
 * Stable channel context passed to external callbacks.
 */
public record VoiceChannelContext(VoiceChannelId id, String displayName, VoiceChannelConfig config) {

    public VoiceChannelContext {
        Objects.requireNonNull(id, "id");
        displayName = displayName == null || displayName.isBlank() ? id.toString() : displayName;
        Objects.requireNonNull(config, "config");
    }
}
