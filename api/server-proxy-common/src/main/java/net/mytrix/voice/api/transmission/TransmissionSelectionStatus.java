package net.mytrix.voice.api.transmission;

/**
 * Result category for server-authorized channel selection.
 */
public enum TransmissionSelectionStatus {
    SELECTED,
    CLEARED,
    CHANNEL_NOT_FOUND,
    NOT_A_MEMBER,
    CANNOT_SPEAK,
    API_NOT_READY,
    REJECTED
}
