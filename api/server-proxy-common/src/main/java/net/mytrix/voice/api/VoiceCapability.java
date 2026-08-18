package net.mytrix.voice.api;

/**
 * Feature flags exposed by a concrete Mytrix Voice API implementation.
 */
public enum VoiceCapability {
    PROXIMITY_CHANNELS,
    GROUP_CHANNELS,
    CROSS_DIMENSION_CHANNELS,
    NON_SPATIAL_AUDIO,
    CUSTOM_CHANNEL_PROVIDERS,
    VOICE_EVENTS,
    SERVER_CHANNEL_SELECTION
}
