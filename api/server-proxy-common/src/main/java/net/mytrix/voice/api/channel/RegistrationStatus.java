package net.mytrix.voice.api.channel;

/**
 * Result category for channel registration.
 */
public enum RegistrationStatus {
    SUCCESS,
    ALREADY_REGISTERED,
    INVALID_DEFINITION,
    NAMESPACE_NOT_ALLOWED,
    API_NOT_READY,
    ERROR
}
