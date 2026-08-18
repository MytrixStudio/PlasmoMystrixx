package net.mytrix.voice.api;

import java.time.Instant;

public record VoiceMemberAddedEvent(VoiceChannelId channelId, VoiceMembership membership, Instant createdAt) implements DynamicVoiceEvent {
}
