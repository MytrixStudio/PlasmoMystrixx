package net.mytrix.voice.api;

import java.util.Set;

public interface VoiceSession {

    VoiceSessionId id();

    VoiceSessionState state();

    VoiceSessionOptions options();

    Set<VoiceChannelId> channels();
}
