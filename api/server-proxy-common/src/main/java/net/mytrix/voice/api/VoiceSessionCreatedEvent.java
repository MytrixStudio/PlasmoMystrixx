package net.mytrix.voice.api;

import java.time.Instant;

public record VoiceSessionCreatedEvent(VoiceSessionSnapshot session, Instant createdAt) implements DynamicVoiceEvent {
}
