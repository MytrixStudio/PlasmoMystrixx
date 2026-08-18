package net.mytrix.voice.api.transmission;

import net.mytrix.voice.api.channel.VoiceChannelId;

import java.util.Optional;
import java.util.UUID;

/**
 * Server-authoritative channel selection service.
 *
 * <p>This service never injects audio. It only controls which registered
 * channel the existing capture/codec/network path will use for a player.</p>
 */
public interface VoiceTransmissionService {

    TransmissionSelectionResult selectChannel(UUID playerId, VoiceChannelId channelId);

    TransmissionSelectionResult clearSelection(UUID playerId);

    Optional<VoiceChannelId> selectedChannel(UUID playerId);
}
