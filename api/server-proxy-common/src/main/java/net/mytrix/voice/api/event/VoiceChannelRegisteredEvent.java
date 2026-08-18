package net.mytrix.voice.api.event;

import net.mytrix.voice.api.channel.VoiceChannelView;

/**
 * Fired after an API-owned channel has been registered.
 */
public record VoiceChannelRegisteredEvent(VoiceChannelView channel) implements VoiceEvent {
}
