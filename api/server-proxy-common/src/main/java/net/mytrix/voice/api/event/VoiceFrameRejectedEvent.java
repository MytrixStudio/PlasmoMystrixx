package net.mytrix.voice.api.event;

import net.mytrix.voice.api.channel.VoiceChannelId;

import java.util.Optional;
import java.util.UUID;

/**
 * Fired for public diagnostic rejection events. It does not expose audio
 * content.
 */
public record VoiceFrameRejectedEvent(
        UUID speakerId,
        Optional<VoiceChannelId> channelId,
        VoiceRejectionReason reason
) implements VoiceEvent {

    public VoiceFrameRejectedEvent {
        channelId = channelId == null ? Optional.empty() : channelId;
    }
}
