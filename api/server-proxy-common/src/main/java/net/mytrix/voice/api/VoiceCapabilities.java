package net.mytrix.voice.api;

public record VoiceCapabilities(
        boolean transmit,
        boolean receive,
        boolean bypassChannelMute,
        boolean hearAllChannels,
        boolean broadcast
) {

    public static VoiceCapabilities member() {
        return new VoiceCapabilities(true, true, false, false, false);
    }

    public static VoiceCapabilities listener() {
        return new VoiceCapabilities(false, true, false, false, false);
    }

    public static VoiceCapabilities broadcaster() {
        return new VoiceCapabilities(true, true, false, false, true);
    }

    public static VoiceCapabilities moderator() {
        return new VoiceCapabilities(true, true, true, true, true);
    }

    public static VoiceCapabilities spectator() {
        return new VoiceCapabilities(false, true, false, true, false);
    }
}
