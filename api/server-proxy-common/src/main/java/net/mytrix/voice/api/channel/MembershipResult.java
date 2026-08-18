package net.mytrix.voice.api.channel;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed result returned by channel membership operations.
 */
public record MembershipResult(
        MembershipStatus status,
        VoiceChannelId channelId,
        UUID playerId,
        String message
) {

    public MembershipResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(playerId, "playerId");
        message = message == null ? "" : message;
    }

    public boolean successful() {
        return status == MembershipStatus.ADDED || status == MembershipStatus.REMOVED;
    }

    public static MembershipResult of(MembershipStatus status, VoiceChannelId channelId, UUID playerId, String message) {
        return new MembershipResult(status, channelId, playerId, message);
    }
}
