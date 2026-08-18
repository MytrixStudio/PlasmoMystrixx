package net.mytrix.voice.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record VoiceSourceRequest(
        UUID ownerPlayerId,
        VoiceChannelId channelId,
        Set<UUID> recipients,
        Map<String, String> metadata
) {

    public VoiceSourceRequest {
        recipients = Set.copyOf(recipients == null ? Set.of() : recipients);
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }
}
