package net.mytrix.voice.api.channel;

import java.util.Set;
import java.util.UUID;

/**
 * Mutable public handle for manually managed channel membership.
 */
public interface ManagedVoiceChannel {

    VoiceChannelId id();

    VoiceChannelConfig config();

    MembershipResult addMember(UUID playerId);

    MembershipResult removeMember(UUID playerId);

    boolean contains(UUID playerId);

    Set<UUID> members();

    void clearMembers();
}
