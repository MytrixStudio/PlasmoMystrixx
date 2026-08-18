package net.mytrix.voice.api.event;

import net.mytrix.voice.api.channel.VoiceChannelId;

import java.util.UUID;

/**
 * Fired when the public API observes a logical transmission stop.
 */
public record VoiceTransmissionStoppedEvent(
        UUID speakerId,
        VoiceChannelId channelId,
        TransmissionStopReason reason
) implements VoiceEvent {
}
