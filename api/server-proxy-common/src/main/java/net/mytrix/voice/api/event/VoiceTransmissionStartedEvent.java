package net.mytrix.voice.api.event;

import net.mytrix.voice.api.channel.VoiceChannelId;

import java.util.UUID;

/**
 * Fired when the public API observes a logical transmission start.
 */
public record VoiceTransmissionStartedEvent(UUID speakerId, VoiceChannelId channelId) implements VoiceEvent {
}
