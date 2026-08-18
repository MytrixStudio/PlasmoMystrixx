package net.mytrix.voice.api.player;

import net.mytrix.voice.api.channel.VoiceChannelId;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only service for voice player state.
 */
public interface VoicePlayerService {

    Optional<VoicePlayerView> find(UUID playerId);

    boolean isVoiceAvailable(UUID playerId);

    boolean isSpeaking(UUID playerId);

    Set<VoiceChannelId> channelsOf(UUID playerId);
}
