package net.mytrix.voice.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MytrixVoiceServices {

    private static final Map<Class<?>, Object> SERVICES = new ConcurrentHashMap<>();
    private static final DynamicVoiceApi DYNAMIC_VOICE_PROXY = new DeferredDynamicVoiceApi();

    private MytrixVoiceServices() {
    }

    public static <T> void register(Class<T> type, T service) {
        SERVICES.put(type, service);
    }

    public static <T> void unregister(Class<T> type, T service) {
        SERVICES.remove(type, service);
    }

    public static <T> Optional<T> find(Class<T> type) {
        Object service = SERVICES.get(type);
        if (service != null) return Optional.of(type.cast(service));
        if (type == DynamicVoiceApi.class) return Optional.of(type.cast(DYNAMIC_VOICE_PROXY));
        return Optional.empty();
    }

    private static Optional<DynamicVoiceApi> dynamicVoiceDelegate() {
        Object service = SERVICES.get(DynamicVoiceApi.class);
        if (service instanceof DynamicVoiceApi dynamicVoiceApi && service != DYNAMIC_VOICE_PROXY) {
            return Optional.of(dynamicVoiceApi);
        }
        return Optional.empty();
    }

    private static DynamicVoiceApi dynamicVoiceDelegateOrThrow() {
        return dynamicVoiceDelegate().orElseThrow(() ->
                new VoiceApiUnavailableException("Dynamic voice API is not ready yet")
        );
    }

    private static final class DeferredDynamicVoiceApi implements DynamicVoiceApi {

        @Override
        public boolean isReady() {
            return dynamicVoiceDelegate()
                    .map(DynamicVoiceApi::isReady)
                    .orElse(false);
        }

        @Override
        public VoiceOwnerContext registerOwner(String namespace, String version) {
            VoiceIdentifierValidator.validateNamespace(namespace);
            return dynamicVoiceDelegate()
                    .map(api -> api.registerOwner(namespace, version))
                    .orElseGet(() -> new DeferredOwnerContext(namespace, version == null ? "unknown" : version));
        }

        @Override
        public VoiceSession createSession(VoiceSessionId sessionId, VoiceSessionOptions options) {
            return dynamicVoiceDelegateOrThrow().createSession(sessionId, options);
        }

        @Override
        public void activateSession(VoiceSessionId sessionId) {
            dynamicVoiceDelegateOrThrow().activateSession(sessionId);
        }

        @Override
        public void pauseSession(VoiceSessionId sessionId) {
            dynamicVoiceDelegateOrThrow().pauseSession(sessionId);
        }

        @Override
        public Optional<VoiceSession> findSession(VoiceSessionId sessionId) {
            return dynamicVoiceDelegate().flatMap(api -> api.findSession(sessionId));
        }

        @Override
        public VoiceChannel createChannel(VoiceSessionId sessionId, VoiceChannelId channelId, VoiceChannelOptions options) {
            return dynamicVoiceDelegateOrThrow().createChannel(sessionId, channelId, options);
        }

        @Override
        public Optional<VoiceChannel> findChannel(VoiceChannelId channelId) {
            return dynamicVoiceDelegate().flatMap(api -> api.findChannel(channelId));
        }

        @Override
        public void updateChannel(VoiceChannelId channelId, VoiceChannelUpdate update) {
            dynamicVoiceDelegateOrThrow().updateChannel(channelId, update);
        }

        @Override
        public void deleteChannel(VoiceChannelId channelId) {
            dynamicVoiceDelegateOrThrow().deleteChannel(channelId);
        }

        @Override
        public void syncMembers(VoiceChannelId channelId, Collection<VoiceMemberDefinition> members) {
            dynamicVoiceDelegateOrThrow().syncMembers(channelId, members);
        }

        @Override
        public void addMember(VoiceChannelId channelId, VoiceMemberDefinition member) {
            dynamicVoiceDelegateOrThrow().addMember(channelId, member);
        }

        @Override
        public void removeMember(VoiceChannelId channelId, UUID playerId) {
            dynamicVoiceDelegateOrThrow().removeMember(channelId, playerId);
        }

        @Override
        public void activateChannel(VoiceChannelId channelId) {
            dynamicVoiceDelegateOrThrow().activateChannel(channelId);
        }

        @Override
        public void deactivateChannel(VoiceChannelId channelId) {
            dynamicVoiceDelegateOrThrow().deactivateChannel(channelId);
        }

        @Override
        public void setProximityGroupReplacementEnabled(boolean enabled) {
            dynamicVoiceDelegate().ifPresent(api -> api.setProximityGroupReplacementEnabled(enabled));
        }

        @Override
        public boolean isProximityGroupReplacementEnabled() {
            return dynamicVoiceDelegate()
                    .map(DynamicVoiceApi::isProximityGroupReplacementEnabled)
                    .orElse(false);
        }

        @Override
        public boolean selectGroupChannel(UUID playerId, VoiceChannelId channelId) {
            return dynamicVoiceDelegate()
                    .map(api -> api.selectGroupChannel(playerId, channelId))
                    .orElse(false);
        }

        @Override
        public void clearGroupChannelSelection(UUID playerId) {
            dynamicVoiceDelegate().ifPresent(api -> api.clearGroupChannelSelection(playerId));
        }

        @Override
        public Optional<VoiceChannelId> selectedGroupChannel(UUID playerId) {
            return dynamicVoiceDelegate()
                    .flatMap(api -> api.selectedGroupChannel(playerId));
        }

        @Override
        public VoiceRestrictionHandle applyRestriction(VoiceRestrictionRequest request) {
            return dynamicVoiceDelegateOrThrow().applyRestriction(request);
        }

        @Override
        public void removeRestriction(VoiceRestrictionHandle handle) {
            dynamicVoiceDelegateOrThrow().removeRestriction(handle);
        }

        @Override
        public void closeSession(VoiceSessionId sessionId) {
            dynamicVoiceDelegateOrThrow().closeSession(sessionId);
        }

        @Override
        public void closeOwnedSessions(String ownerNamespace) {
            dynamicVoiceDelegate().ifPresent(api -> api.closeOwnedSessions(ownerNamespace));
        }

        @Override
        public VoicePlayerSnapshot inspectPlayer(UUID playerId) {
            return dynamicVoiceDelegate()
                    .map(api -> api.inspectPlayer(playerId))
                    .orElseGet(() -> new VoicePlayerSnapshot(playerId, false, Set.of(), Set.of(), true, true, false));
        }

        @Override
        public VoiceChannelSnapshot inspectChannel(VoiceChannelId channelId) {
            return dynamicVoiceDelegateOrThrow().inspectChannel(channelId);
        }

        @Override
        public Collection<VoiceSessionSnapshot> inspectSessions() {
            return dynamicVoiceDelegate()
                    .map(DynamicVoiceApi::inspectSessions)
                    .orElseGet(Set::of);
        }

        @Override
        public Collection<VoiceRestrictionSnapshot> inspectRestrictions() {
            return dynamicVoiceDelegate()
                    .map(DynamicVoiceApi::inspectRestrictions)
                    .orElseGet(Set::of);
        }
    }

    private static final class DeferredOwnerContext implements VoiceOwnerContext {
        private final String namespace;
        private final String version;

        private DeferredOwnerContext(String namespace, String version) {
            this.namespace = namespace;
            this.version = version;
        }

        @Override
        public String namespace() {
            return namespace;
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public DynamicVoiceApi api() {
            return DYNAMIC_VOICE_PROXY;
        }
    }
}
