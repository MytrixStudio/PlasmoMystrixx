package net.mytrix.voice.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface DynamicVoiceApi {

    boolean isReady();

    VoiceOwnerContext registerOwner(String namespace, String version);

    VoiceSession createSession(VoiceSessionId sessionId, VoiceSessionOptions options);

    void activateSession(VoiceSessionId sessionId);

    void pauseSession(VoiceSessionId sessionId);

    Optional<VoiceSession> findSession(VoiceSessionId sessionId);

    VoiceChannel createChannel(VoiceSessionId sessionId, VoiceChannelId channelId, VoiceChannelOptions options);

    Optional<VoiceChannel> findChannel(VoiceChannelId channelId);

    void updateChannel(VoiceChannelId channelId, VoiceChannelUpdate update);

    void deleteChannel(VoiceChannelId channelId);

    void syncMembers(VoiceChannelId channelId, Collection<VoiceMemberDefinition> members);

    void addMember(VoiceChannelId channelId, VoiceMemberDefinition member);

    void removeMember(VoiceChannelId channelId, UUID playerId);

    void activateChannel(VoiceChannelId channelId);

    void deactivateChannel(VoiceChannelId channelId);

    void setProximityGroupReplacementEnabled(boolean enabled);

    boolean isProximityGroupReplacementEnabled();

    boolean selectGroupChannel(UUID playerId, VoiceChannelId channelId);

    void clearGroupChannelSelection(UUID playerId);

    Optional<VoiceChannelId> selectedGroupChannel(UUID playerId);

    VoiceRestrictionHandle applyRestriction(VoiceRestrictionRequest request);

    void removeRestriction(VoiceRestrictionHandle handle);

    void closeSession(VoiceSessionId sessionId);

    void closeOwnedSessions(String ownerNamespace);

    VoicePlayerSnapshot inspectPlayer(UUID playerId);

    VoiceChannelSnapshot inspectChannel(VoiceChannelId channelId);

    Collection<VoiceSessionSnapshot> inspectSessions();

    Collection<VoiceRestrictionSnapshot> inspectRestrictions();
}
