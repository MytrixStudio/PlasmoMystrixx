package net.mytrix.voice.api.event;

import net.mytrix.voice.api.channel.VoiceChannelId;

import java.util.UUID;

/**
 * Fired after a member is removed from an API-owned channel.
 */
public record VoiceChannelMemberLeftEvent(VoiceChannelId channelId, UUID playerId) implements VoiceEvent {
}
