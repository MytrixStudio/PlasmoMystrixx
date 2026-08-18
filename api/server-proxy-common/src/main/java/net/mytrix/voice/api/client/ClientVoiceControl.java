package net.mytrix.voice.api.client;

import net.mytrix.voice.api.channel.VoiceChannelId;
import net.mytrix.voice.api.transmission.TransmissionSelectionResult;

import java.util.Optional;

/**
 * Client control contract for selecting voice channels without exposing
 * microphone capture or packet creation.
 */
public interface ClientVoiceControl {

    TransmissionSelectionResult setActiveChannel(VoiceChannelId channelId);

    void useProximityChannel();

    Optional<VoiceChannelId> activeChannel();
}
