package net.mytrix.voice.api.channel;

/**
 * Result category for channel updates.
 */
public enum UpdateStatus {
    UPDATED,
    CHANNEL_NOT_FOUND,
    CHANNEL_CLOSED,
    INVALID_CONFIG,
    REJECTED,
    API_NOT_READY
}
