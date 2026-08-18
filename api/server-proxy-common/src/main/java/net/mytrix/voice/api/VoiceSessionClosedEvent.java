package net.mytrix.voice.api;

import java.time.Instant;

public record VoiceSessionClosedEvent(VoiceSessionId sessionId, int channelsRemoved, int restrictionsRemoved, int sourcesRemoved, Instant createdAt) implements DynamicVoiceEvent {
}
