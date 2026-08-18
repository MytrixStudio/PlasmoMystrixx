package net.mytrix.voice.api.event;

/**
 * Public reason a voice operation or frame was rejected.
 */
public enum VoiceRejectionReason {
    API_NOT_READY,
    CHANNEL_NOT_FOUND,
    NOT_A_MEMBER,
    CANNOT_SPEAK,
    CANNOT_LISTEN,
    PAYLOAD_TOO_LARGE,
    RATE_LIMITED,
    CALLBACK_FAILED,
    CLOSED,
    UNKNOWN
}
