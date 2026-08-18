package net.mytrix.voice.api.player;

import net.mytrix.voice.api.channel.VoiceChannelId;

import java.util.Set;
import java.util.UUID;

/**
 * Immutable public view of a player's voice state.
 */
public record VoicePlayerView(
        UUID playerId,
        boolean voiceEnabled,
        boolean muted,
        boolean deafened,
        boolean speaking,
        Set<VoiceChannelId> activeChannels
) {

    public VoicePlayerView {
        java.util.Objects.requireNonNull(playerId, "playerId");
        activeChannels = Set.copyOf(activeChannels == null ? Set.of() : activeChannels);
    }
}
