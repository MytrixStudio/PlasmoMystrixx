package net.mytrix.voice.api.transmission;

import net.mytrix.voice.api.channel.VoiceChannelId;

import java.util.Optional;
import java.util.UUID;

/**
 * Typed result for changing a player's active voice channel.
 */
public record TransmissionSelectionResult(
        TransmissionSelectionStatus status,
        UUID playerId,
        Optional<VoiceChannelId> channelId,
        String message
) {

    public TransmissionSelectionResult {
        java.util.Objects.requireNonNull(status, "status");
        java.util.Objects.requireNonNull(playerId, "playerId");
        channelId = channelId == null ? Optional.empty() : channelId;
        message = message == null ? "" : message;
    }

    public boolean successful() {
        return status == TransmissionSelectionStatus.SELECTED ||
                status == TransmissionSelectionStatus.CLEARED;
    }

    public static TransmissionSelectionResult selected(UUID playerId, VoiceChannelId channelId) {
        return new TransmissionSelectionResult(TransmissionSelectionStatus.SELECTED, playerId, Optional.of(channelId), "selected");
    }

    public static TransmissionSelectionResult cleared(UUID playerId) {
        return new TransmissionSelectionResult(TransmissionSelectionStatus.CLEARED, playerId, Optional.empty(), "cleared");
    }

    public static TransmissionSelectionResult failure(TransmissionSelectionStatus status, UUID playerId, VoiceChannelId channelId, String message) {
        return new TransmissionSelectionResult(status, playerId, Optional.ofNullable(channelId), message);
    }
}
