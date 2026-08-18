package net.mytrix.voice.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface VoiceChannel {

    VoiceChannelId id();

    VoiceSessionId sessionId();

    Set<UUID> members();

    VoiceChannelPolicy policy();

    boolean active();

    Map<String, String> metadata();
}
