package net.mytrix.voice.api;

import java.time.Instant;

public record VoiceRestrictionRemovedEvent(VoiceRestrictionSnapshot restriction, Instant createdAt) implements DynamicVoiceEvent {
}
