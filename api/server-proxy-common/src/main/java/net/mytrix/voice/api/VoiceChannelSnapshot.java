package net.mytrix.voice.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record VoiceChannelSnapshot(
        VoiceChannelId id,
        boolean active,
        VoiceChannelPolicy policy,
        Set<UUID> members,
        Map<UUID, VoiceCapabilities> capabilities,
        Map<UUID, String> roles,
        Map<String, String> metadata
) {

    public VoiceChannelSnapshot {
        members = Set.copyOf(members == null ? Set.of() : members);
        capabilities = Map.copyOf(capabilities == null ? Map.of() : capabilities);
        roles = Map.copyOf(roles == null ? Map.of() : roles);
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }
}
