package net.mytrix.voice.api;

/**
 * Lifecycle state of a public voice API service.
 */
public enum VoiceApiState {
    /** The voice chat runtime is not present or not registered. */
    UNAVAILABLE,
    /** The runtime exists but is not ready for routing or registration yet. */
    INITIALIZING,
    /** The runtime accepts public API calls. */
    READY,
    /** The runtime is shutting down and rejects new registrations. */
    STOPPING,
    /** The runtime has stopped and all handles should be considered invalid. */
    STOPPED
}
