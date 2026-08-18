package net.mytrix.voice.api;

import java.util.Map;
import java.util.Set;

public record VoiceSessionSnapshot(
        VoiceSessionId id,
        VoiceSessionState state,
        Set<VoiceChannelId> channels,
        VoiceSessionOptions options,
        Map<String, String> metadata
) {

    public VoiceSessionSnapshot {
        channels = Set.copyOf(channels == null ? Set.of() : channels);
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }
}
