package net.mytrix.voice.api.event;

import net.mytrix.voice.api.channel.VoiceChannelId;

/**
 * Fired after an API-owned channel has been removed.
 */
public record VoiceChannelUnregisteredEvent(VoiceChannelId channelId) implements VoiceEvent {
}
