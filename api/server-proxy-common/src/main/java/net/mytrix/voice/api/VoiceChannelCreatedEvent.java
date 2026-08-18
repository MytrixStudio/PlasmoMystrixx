package net.mytrix.voice.api;

import java.time.Instant;

public record VoiceChannelCreatedEvent(VoiceChannelSnapshot channel, Instant createdAt) implements DynamicVoiceEvent {
}
