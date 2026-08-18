package net.mytrix.voice.api.event;

import net.mytrix.voice.api.channel.VoiceChannelId;

import java.util.UUID;

/**
 * Fired after a member is added to an API-owned channel.
 */
public record VoiceChannelMemberJoinedEvent(VoiceChannelId channelId, UUID playerId) implements VoiceEvent {
}
