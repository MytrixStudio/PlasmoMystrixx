package su.plo.voice.server.dynamic;

import net.mytrix.voice.api.*;
import org.jetbrains.annotations.NotNull;
import su.plo.slib.api.server.position.ServerPos3d;
import su.plo.voice.BaseVoice;
import su.plo.voice.api.event.EventPriority;
import su.plo.voice.api.event.EventSubscribe;
import su.plo.voice.api.server.event.audio.source.ServerSourceAudioPacketEvent;
import su.plo.voice.api.server.event.connection.UdpClientConnectedEvent;
import su.plo.voice.api.server.event.connection.UdpClientDisconnectedEvent;
import su.plo.voice.api.server.player.VoicePlayer;
import su.plo.voice.api.server.socket.UdpServerConnection;
import su.plo.voice.proto.packets.tcp.clientbound.GroupRoutingModePacket;
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket;
import su.plo.voice.server.BaseVoiceServer;
import su.plo.voice.server.api.MytrixServerVoiceApi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public final class DynamicVoiceService implements DynamicVoiceApi, VoicePacketRouter {

    private final BaseVoiceServer voiceServer;

    private final ConcurrentMap<VoiceSessionId, SessionState> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<VoiceChannelId, ChannelState> channels = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<VoiceChannelId>> channelsByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, VoiceChannelId> selectedChannelByPlayer = new ConcurrentHashMap<>();

    private final ConcurrentMap<VoiceRestrictionHandle, RestrictionState> restrictions = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<VoiceRestrictionHandle>> restrictionsByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentMap<VoiceChannelId, Set<VoiceRestrictionHandle>> restrictionsByChannel = new ConcurrentHashMap<>();
    private final ConcurrentMap<VoiceSessionId, Set<VoiceRestrictionHandle>> restrictionsBySession = new ConcurrentHashMap<>();
    private final Set<VoiceRestrictionHandle> globalRestrictions = ConcurrentHashMap.newKeySet();

    private final ConcurrentMap<String, OwnerState> owners = new ConcurrentHashMap<>();
    private final Set<UUID> connectedPlayers = ConcurrentHashMap.newKeySet();
    private final List<VoiceDynamicEventListener> listeners = new CopyOnWriteArrayList<>();
    private final MytrixServerVoiceApi publicServerApi;

    private volatile boolean registered;
    private volatile boolean ready;
    private volatile boolean debugRouting;
    private volatile boolean proximityGroupReplacementEnabled;
    private volatile DynamicVoiceRouteGuard routeGuard;

    public DynamicVoiceService(@NotNull BaseVoiceServer voiceServer) {
        this.voiceServer = voiceServer;
        this.publicServerApi = new MytrixServerVoiceApi(this);
    }

    public void registerService() {
        if (registered) return;

        MytrixVoiceServices.register(DynamicVoiceApi.class, this);
        publicServerApi.registerService();
        registered = true;
        logInfo("[MytrixVoice] Dynamic voice API registered");
    }

    public void initialize() {
        registerService();
        ready = true;
        voiceServer.getUdpConnectionManager().getConnections().forEach(connection ->
                connectedPlayers.add(connection.getPlayer().getInstance().getUuid())
        );
        publicServerApi.ready();
        logInfo("[MytrixVoice] Dynamic voice service ready");
        logInfo("[MytrixVoice] Backend adapter: PlasmoVoice");
        logInfo("[MytrixVoice] Group packet routing: active");
    }

    public void shutdown() {
        ready = false;
        publicServerApi.shutdown();
        MytrixVoiceServices.unregister(DynamicVoiceApi.class, this);
        registered = false;
        new ArrayList<>(sessions.keySet()).forEach(this::closeSession);
        listeners.clear();
        connectedPlayers.forEach(playerId -> sendGroupRoutingMode(playerId, false));
        connectedPlayers.clear();
        selectedChannelByPlayer.clear();
    }

    public void setRouteGuard(DynamicVoiceRouteGuard routeGuard) {
        this.routeGuard = routeGuard;
    }

    public void setDebugRouting(boolean debugRouting) {
        this.debugRouting = debugRouting;
    }

    public boolean isDebugRouting() {
        return debugRouting;
    }

    @Override
    public void setProximityGroupReplacementEnabled(boolean enabled) {
        this.proximityGroupReplacementEnabled = enabled;
        if (enabled) {
            selectedChannelByPlayer.keySet().forEach(voiceServer::removeProximitySourceOwnedBy);
            selectedChannelByPlayer.keySet().forEach(this::syncGroupRoutingMode);
        } else {
            selectedChannelByPlayer.keySet().forEach(voiceServer::removeGroupSourcesOwnedBy);
            connectedPlayers.forEach(playerId -> sendGroupRoutingMode(playerId, false));
        }
        logInfo(
                "[MytrixVoice] Native client group routing {}",
                enabled ? "enabled" : "disabled"
        );
    }

    @Override
    public boolean isProximityGroupReplacementEnabled() {
        return proximityGroupReplacementEnabled;
    }

    public void addListener(VoiceDynamicEventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(VoiceDynamicEventListener listener) {
        listeners.remove(listener);
    }

    @Override
    public boolean isReady() {
        return ready && voiceServer.getUdpServer().isPresent();
    }

    @Override
    public VoiceOwnerContext registerOwner(String namespace, String version) {
        VoiceIdentifierValidator.validateNamespace(namespace);
        OwnerState owner = owners.compute(namespace, (key, old) -> new OwnerState(namespace, version == null ? "unknown" : version));
        return owner;
    }

    @Override
    public VoiceSession createSession(VoiceSessionId sessionId, VoiceSessionOptions options) {
        requireRegistered();
        Objects.requireNonNull(sessionId, "sessionId");
        if (options == null) options = VoiceSessionOptions.memoryOnly();

        SessionState session = new SessionState(sessionId, options);
        SessionState existing = sessions.putIfAbsent(sessionId, session);
        if (existing != null && existing.state != VoiceSessionState.CLOSED) {
            throw new VoiceSessionAlreadyExistsException("Session already exists: " + sessionId);
        }
        session.state = VoiceSessionState.ACTIVE;
        fire(new VoiceSessionCreatedEvent(session.snapshot(), Instant.now()));
        logInfo("[MytrixVoice] Created session {}", sessionId);
        return session;
    }

    public void activateSession(VoiceSessionId sessionId) {
        getSessionOrThrow(sessionId).state = VoiceSessionState.ACTIVE;
    }

    public void pauseSession(VoiceSessionId sessionId) {
        getSessionOrThrow(sessionId).state = VoiceSessionState.PAUSED;
    }

    @Override
    public Optional<VoiceSession> findSession(VoiceSessionId sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public VoiceChannel createChannel(VoiceSessionId sessionId, VoiceChannelId channelId, VoiceChannelOptions options) {
        requireRegistered();
        SessionState session = getSessionOrThrow(sessionId);
        if (session.state == VoiceSessionState.CLOSED || session.state == VoiceSessionState.CLOSING) {
            throw new VoiceSessionClosedException("Session is closed: " + sessionId);
        }
        if (!channelId.sessionId().equals(sessionId)) {
            throw new InvalidVoiceIdentifierException("Channel " + channelId + " does not belong to session " + sessionId);
        }
        if (options == null) options = VoiceChannelOptions.builder().build();

        ChannelState channel = new ChannelState(channelId, options.policy(), options.active(), options.metadata());
        ChannelState existing = channels.putIfAbsent(channelId, channel);
        if (existing != null) {
            throw new VoiceChannelAlreadyExistsException("Channel already exists: " + channelId);
        }
        session.channels.add(channelId);
        fire(new VoiceChannelCreatedEvent(channel.snapshot(), Instant.now()));
        logInfo(
                "[MytrixVoice] Created channel {} members={} exclusive={} priority={}",
                channelId,
                channel.members.size(),
                channel.policy.exclusive(),
                channel.policy.priority()
        );
        return channel;
    }

    @Override
    public Optional<VoiceChannel> findChannel(VoiceChannelId channelId) {
        return Optional.ofNullable(channels.get(channelId));
    }

    @Override
    public void updateChannel(VoiceChannelId channelId, VoiceChannelUpdate update) {
        ChannelState channel = getChannelOrThrow(channelId);
        if (update == null) return;
        update.policy().ifPresent(policy -> channel.policy = policy);
        update.active().ifPresent(active -> {
            channel.active = active;
            if (!active) {
                clearSelectionsForChannel(channelId);
                voiceServer.removeGroupSourcesForChannel(channelId);
            }
        });
        channel.metadata.putAll(update.putMetadata());
        update.removeMetadata().forEach(channel.metadata::remove);
    }

    @Override
    public void deleteChannel(VoiceChannelId channelId) {
        ChannelState removed = channels.remove(channelId);
        if (removed == null) return;
        voiceServer.removeGroupSourcesForChannel(channelId);
        clearSelectionsForChannel(channelId);

        sessions.computeIfPresent(channelId.sessionId(), (id, session) -> {
            session.channels.remove(channelId);
            return session;
        });
        for (UUID member : removed.members.keySet()) {
            removePlayerIndex(member, channelId);
        }
        Set<VoiceRestrictionHandle> channelRestrictions = restrictionsByChannel.remove(channelId);
        if (channelRestrictions != null) {
            channelRestrictions.forEach(this::removeRestriction);
        }
        fire(new VoiceChannelDeletedEvent(channelId, Instant.now()));
        logInfo("[MytrixVoice] Deleted channel {}", channelId);
    }

    @Override
    public void syncMembers(VoiceChannelId channelId, Collection<VoiceMemberDefinition> members) {
        ChannelState channel = getChannelOrThrow(channelId);
        Map<UUID, VoiceMembership> desired = new HashMap<>();
        if (members != null) {
            for (VoiceMemberDefinition member : members) {
                desired.put(member.playerId(), toMembership(channelId, member));
            }
        }

        for (UUID existing : new HashSet<>(channel.members.keySet())) {
            if (!desired.containsKey(existing)) {
                removeMember(channelId, existing);
            }
        }

        desired.forEach((playerId, membership) -> {
            VoiceMembership previous = channel.members.put(playerId, membership);
            addPlayerIndex(playerId, channelId);
            if (previous == null) {
                fire(new VoiceMemberAddedEvent(channelId, membership, Instant.now()));
            }
        });
    }

    @Override
    public void addMember(VoiceChannelId channelId, VoiceMemberDefinition member) {
        ChannelState channel = getChannelOrThrow(channelId);
        VoiceMembership membership = toMembership(channelId, member);
        VoiceMembership previous = channel.members.put(member.playerId(), membership);
        addPlayerIndex(member.playerId(), channelId);
        if (previous == null) {
            fire(new VoiceMemberAddedEvent(channelId, membership, Instant.now()));
        }
    }

    @Override
    public void removeMember(VoiceChannelId channelId, UUID playerId) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(playerId, "playerId");
        ChannelState channel = channels.get(channelId);
        if (channel == null) {
            removePlayerIndex(playerId, channelId);
            clearGroupChannelSelection(playerId, channelId);
            voiceServer.removeGroupRecipientFromChannel(channelId, playerId);
            return;
        }
        if (channel.members.remove(playerId) != null) {
            removePlayerIndex(playerId, channelId);
            clearGroupChannelSelection(playerId, channelId);
            voiceServer.removeGroupSourcesOwnedBy(playerId);
            voiceServer.removeGroupRecipientFromChannel(channelId, playerId);
            fire(new VoiceMemberRemovedEvent(channelId, playerId, Instant.now()));
        }
    }

    @Override
    public void activateChannel(VoiceChannelId channelId) {
        getChannelOrThrow(channelId).active = true;
    }

    @Override
    public void deactivateChannel(VoiceChannelId channelId) {
        ChannelState channel = getChannelOrThrow(channelId);
        channel.active = false;
        clearSelectionsForChannel(channelId);
        voiceServer.removeGroupSourcesForChannel(channelId);
    }

    @Override
    public VoiceRestrictionHandle applyRestriction(VoiceRestrictionRequest request) {
        requireRegistered();
        Objects.requireNonNull(request, "request");

        VoiceRestrictionHandle handle = VoiceRestrictionHandle.create(request.ownerNamespace());
        RestrictionState state = new RestrictionState(handle, request);
        restrictions.put(handle, state);
        indexRestriction(state);
        cleanupTargetSources(request.target(), request.type());
        VoiceRestrictionSnapshot snapshot = state.snapshot();
        fire(new VoiceRestrictionAppliedEvent(snapshot, Instant.now()));
        logInfo(
                "[MytrixVoice] Applied restriction type={} target={} owner={} reason={}",
                request.type(),
                formatTarget(request.target()),
                request.ownerNamespace(),
                request.reason()
        );
        return handle;
    }

    @Override
    public void removeRestriction(VoiceRestrictionHandle handle) {
        RestrictionState removed = restrictions.remove(handle);
        if (removed == null) return;
        unindexRestriction(removed);
        fire(new VoiceRestrictionRemovedEvent(removed.snapshot(), Instant.now()));
    }

    @Override
    public void closeSession(VoiceSessionId sessionId) {
        SessionState session = sessions.get(sessionId);
        if (session == null || session.state == VoiceSessionState.CLOSED) return;
        session.state = VoiceSessionState.CLOSING;

        int channelCount = session.channels.size();
        Set<UUID> affectedPlayers = session.channels.stream()
                .map(channels::get)
                .filter(Objects::nonNull)
                .flatMap(channel -> channel.members.keySet().stream())
                .collect(Collectors.toSet());

        for (VoiceChannelId channelId : new HashSet<>(session.channels)) {
            deleteChannel(channelId);
        }

        int removedRestrictions = 0;
        Set<VoiceRestrictionHandle> sessionRestrictions = restrictionsBySession.remove(sessionId);
        if (sessionRestrictions != null) {
            for (VoiceRestrictionHandle handle : new HashSet<>(sessionRestrictions)) {
                if (restrictions.containsKey(handle)) {
                    removeRestriction(handle);
                    removedRestrictions++;
                }
            }
        }

        int removedSources = 0;
        for (UUID playerId : affectedPlayers) {
            if (stopSourcesOwnedBy(playerId)) removedSources++;
        }

        session.state = VoiceSessionState.CLOSED;
        sessions.remove(sessionId);
        fire(new VoiceSessionClosedEvent(sessionId, channelCount, removedRestrictions, removedSources, Instant.now()));
        logInfo(
                "[MytrixVoice] Closed session {} channels={} restrictions_removed={} sources_removed={}",
                sessionId,
                channelCount,
                removedRestrictions,
                removedSources
        );
    }

    @Override
    public void closeOwnedSessions(String ownerNamespace) {
        VoiceIdentifierValidator.validateNamespace(ownerNamespace);
        new ArrayList<>(sessions.keySet()).stream()
                .filter(sessionId -> sessionId.namespace().equals(ownerNamespace))
                .forEach(this::closeSession);

        new ArrayList<>(restrictions.values()).stream()
                .filter(restriction -> restriction.request.ownerNamespace().equals(ownerNamespace))
                .map(restriction -> restriction.handle)
                .forEach(this::removeRestriction);
    }

    @Override
    public VoicePlayerSnapshot inspectPlayer(UUID playerId) {
        cleanupExpiredRestrictions();
        Set<VoiceChannelId> playerChannels = Set.copyOf(channelsByPlayer.getOrDefault(playerId, Set.of()));
        Set<VoiceRestrictionSnapshot> playerRestrictions = collectRestrictions(playerId, playerChannels).stream()
                .map(RestrictionState::snapshot)
                .collect(Collectors.toUnmodifiableSet());
        boolean canTransmit = playerRestrictions.stream().noneMatch(restriction ->
                restriction.type() == VoiceRestrictionType.BLOCK_TRANSMIT || restriction.type() == VoiceRestrictionType.BLOCK_BOTH
        );
        boolean canReceive = playerRestrictions.stream().noneMatch(restriction ->
                restriction.type() == VoiceRestrictionType.BLOCK_RECEIVE || restriction.type() == VoiceRestrictionType.BLOCK_BOTH
        );
        boolean proximityDisabled = playerRestrictions.stream().anyMatch(restriction ->
                restriction.type() == VoiceRestrictionType.DISABLE_PROXIMITY || restriction.type() == VoiceRestrictionType.FORCE_CHANNEL_ONLY
        );
        return new VoicePlayerSnapshot(playerId, connectedPlayers.contains(playerId), playerChannels, playerRestrictions, canTransmit, canReceive, proximityDisabled);
    }

    @Override
    public VoiceChannelSnapshot inspectChannel(VoiceChannelId channelId) {
        return getChannelOrThrow(channelId).snapshot();
    }

    @Override
    public Collection<VoiceSessionSnapshot> inspectSessions() {
        return sessions.values().stream()
                .map(SessionState::snapshot)
                .sorted(Comparator.comparing(snapshot -> snapshot.id().toString()))
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public Collection<VoiceRestrictionSnapshot> inspectRestrictions() {
        cleanupExpiredRestrictions();
        return restrictions.values().stream()
                .map(RestrictionState::snapshot)
                .sorted(Comparator.comparing(snapshot -> snapshot.handle().id().toString()))
                .collect(Collectors.toUnmodifiableList());
    }

    public Collection<VoiceChannelSnapshot> inspectChannels(VoiceSessionId sessionId) {
        SessionState session = getSessionOrThrow(sessionId);
        return session.channels.stream()
                .map(channels::get)
                .filter(Objects::nonNull)
                .map(ChannelState::snapshot)
                .sorted(Comparator.comparing(snapshot -> snapshot.id().toString()))
                .collect(Collectors.toUnmodifiableList());
    }

    public Collection<VoiceRestrictionSnapshot> inspectChannelRestrictions(VoiceChannelId channelId) {
        cleanupExpiredRestrictions();
        return restrictionsByChannel.getOrDefault(channelId, Set.of()).stream()
                .map(restrictions::get)
                .filter(Objects::nonNull)
                .map(RestrictionState::snapshot)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public VoiceRoutingResult route(VoiceRoutingContext context) {
        return route(context, false);
    }

    private VoiceRoutingResult route(VoiceRoutingContext context, boolean groupTransmission) {
        cleanupExpiredRestrictions();
        UUID speakerId = context.speakerId();
        VoiceChannelId selectedGroupChannel = groupTransmission ? selectedChannelByPlayer.get(speakerId) : null;
        List<VoiceChannelSnapshot> activeChannels = context.memberships().stream()
                .filter(VoiceChannelSnapshot::active)
                .filter(channel -> {
                    SessionState session = sessions.get(channel.id().sessionId());
                    return session != null && session.state == VoiceSessionState.ACTIVE;
                })
                .filter(channel -> selectedGroupChannel == null || channel.id().equals(selectedGroupChannel))
                .sorted(channelComparator())
                .toList();
        if (selectedGroupChannel != null && activeChannels.isEmpty()) {
            clearGroupChannelSelection(speakerId, selectedGroupChannel);
            return VoiceRoutingResult.discard(false, Optional.of(selectedGroupChannel));
        }

        boolean forceChannelOnly = context.restrictions().stream().anyMatch(restriction ->
                restriction.type() == VoiceRestrictionType.FORCE_CHANNEL_ONLY ||
                        restriction.type() == VoiceRestrictionType.DISABLE_PROXIMITY
        );

        if (blocksTransmit(context.restrictions())) {
            boolean playerOrGlobalBlock = context.restrictions().stream()
                    .filter(restriction -> restriction.target() instanceof RestrictionTarget.Player ||
                            restriction.target() instanceof RestrictionTarget.Global)
                    .anyMatch(restriction -> restriction.type() == VoiceRestrictionType.BLOCK_TRANSMIT ||
                            restriction.type() == VoiceRestrictionType.BLOCK_BOTH);
            if (playerOrGlobalBlock) {
                boolean cancel = forceChannelOnly || activeChannels.stream().anyMatch(channel -> channel.policy().exclusive());
                Optional<VoiceChannelId> selected = activeChannels.stream().findFirst().map(VoiceChannelSnapshot::id);
                return VoiceRoutingResult.discard(cancel, selected);
            }
        }

        for (VoiceChannelSnapshot channel : activeChannels) {
            VoiceCapabilities speakerCapabilities = channel.capabilities().get(speakerId);
            if (speakerCapabilities == null || !speakerCapabilities.transmit()) continue;
            DynamicVoiceRouteGuard guard = routeGuard;
            if (guard != null && !guard.canTransmit(channel.id(), speakerId)) {
                guard.onRejected(channel.id(), speakerId, "cannot_speak");
                if (channel.policy().exclusive() || forceChannelOnly || selectedGroupChannel != null) {
                    return VoiceRoutingResult.discard(true, Optional.of(channel.id()));
                }
                continue;
            }

            VoiceChannelPolicy policy = channel.policy();
            List<VoiceRestrictionSnapshot> channelRestrictions = collectRestrictionsForChannel(speakerId, channel.id()).stream()
                    .map(RestrictionState::snapshot)
                    .collect(Collectors.toUnmodifiableList());
            if (blocksTransmit(channelRestrictions)) {
                if (policy.exclusive() || forceChannelOnly) {
                    return VoiceRoutingResult.discard(true, Optional.of(channel.id()));
                }
                continue;
            }

            if (policy.routingMode() == RoutingMode.DISABLED) {
                if (policy.exclusive()) return VoiceRoutingResult.discard(true, Optional.of(channel.id()));
                continue;
            }
            if (groupTransmission && policy.routingMode() == RoutingMode.PROXIMITY) {
                continue;
            }
            if (policy.routingMode() == RoutingMode.PROXIMITY) {
                if (forceChannelOnly || policy.exclusive()) {
                    return VoiceRoutingResult.discard(true, Optional.of(channel.id()));
                }
                continue;
            }
            if (policy.routingMode() == RoutingMode.LISTEN_ONLY && !speakerCapabilities.broadcast()) {
                if (policy.exclusive()) return VoiceRoutingResult.discard(true, Optional.of(channel.id()));
                continue;
            }

            Set<UUID> recipients = groupTransmission
                    ? resolveGroupRecipients(speakerId, channel)
                    : resolveRecipients(speakerId, channel);
            boolean cancelDefault = policy.exclusive() ||
                    policy.routingMode() == RoutingMode.PRIVATE_CHANNEL ||
                    policy.routingMode() == RoutingMode.BROADCAST ||
                    forceChannelOnly;
            return new VoiceRoutingResult(cancelDefault, recipients, false, Optional.of(channel.id()));
        }

        if (forceChannelOnly) {
            return VoiceRoutingResult.discard(true, Optional.empty());
        }

        return VoiceRoutingResult.defaultRoute();
    }

    public VoiceRoutingResult route(UUID speakerId, SourceAudioPacket packet) {
        return route(createRoutingContext(speakerId, () -> packet), false);
    }

    public VoiceRoutingResult routeGroup(UUID speakerId) {
        return routeGroup(speakerId, null);
    }

    public VoiceRoutingResult routeGroup(UUID speakerId, Object rawPacket) {
        VoiceRoutingResult result = route(createRoutingContext(speakerId, () -> rawPacket), true);
        if (result.selectedChannel().isEmpty() && !result.discardPacket()) {
            return VoiceRoutingResult.discard(false, Optional.empty());
        }
        return result;
    }

    @Override
    public boolean selectGroupChannel(UUID playerId, VoiceChannelId channelId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(channelId, "channelId");
        ChannelState channel = channels.get(channelId);
        if (channel == null || !channel.active) return false;
        VoiceMembership membership = channel.members.get(playerId);
        if (membership == null || !membership.canTransmit() || !membership.capabilities().transmit()) return false;
        DynamicVoiceRouteGuard guard = routeGuard;
        if (guard != null && !guard.canTransmit(channelId, playerId)) {
            guard.onRejected(channelId, playerId, "cannot_speak");
            return false;
        }
        VoiceChannelId previousChannel = selectedChannelByPlayer.put(playerId, channelId);
        if (previousChannel != null && !previousChannel.equals(channelId)) {
            voiceServer.removeGroupSourcesOwnedBy(playerId);
        }
        syncGroupRoutingMode(playerId);
        return true;
    }

    @Override
    public void clearGroupChannelSelection(UUID playerId) {
        if (playerId == null) return;
        if (selectedChannelByPlayer.remove(playerId) != null) {
            voiceServer.removeGroupSourcesOwnedBy(playerId);
        }
        sendGroupRoutingMode(playerId, false);
    }

    private void clearGroupChannelSelection(UUID playerId, VoiceChannelId expectedChannel) {
        if (playerId == null || expectedChannel == null) return;
        if (selectedChannelByPlayer.remove(playerId, expectedChannel)) {
            voiceServer.removeGroupSourcesOwnedBy(playerId);
            sendGroupRoutingMode(playerId, false);
        }
    }

    private void clearSelectionsForChannel(VoiceChannelId channelId) {
        selectedChannelByPlayer.entrySet().stream()
                .filter(entry -> entry.getValue().equals(channelId))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(playerId -> clearGroupChannelSelection(playerId, channelId));
    }

    private void syncGroupRoutingMode(UUID playerId) {
        sendGroupRoutingMode(
                playerId,
                proximityGroupReplacementEnabled && selectedChannelByPlayer.containsKey(playerId)
        );
    }

    private void sendGroupRoutingMode(UUID playerId, boolean replaceProximityWithGroup) {
        if (voiceServer.getPlayerManager() == null) return;
        voiceServer.getPlayerManager()
                .getPlayerById(playerId, false)
                .ifPresent(player -> player.sendPacket(
                        new GroupRoutingModePacket(replaceProximityWithGroup)
                ));
    }

    @Override
    public Optional<VoiceChannelId> selectedGroupChannel(UUID playerId) {
        return Optional.ofNullable(selectedChannelByPlayer.get(playerId));
    }

    public void notifyGroupTransmissionStart(UUID speakerId, VoiceChannelId channelId) {
        publicServerApi.notifyTransmissionStart(speakerId, channelId);
    }

    public void notifyGroupTransmissionStop(UUID speakerId, VoiceChannelId channelId, String reason) {
        publicServerApi.notifyTransmissionStop(
                speakerId,
                channelId,
                "timeout".equals(reason)
                        ? net.mytrix.voice.api.event.TransmissionStopReason.TIMEOUT
                        : net.mytrix.voice.api.event.TransmissionStopReason.CLIENT_STOPPED
        );
    }

    private VoiceRoutingContext createRoutingContext(UUID speakerId, VoicePacket packet) {
        Set<VoiceChannelId> channelIds = Set.copyOf(channelsByPlayer.getOrDefault(speakerId, Set.of()));
        List<VoiceChannelSnapshot> memberships = channelIds.stream()
                .map(channels::get)
                .filter(Objects::nonNull)
                .map(ChannelState::snapshot)
                .collect(Collectors.toUnmodifiableList());
        List<VoiceRestrictionSnapshot> applicableRestrictions = collectRestrictions(speakerId, channelIds).stream()
                .map(RestrictionState::snapshot)
                .collect(Collectors.toUnmodifiableList());
        return new VoiceRoutingContext(speakerId, packet, memberships, applicableRestrictions);
    }

    @EventSubscribe(priority = EventPriority.LOWEST)
    public void onUdpClientConnected(UdpClientConnectedEvent event) {
        UUID playerId = event.getConnection().getPlayer().getInstance().getUuid();
        connectedPlayers.add(playerId);
        syncGroupRoutingMode(playerId);
    }

    @EventSubscribe
    public void onUdpClientDisconnected(UdpClientDisconnectedEvent event) {
        UUID playerId = event.getConnection().getPlayer().getInstance().getUuid();
        connectedPlayers.remove(playerId);
        stopSourcesOwnedBy(playerId);
    }

    @EventSubscribe(priority = EventPriority.LOWEST)
    public void onServerSourceAudioPacket(ServerSourceAudioPacketEvent event) {
        // Dynamic group voice is routed by the explicit "group" activation.
        // Proximity packets are intentionally left to VoiceServerProximitySource so nearby voice does not get duplicated.
    }

    private Set<UUID> resolveRecipients(UUID speakerId, VoiceChannelSnapshot channel) {
        Set<UUID> recipients = new HashSet<>();
        double maximumDistance = channel.policy().maximumDistance();
        for (UUID member : channel.members()) {
            if (!channel.policy().allowSelfMonitoring() && member.equals(speakerId)) continue;
            if (!connectedPlayers.contains(member)) continue;

            VoiceCapabilities capabilities = channel.capabilities().get(member);
            if (capabilities == null || !capabilities.receive()) continue;
            if (!capabilities.bypassChannelMute() && blocksReceive(member, channel.id())) continue;
            DynamicVoiceRouteGuard guard = routeGuard;
            if (guard != null && !guard.canReceive(channel.id(), member, speakerId)) {
                guard.onRejected(channel.id(), speakerId, "cannot_listen");
                continue;
            }
            if (maximumDistance > 0D && !withinDistance(speakerId, member, maximumDistance)) continue;
            recipients.add(member);
        }
        return Set.copyOf(recipients);
    }

    private Set<UUID> resolveGroupRecipients(UUID speakerId, VoiceChannelSnapshot channel) {
        Set<UUID> recipients = new HashSet<>();
        for (UUID member : channel.members()) {
            if (!channel.policy().allowSelfMonitoring() && member.equals(speakerId)) continue;
            if (!connectedPlayers.contains(member)) continue;

            VoiceCapabilities capabilities = channel.capabilities().get(member);
            if (capabilities == null || !capabilities.receive()) continue;
            if (!capabilities.bypassChannelMute() && blocksReceive(member, channel.id())) continue;
            DynamicVoiceRouteGuard guard = routeGuard;
            if (guard != null && !guard.canReceive(channel.id(), member, speakerId)) {
                guard.onRejected(channel.id(), speakerId, "cannot_listen");
                continue;
            }
            recipients.add(member);
        }
        return Set.copyOf(recipients);
    }

    private boolean withinDistance(UUID speakerId, UUID recipientId, double maximumDistance) {
        Optional<UdpServerConnection> speakerConnection = voiceServer.getUdpConnectionManager().getConnectionByPlayerId(speakerId);
        Optional<UdpServerConnection> recipientConnection = voiceServer.getUdpConnectionManager().getConnectionByPlayerId(recipientId);
        if (speakerConnection.isEmpty() || recipientConnection.isEmpty()) return false;

        ServerPos3d speakerPosition = new ServerPos3d();
        ServerPos3d recipientPosition = new ServerPos3d();
        speakerConnection.get().getPlayer().getInstance().getServerPosition(speakerPosition);
        recipientConnection.get().getPlayer().getInstance().getServerPosition(recipientPosition);

        if (!Objects.equals(speakerPosition.getWorld(), recipientPosition.getWorld())) return false;
        return speakerPosition.distanceSquared(recipientPosition) <= maximumDistance * maximumDistance;
    }

    private boolean blocksTransmit(Collection<VoiceRestrictionSnapshot> restrictions) {
        return restrictions.stream().anyMatch(restriction ->
                restriction.type() == VoiceRestrictionType.BLOCK_TRANSMIT ||
                        restriction.type() == VoiceRestrictionType.BLOCK_BOTH
        );
    }

    private boolean blocksReceive(UUID playerId, VoiceChannelId channelId) {
        return collectRestrictionsForChannel(playerId, channelId).stream().anyMatch(restriction ->
                restriction.request.type() == VoiceRestrictionType.BLOCK_RECEIVE ||
                        restriction.request.type() == VoiceRestrictionType.BLOCK_BOTH
        );
    }

    private Collection<RestrictionState> collectRestrictions(UUID playerId, Collection<VoiceChannelId> channelIds) {
        cleanupExpiredRestrictions();
        Set<VoiceRestrictionHandle> handles = new HashSet<>(globalRestrictions);
        handles.addAll(restrictionsByPlayer.getOrDefault(playerId, Set.of()));
        for (VoiceChannelId channelId : channelIds) {
            handles.addAll(restrictionsByChannel.getOrDefault(channelId, Set.of()));
            handles.addAll(restrictionsBySession.getOrDefault(channelId.sessionId(), Set.of()));
        }
        return handles.stream()
                .map(restrictions::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt((RestrictionState restriction) -> restriction.request.priority()).reversed())
                .collect(Collectors.toUnmodifiableList());
    }

    private Collection<RestrictionState> collectRestrictionsForChannel(UUID playerId, VoiceChannelId channelId) {
        cleanupExpiredRestrictions();
        Set<VoiceRestrictionHandle> handles = new HashSet<>(globalRestrictions);
        handles.addAll(restrictionsByPlayer.getOrDefault(playerId, Set.of()));
        handles.addAll(restrictionsByChannel.getOrDefault(channelId, Set.of()));
        handles.addAll(restrictionsBySession.getOrDefault(channelId.sessionId(), Set.of()));
        return handles.stream()
                .map(restrictions::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt((RestrictionState restriction) -> restriction.request.priority()).reversed())
                .collect(Collectors.toUnmodifiableList());
    }

    private void cleanupExpiredRestrictions() {
        long now = System.currentTimeMillis();
        for (RestrictionState restriction : new ArrayList<>(restrictions.values())) {
            if (restriction.expiresAtMillis > 0L && restriction.expiresAtMillis <= now) {
                removeRestriction(restriction.handle);
            }
        }
    }

    private void cleanupTargetSources(RestrictionTarget target, VoiceRestrictionType type) {
        if (type != VoiceRestrictionType.BLOCK_TRANSMIT &&
                type != VoiceRestrictionType.BLOCK_RECEIVE &&
                type != VoiceRestrictionType.BLOCK_BOTH &&
                type != VoiceRestrictionType.DISABLE_PROXIMITY &&
                type != VoiceRestrictionType.FORCE_CHANNEL_ONLY) {
            return;
        }

        if (target instanceof RestrictionTarget.Channel channelTarget) {
            voiceServer.removeGroupSourcesForChannel(channelTarget.channelId());
            return;
        }
        if (target instanceof RestrictionTarget.Session sessionTarget) {
            SessionState session = sessions.get(sessionTarget.sessionId());
            if (session != null) {
                session.channels.forEach(voiceServer::removeGroupSourcesForChannel);
            }
            return;
        }

        for (UUID playerId : playersForTarget(target)) {
            stopSourcesOwnedBy(playerId);
        }
    }

    private Set<UUID> playersForTarget(RestrictionTarget target) {
        if (target instanceof RestrictionTarget.Player player) {
            return Set.of(player.playerId());
        }
        if (target instanceof RestrictionTarget.Channel channelTarget) {
            ChannelState channel = channels.get(channelTarget.channelId());
            return channel == null ? Set.of() : Set.copyOf(channel.members.keySet());
        }
        if (target instanceof RestrictionTarget.Session sessionTarget) {
            SessionState session = sessions.get(sessionTarget.sessionId());
            if (session == null) return Set.of();
            return session.channels.stream()
                    .map(channels::get)
                    .filter(Objects::nonNull)
                    .flatMap(channel -> channel.members.keySet().stream())
                    .collect(Collectors.toUnmodifiableSet());
        }
        return Set.copyOf(connectedPlayers);
    }

    private boolean stopSourcesOwnedBy(UUID playerId) {
        boolean removedProximity = voiceServer.removeProximitySourceOwnedBy(playerId);
        boolean removedGroup = voiceServer.removeGroupSourcesOwnedBy(playerId);
        return removedProximity || removedGroup;
    }

    private void indexRestriction(RestrictionState restriction) {
        RestrictionTarget target = restriction.request.target();
        if (target instanceof RestrictionTarget.Player player) {
            restrictionsByPlayer.computeIfAbsent(player.playerId(), ignored -> ConcurrentHashMap.newKeySet()).add(restriction.handle);
        } else if (target instanceof RestrictionTarget.Channel channel) {
            restrictionsByChannel.computeIfAbsent(channel.channelId(), ignored -> ConcurrentHashMap.newKeySet()).add(restriction.handle);
        } else if (target instanceof RestrictionTarget.Session session) {
            restrictionsBySession.computeIfAbsent(session.sessionId(), ignored -> ConcurrentHashMap.newKeySet()).add(restriction.handle);
        } else {
            globalRestrictions.add(restriction.handle);
        }
    }

    private void unindexRestriction(RestrictionState restriction) {
        RestrictionTarget target = restriction.request.target();
        if (target instanceof RestrictionTarget.Player player) {
            removeFromIndex(restrictionsByPlayer, player.playerId(), restriction.handle);
        } else if (target instanceof RestrictionTarget.Channel channel) {
            removeFromIndex(restrictionsByChannel, channel.channelId(), restriction.handle);
        } else if (target instanceof RestrictionTarget.Session session) {
            removeFromIndex(restrictionsBySession, session.sessionId(), restriction.handle);
        } else {
            globalRestrictions.remove(restriction.handle);
        }
    }

    private <K> void removeFromIndex(ConcurrentMap<K, Set<VoiceRestrictionHandle>> index, K key, VoiceRestrictionHandle handle) {
        Set<VoiceRestrictionHandle> handles = index.get(key);
        if (handles == null) return;
        handles.remove(handle);
        if (handles.isEmpty()) index.remove(key, handles);
    }

    private void addPlayerIndex(UUID playerId, VoiceChannelId channelId) {
        channelsByPlayer.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(channelId);
    }

    private void removePlayerIndex(UUID playerId, VoiceChannelId channelId) {
        Set<VoiceChannelId> channelIds = channelsByPlayer.get(playerId);
        if (channelIds == null) return;
        channelIds.remove(channelId);
        if (channelIds.isEmpty()) channelsByPlayer.remove(playerId, channelIds);
    }

    private VoiceMembership toMembership(VoiceChannelId channelId, VoiceMemberDefinition definition) {
        return new VoiceMembership(
                definition.playerId(),
                channelId,
                definition.role(),
                definition.canTransmit(),
                definition.canReceive(),
                definition.capabilities()
        );
    }

    private SessionState getSessionOrThrow(VoiceSessionId sessionId) {
        SessionState session = sessions.get(sessionId);
        if (session == null) throw new VoiceSessionNotFoundException("Session not found: " + sessionId);
        return session;
    }

    private ChannelState getChannelOrThrow(VoiceChannelId channelId) {
        ChannelState channel = channels.get(channelId);
        if (channel == null) throw new VoiceChannelNotFoundException("Channel not found: " + channelId);
        return channel;
    }

    private void requireRegistered() {
        if (!registered) throw new VoiceApiUnavailableException("Dynamic voice API is not registered");
    }

    private void fire(DynamicVoiceEvent event) {
        listeners.forEach(listener -> listener.onDynamicVoiceEvent(event));
    }

    private void fireRouteResolved(VoiceRoutingContext context, VoiceRoutingResult result) {
        if (debugRouting) fire(new VoiceRouteResolvedEvent(context, result, Instant.now()));
    }

    private void fireRouteRejected(VoiceRoutingContext context, String reason) {
        if (debugRouting) fire(new VoiceRouteRejectedEvent(context, reason, Instant.now()));
    }

    private String formatTarget(RestrictionTarget target) {
        if (target instanceof RestrictionTarget.Player player) return "player:" + player.playerId();
        if (target instanceof RestrictionTarget.Channel channel) return "channel:" + channel.channelId();
        if (target instanceof RestrictionTarget.Session session) return "session:" + session.sessionId();
        return "global";
    }

    private Comparator<VoiceChannelSnapshot> channelComparator() {
        return Comparator
                .comparingInt((VoiceChannelSnapshot channel) -> channel.policy().priority())
                .reversed()
                .thenComparing(channel -> channel.id().toString());
    }

    private void logInfo(String message, Object... args) {
        try {
            BaseVoice.LOGGER.info(message, args);
        } catch (Throwable ignored) {
        }
    }

    private final class OwnerState implements VoiceOwnerContext {
        private final String namespace;
        private final String version;

        private OwnerState(String namespace, String version) {
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
            return DynamicVoiceService.this;
        }
    }

    private static final class SessionState implements VoiceSession {
        private final VoiceSessionId id;
        private final VoiceSessionOptions options;
        private final Set<VoiceChannelId> channels = ConcurrentHashMap.newKeySet();
        private volatile VoiceSessionState state = VoiceSessionState.CREATED;

        private SessionState(VoiceSessionId id, VoiceSessionOptions options) {
            this.id = id;
            this.options = options;
        }

        @Override
        public VoiceSessionId id() {
            return id;
        }

        @Override
        public VoiceSessionState state() {
            return state;
        }

        @Override
        public VoiceSessionOptions options() {
            return options;
        }

        @Override
        public Set<VoiceChannelId> channels() {
            return Set.copyOf(channels);
        }

        private VoiceSessionSnapshot snapshot() {
            return new VoiceSessionSnapshot(id, state, channels, options, options.metadata());
        }
    }

    private static final class ChannelState implements VoiceChannel {
        private final VoiceChannelId id;
        private final ConcurrentMap<UUID, VoiceMembership> members = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, String> metadata = new ConcurrentHashMap<>();
        private volatile VoiceChannelPolicy policy;
        private volatile boolean active;

        private ChannelState(VoiceChannelId id, VoiceChannelPolicy policy, boolean active, Map<String, String> metadata) {
            this.id = id;
            this.policy = policy;
            this.active = active;
            this.metadata.putAll(metadata);
        }

        @Override
        public VoiceChannelId id() {
            return id;
        }

        @Override
        public VoiceSessionId sessionId() {
            return id.sessionId();
        }

        @Override
        public Set<UUID> members() {
            return Set.copyOf(members.keySet());
        }

        @Override
        public VoiceChannelPolicy policy() {
            return policy;
        }

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public Map<String, String> metadata() {
            return Map.copyOf(metadata);
        }

        private VoiceChannelSnapshot snapshot() {
            Map<UUID, VoiceCapabilities> capabilities = new HashMap<>();
            Map<UUID, String> roles = new HashMap<>();
            members.forEach((playerId, membership) -> {
                capabilities.put(playerId, membership.capabilities());
                roles.put(playerId, membership.role());
            });
            return new VoiceChannelSnapshot(id, active, policy, members.keySet(), capabilities, roles, metadata);
        }
    }

    private static final class RestrictionState {
        private final VoiceRestrictionHandle handle;
        private final VoiceRestrictionRequest request;
        private final long expiresAtMillis;

        private RestrictionState(VoiceRestrictionHandle handle, VoiceRestrictionRequest request) {
            this.handle = handle;
            this.request = request;
            this.expiresAtMillis = request.duration()
                    .map(duration -> System.currentTimeMillis() + duration.toMillis())
                    .orElse(0L);
        }

        private VoiceRestrictionSnapshot snapshot() {
            return new VoiceRestrictionSnapshot(
                    handle,
                    request.ownerNamespace(),
                    request.reason(),
                    request.target(),
                    request.type(),
                    request.priority(),
                    expiresAtMillis <= 0L ? Optional.empty() : Optional.of(Instant.ofEpochMilli(expiresAtMillis))
            );
        }
    }
}
