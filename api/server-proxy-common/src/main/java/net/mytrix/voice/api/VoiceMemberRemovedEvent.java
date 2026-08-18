package net.mytrix.voice.api;

import java.time.Instant;
import java.util.UUID;

public record VoiceMemberRemovedEvent(VoiceChannelId channelId, UUID playerId, Instant createdAt) implements DynamicVoiceEvent {
}
