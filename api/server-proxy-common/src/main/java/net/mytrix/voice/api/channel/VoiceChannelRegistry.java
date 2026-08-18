package net.mytrix.voice.api.channel;

import java.util.Collection;
import java.util.Optional;

/**
 * Server-side registry for API-owned voice channels.
 *
 * <p>Mutating calls should be made from the logical server thread when
 * possible. Returned collections are immutable snapshots.</p>
 */
public interface VoiceChannelRegistry {

    RegistrationResult register(VoiceChannelDefinition definition);

    boolean unregister(VoiceChannelId channelId);

    Optional<VoiceChannelView> find(VoiceChannelId channelId);

    Collection<VoiceChannelView> channelsOwnedBy(String namespace);

    default MembershipResult invalidateMembership(VoiceChannelId channelId) {
        return find(channelId)
                .map(view -> MembershipResult.of(MembershipStatus.REJECTED, channelId, new java.util.UUID(0L, 0L), "Channel does not expose a provider refresh through this view"))
                .orElseGet(() -> MembershipResult.of(MembershipStatus.CHANNEL_NOT_FOUND, channelId, new java.util.UUID(0L, 0L), "Channel not found"));
    }
}
