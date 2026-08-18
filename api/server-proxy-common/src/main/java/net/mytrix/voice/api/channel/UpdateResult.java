package net.mytrix.voice.api.channel;

import java.util.Objects;

/**
 * Typed result returned by public channel updates.
 */
public record UpdateResult(UpdateStatus status, VoiceChannelId channelId, String message) {

    public UpdateResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(channelId, "channelId");
        message = message == null ? "" : message;
    }

    public boolean successful() {
        return status == UpdateStatus.UPDATED;
    }
}
