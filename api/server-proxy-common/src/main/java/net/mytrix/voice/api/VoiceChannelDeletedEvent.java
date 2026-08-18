package net.mytrix.voice.api;

import java.time.Instant;

public record VoiceChannelDeletedEvent(VoiceChannelId channelId, Instant createdAt) implements DynamicVoiceEvent {
}
