package net.mytrix.voice.api;

import java.util.Set;
import java.util.UUID;

public record VoicePlayerSnapshot(
        UUID playerId,
        boolean connected,
        Set<VoiceChannelId> channels,
        Set<VoiceRestrictionSnapshot> restrictions,
        boolean canTransmit,
        boolean canReceive,
        boolean proximityDisabled
) {

    public VoicePlayerSnapshot {
        channels = Set.copyOf(channels == null ? Set.of() : channels);
        restrictions = Set.copyOf(restrictions == null ? Set.of() : restrictions);
    }
}
