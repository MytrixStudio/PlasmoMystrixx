package net.mytrix.voice.api;

import java.util.UUID;

public record VoiceMembership(
        UUID playerId,
        VoiceChannelId channelId,
        String role,
        boolean canTransmit,
        boolean canReceive,
        VoiceCapabilities capabilities
) {
}
