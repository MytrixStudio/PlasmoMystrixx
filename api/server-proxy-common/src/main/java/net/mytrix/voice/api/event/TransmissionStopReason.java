package net.mytrix.voice.api.event;

/**
 * Reason a public voice transmission stopped.
 */
public enum TransmissionStopReason {
    CLIENT_STOPPED,
    CHANNEL_CLOSED,
    MEMBER_REMOVED,
    TIMEOUT,
    REJECTED
}
