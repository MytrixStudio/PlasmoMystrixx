package net.mytrix.voice.api;

import java.time.Instant;

public record VoiceRestrictionAppliedEvent(VoiceRestrictionSnapshot restriction, Instant createdAt) implements DynamicVoiceEvent {
}
