package net.mytrix.voice.api.channel;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable public view of a registered voice channel.
 */
public record VoiceChannelView(
        VoiceChannelId id,
        String displayName,
        VoiceChannelConfig config,
        VoiceChannelOwner owner,
        boolean active,
        boolean closed,
        Set<UUID> members
) {

    public VoiceChannelView {
        Objects.requireNonNull(id, "id");
        displayName = displayName == null || displayName.isBlank() ? id.toString() : displayName;
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(owner, "owner");
        members = Set.copyOf(members == null ? Set.of() : members);
    }
}
