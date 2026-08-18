package su.plo.voice.server.dynamic;

import net.mytrix.voice.api.VoiceChannelId;

import java.util.UUID;

/**
 * Internal boundary used by public API adapters to apply channel permissions
 * without exposing routing internals to consumers.
 */
public interface DynamicVoiceRouteGuard {

    boolean canTransmit(VoiceChannelId channelId, UUID speakerId);

    boolean canReceive(VoiceChannelId channelId, UUID listenerId, UUID speakerId);

    default void onRejected(VoiceChannelId channelId, UUID speakerId, String reason) {
    }
}
