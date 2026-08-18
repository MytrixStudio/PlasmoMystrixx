package net.mytrix.voice.api.channel;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Optional membership provider for dynamic channels.
 *
 * <p>The runtime does not call this for every audio frame. Owners should call
 * {@code refreshMembership()} on the returned handle when their own group state
 * changes.</p>
 */
public interface VoiceChannelMembershipProvider {

    VoiceChannelMembershipProvider EMPTY = channel -> Set.of();

    Collection<UUID> resolveMembers(VoiceChannelContext channel);

    default boolean isMember(VoicePlayerContext player, VoiceChannelContext channel) {
        return resolveMembers(channel).contains(player.playerId());
    }

    static VoiceChannelMembershipProvider empty() {
        return EMPTY;
    }
}
