package su.plo.voice.server.api;

import net.mytrix.voice.api.ApiVersion;
import net.mytrix.voice.api.MytrixVoiceServices;
import net.mytrix.voice.api.RoutingMode;
import net.mytrix.voice.api.VoiceApiState;
import net.mytrix.voice.api.VoiceCapability;
import net.mytrix.voice.api.VoiceCapabilities;
import net.mytrix.voice.api.VoiceChannelOptions;
import net.mytrix.voice.api.VoiceChannelPolicy;
import net.mytrix.voice.api.VoiceChatApiVersions;
import net.mytrix.voice.api.VoiceMemberDefinition;
import net.mytrix.voice.api.VoiceSessionId;
import net.mytrix.voice.api.VoiceSessionOptions;
import net.mytrix.voice.api.channel.DimensionPolicy;
import net.mytrix.voice.api.channel.MembershipResult;
import net.mytrix.voice.api.channel.MembershipStatus;
import net.mytrix.voice.api.channel.RegistrationResult;
import net.mytrix.voice.api.channel.RegistrationStatus;
import net.mytrix.voice.api.channel.UpdateResult;
import net.mytrix.voice.api.channel.UpdateStatus;
import net.mytrix.voice.api.channel.VoiceChannelConfig;
import net.mytrix.voice.api.channel.VoiceChannelDefinition;
import net.mytrix.voice.api.channel.VoiceChannelHandle;
import net.mytrix.voice.api.channel.VoiceChannelId;
import net.mytrix.voice.api.channel.VoiceChannelMode;
import net.mytrix.voice.api.channel.VoiceChannelRegistry;
import net.mytrix.voice.api.channel.VoiceChannelView;
import net.mytrix.voice.api.channel.VoicePlayerContext;
import net.mytrix.voice.api.channel.VoiceSpatialMode;
import net.mytrix.voice.api.channel.VoiceTransmissionPolicy;
import net.mytrix.voice.api.event.Subscription;
import net.mytrix.voice.api.event.TransmissionStopReason;
import net.mytrix.voice.api.event.VoiceChannelMemberJoinedEvent;
import net.mytrix.voice.api.event.VoiceChannelMemberLeftEvent;
import net.mytrix.voice.api.event.VoiceChannelRegisteredEvent;
import net.mytrix.voice.api.event.VoiceChannelUnregisteredEvent;
import net.mytrix.voice.api.event.VoiceEvent;
import net.mytrix.voice.api.event.VoiceEventBus;
import net.mytrix.voice.api.event.VoiceEventListener;
import net.mytrix.voice.api.event.VoiceFrameRejectedEvent;
import net.mytrix.voice.api.event.VoiceRejectionReason;
import net.mytrix.voice.api.event.VoiceTransmissionStartedEvent;
import net.mytrix.voice.api.event.VoiceTransmissionStoppedEvent;
import net.mytrix.voice.api.player.VoicePlayerService;
import net.mytrix.voice.api.player.VoicePlayerView;
import net.mytrix.voice.api.server.ServerVoiceApi;
import net.mytrix.voice.api.transmission.TransmissionSelectionResult;
import net.mytrix.voice.api.transmission.TransmissionSelectionStatus;
import net.mytrix.voice.api.transmission.VoiceTransmissionService;
import su.plo.voice.BaseVoice;
import su.plo.voice.server.dynamic.DynamicVoiceRouteGuard;
import su.plo.voice.server.dynamic.DynamicVoiceService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Internal implementation of the public server API.
 */
public final class MytrixServerVoiceApi implements ServerVoiceApi, DynamicVoiceRouteGuard {

    private static final String INTERNAL_SESSION_VALUE = "api";
    private static final int DEFAULT_API_CHANNEL_PRIORITY = 600;
    private static final long CALLBACK_LOG_INTERVAL_MS = 10_000L;

    private final DynamicVoiceService dynamicVoiceService;
    private final ApiChannelRegistry channelRegistry = new ApiChannelRegistry();
    private final ApiPlayerService playerService = new ApiPlayerService();
    private final ApiTransmissionService transmissionService = new ApiTransmissionService();
    private final SafeVoiceEventBus eventBus = new SafeVoiceEventBus();
    private final Set<VoiceCapability> capabilities = EnumSet.of(
            VoiceCapability.PROXIMITY_CHANNELS,
            VoiceCapability.GROUP_CHANNELS,
            VoiceCapability.CROSS_DIMENSION_CHANNELS,
            VoiceCapability.NON_SPATIAL_AUDIO,
            VoiceCapability.CUSTOM_CHANNEL_PROVIDERS,
            VoiceCapability.VOICE_EVENTS,
            VoiceCapability.SERVER_CHANNEL_SELECTION
    );
    private final ConcurrentMap<VoiceChannelId, ChannelRecord> channelsByPublicId = new ConcurrentHashMap<>();
    private final ConcurrentMap<net.mytrix.voice.api.VoiceChannelId, VoiceChannelId> publicIdByInternalId = new ConcurrentHashMap<>();
    private final Set<VoiceSessionId> apiSessions = ConcurrentHashMap.newKeySet();
    private final Set<TransmissionKey> activeTransmissions = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Long> lastCallbackErrorMillis = new ConcurrentHashMap<>();

    private volatile VoiceApiState state = VoiceApiState.INITIALIZING;

    public MytrixServerVoiceApi(DynamicVoiceService dynamicVoiceService) {
        this.dynamicVoiceService = Objects.requireNonNull(dynamicVoiceService, "dynamicVoiceService");
    }

    public void registerService() {
        MytrixVoiceServices.register(ServerVoiceApi.class, this);
        dynamicVoiceService.setRouteGuard(this);
        state = VoiceApiState.INITIALIZING;
        logInfo("[MytrixVoice] Public server voice API registered");
    }

    public void ready() {
        state = VoiceApiState.READY;
    }

    public void shutdown() {
        state = VoiceApiState.STOPPING;
        for (ChannelRecord record : new ArrayList<>(channelsByPublicId.values())) {
            record.close();
        }
        eventBus.clear();
        activeTransmissions.clear();
        dynamicVoiceService.setRouteGuard(null);
        MytrixVoiceServices.unregister(ServerVoiceApi.class, this);
        state = VoiceApiState.STOPPED;
    }

    public void notifyTransmissionStart(UUID speakerId, net.mytrix.voice.api.VoiceChannelId internalChannelId) {
        VoiceChannelId publicId = publicIdByInternalId.get(internalChannelId);
        if (publicId == null) return;
        TransmissionKey key = new TransmissionKey(speakerId, publicId);
        if (activeTransmissions.add(key)) {
            eventBus.fire(new VoiceTransmissionStartedEvent(speakerId, publicId));
        }
    }

    public void notifyTransmissionStop(UUID speakerId, net.mytrix.voice.api.VoiceChannelId internalChannelId, TransmissionStopReason reason) {
        VoiceChannelId publicId = publicIdByInternalId.get(internalChannelId);
        if (publicId == null) return;
        TransmissionKey key = new TransmissionKey(speakerId, publicId);
        if (activeTransmissions.remove(key)) {
            eventBus.fire(new VoiceTransmissionStoppedEvent(speakerId, publicId, reason));
        }
    }

    @Override
    public ApiVersion version() {
        return VoiceChatApiVersions.CURRENT;
    }

    @Override
    public boolean supports(VoiceCapability capability) {
        return capabilities.contains(capability);
    }

    @Override
    public VoiceApiState state() {
        return state;
    }

    @Override
    public VoiceChannelRegistry channels() {
        return channelRegistry;
    }

    @Override
    public VoicePlayerService players() {
        return playerService;
    }

    @Override
    public VoiceTransmissionService transmissions() {
        return transmissionService;
    }

    @Override
    public VoiceEventBus events() {
        return eventBus;
    }

    @Override
    public boolean canTransmit(net.mytrix.voice.api.VoiceChannelId channelId, UUID speakerId) {
        ChannelRecord record = recordByInternalId(channelId);
        if (record == null) return true;
        if (record.closed) return false;
        if (!record.definition.config().allowTransmission()) return false;
        if (!record.members.contains(speakerId)) return false;
        return safeCanSpeak(record, speakerId);
    }

    @Override
    public boolean canReceive(net.mytrix.voice.api.VoiceChannelId channelId, UUID listenerId, UUID speakerId) {
        ChannelRecord record = recordByInternalId(channelId);
        if (record == null) return true;
        if (record.closed) return false;
        if (!record.members.contains(listenerId)) return false;
        return safeCanListen(record, listenerId, speakerId);
    }

    @Override
    public void onRejected(net.mytrix.voice.api.VoiceChannelId channelId, UUID speakerId, String reason) {
        VoiceChannelId publicId = publicIdByInternalId.get(channelId);
        VoiceRejectionReason publicReason = switch (reason) {
            case "cannot_speak" -> VoiceRejectionReason.CANNOT_SPEAK;
            case "cannot_listen" -> VoiceRejectionReason.CANNOT_LISTEN;
            case "not_a_member" -> VoiceRejectionReason.NOT_A_MEMBER;
            default -> VoiceRejectionReason.UNKNOWN;
        };
        eventBus.fire(new VoiceFrameRejectedEvent(speakerId, Optional.ofNullable(publicId), publicReason));
    }

    private ChannelRecord recordByInternalId(net.mytrix.voice.api.VoiceChannelId internalId) {
        VoiceChannelId publicId = publicIdByInternalId.get(internalId);
        return publicId == null ? null : channelsByPublicId.get(publicId);
    }

    private boolean isReadyForMutations() {
        return state == VoiceApiState.INITIALIZING || state == VoiceApiState.READY;
    }

    private VoiceSessionId sessionIdFor(String namespace) {
        return VoiceSessionId.of(namespace, INTERNAL_SESSION_VALUE);
    }

    private net.mytrix.voice.api.VoiceChannelId internalIdFor(VoiceChannelId publicId) {
        return net.mytrix.voice.api.VoiceChannelId.of(sessionIdFor(publicId.namespace()), sanitizePath(publicId.path()));
    }

    private String sanitizePath(String path) {
        String safe = path.replace('/', '.');
        if (safe.length() <= 120) return safe;
        return safe.substring(0, 100) + "." + Integer.toUnsignedString(path.hashCode(), 16);
    }

    private void ensureApiSession(String namespace) {
        VoiceSessionId sessionId = sessionIdFor(namespace);
        if (!apiSessions.add(sessionId)) return;
        if (dynamicVoiceService.findSession(sessionId).isEmpty()) {
            dynamicVoiceService.createSession(
                    sessionId,
                    VoiceSessionOptions.builder()
                            .metadata(Map.of("api", "public", "owner", namespace))
                            .build()
            );
        }
        dynamicVoiceService.activateSession(sessionId);
    }

    private VoiceChannelPolicy toDynamicPolicy(VoiceChannelConfig config) {
        if (config.channelMode() == VoiceChannelMode.PROXIMITY) {
            return VoiceChannelPolicy.proximity();
        }

        boolean exclusive = config.transmissionPolicy() == VoiceTransmissionPolicy.EXCLUSIVE;
        boolean spatial = config.spatialMode() == VoiceSpatialMode.POSITIONAL;
        double maximumDistance = config.dimensionPolicy() == DimensionPolicy.CROSS_DIMENSION ? 0D : 0D;
        RoutingMode mode = switch (config.channelMode()) {
            case GROUP -> RoutingMode.PRIVATE_CHANNEL;
            case GLOBAL -> RoutingMode.BROADCAST;
            case CUSTOM -> RoutingMode.PRIVATE_CHANNEL;
            case PROXIMITY -> RoutingMode.PROXIMITY;
        };
        return new VoiceChannelPolicy(mode, exclusive, false, false, DEFAULT_API_CHANNEL_PRIORITY, maximumDistance, spatial);
    }

    private VoiceChannelOptions toDynamicOptions(VoiceChannelDefinition definition) {
        return VoiceChannelOptions.builder()
                .policy(toDynamicPolicy(definition.config()))
                .active(true)
                .metadata(Map.of(
                        "api.public_id", definition.id().toString(),
                        "api.owner", definition.owner().modId(),
                        "api.display_name", definition.displayName(),
                        "api.spatial_mode", definition.config().spatialMode().name(),
                        "api.dimension_policy", definition.config().dimensionPolicy().name(),
                        "api.base_volume", Float.toString(definition.config().baseVolume())
                ))
                .build();
    }

    private boolean safeCanJoin(ChannelRecord record, UUID playerId) {
        try {
            return record.definition.permission().canJoin(new VoicePlayerContext(playerId), record.definition.context());
        } catch (RuntimeException exception) {
            logCallbackError(record, "canJoin", exception);
            return false;
        }
    }

    private boolean safeCanSpeak(ChannelRecord record, UUID playerId) {
        try {
            return record.definition.permission().canSpeak(new VoicePlayerContext(playerId), record.definition.context());
        } catch (RuntimeException exception) {
            logCallbackError(record, "canSpeak", exception);
            return false;
        }
    }

    private boolean safeCanListen(ChannelRecord record, UUID listenerId, UUID speakerId) {
        try {
            return record.definition.permission().canListen(
                    new VoicePlayerContext(listenerId),
                    new VoicePlayerContext(speakerId),
                    record.definition.context()
            );
        } catch (RuntimeException exception) {
            logCallbackError(record, "canListen", exception);
            return false;
        }
    }

    private void logCallbackError(ChannelRecord record, String operation, RuntimeException exception) {
        String key = record.definition.id() + ":" + operation;
        long now = System.currentTimeMillis();
        Long previous = lastCallbackErrorMillis.put(key, now);
        if (previous == null || now - previous >= CALLBACK_LOG_INTERVAL_MS) {
            logWarn(
                    "[MytrixVoice] Public API callback failed owner={} channel={} operation={}: {}",
                    record.definition.owner().modId(),
                    record.definition.id(),
                    operation,
                    exception.toString()
            );
        }
    }

    private final class ApiChannelRegistry implements VoiceChannelRegistry {

        @Override
        public RegistrationResult register(VoiceChannelDefinition definition) {
            if (!isReadyForMutations()) {
                return RegistrationResult.failure(RegistrationStatus.API_NOT_READY, "API state is " + state);
            }

            final VoiceChannelDefinition safeDefinition;
            try {
                safeDefinition = Objects.requireNonNull(definition, "definition");
            } catch (RuntimeException exception) {
                return RegistrationResult.failure(RegistrationStatus.INVALID_DEFINITION, exception.getMessage());
            }

            if (channelsByPublicId.containsKey(safeDefinition.id())) {
                return RegistrationResult.failure(RegistrationStatus.ALREADY_REGISTERED, "Channel already registered: " + safeDefinition.id());
            }

            try {
                ensureApiSession(safeDefinition.id().namespace());
                net.mytrix.voice.api.VoiceChannelId internalId = internalIdFor(safeDefinition.id());
                ChannelRecord record = new ChannelRecord(safeDefinition, internalId);
                ChannelRecord existing = channelsByPublicId.putIfAbsent(safeDefinition.id(), record);
                if (existing != null) {
                    return RegistrationResult.failure(RegistrationStatus.ALREADY_REGISTERED, "Channel already registered: " + safeDefinition.id());
                }

                publicIdByInternalId.put(internalId, safeDefinition.id());
                dynamicVoiceService.createChannel(internalId.sessionId(), internalId, toDynamicOptions(safeDefinition));
                if (safeDefinition.config().membershipMode() == net.mytrix.voice.api.channel.MembershipMode.DYNAMIC) {
                    record.refreshMembership();
                }
                eventBus.fire(new VoiceChannelRegisteredEvent(record.view()));
                logInfo("[MytrixVoice] API channel registered owner={} id={}", safeDefinition.owner().modId(), safeDefinition.id());
                return RegistrationResult.success(record);
            } catch (RuntimeException exception) {
                channelsByPublicId.remove(safeDefinition.id());
                publicIdByInternalId.remove(internalIdFor(safeDefinition.id()));
                return RegistrationResult.failure(RegistrationStatus.ERROR, exception.toString());
            }
        }

        @Override
        public boolean unregister(VoiceChannelId channelId) {
            ChannelRecord record = channelsByPublicId.get(channelId);
            if (record == null) return false;
            record.close();
            return true;
        }

        @Override
        public Optional<VoiceChannelView> find(VoiceChannelId channelId) {
            ChannelRecord record = channelsByPublicId.get(channelId);
            return record == null || record.closed ? Optional.empty() : Optional.of(record.view());
        }

        @Override
        public Collection<VoiceChannelView> channelsOwnedBy(String namespace) {
            return channelsByPublicId.values().stream()
                    .filter(record -> !record.closed)
                    .filter(record -> record.definition.owner().modId().equals(namespace))
                    .map(ChannelRecord::view)
                    .collect(Collectors.toUnmodifiableList());
        }

        @Override
        public MembershipResult invalidateMembership(VoiceChannelId channelId) {
            ChannelRecord record = channelsByPublicId.get(channelId);
            if (record == null) {
                return MembershipResult.of(MembershipStatus.CHANNEL_NOT_FOUND, channelId, new UUID(0L, 0L), "Channel not found");
            }
            return record.refreshMembership();
        }
    }

    private final class ApiPlayerService implements VoicePlayerService {

        @Override
        public Optional<VoicePlayerView> find(UUID playerId) {
            if (playerId == null) return Optional.empty();
            net.mytrix.voice.api.VoicePlayerSnapshot snapshot = dynamicVoiceService.inspectPlayer(playerId);
            Set<VoiceChannelId> publicChannels = snapshot.channels().stream()
                    .map(publicIdByInternalId::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toUnmodifiableSet());
            boolean speaking = activeTransmissions.stream().anyMatch(key -> key.playerId.equals(playerId));
            return Optional.of(new VoicePlayerView(
                    playerId,
                    snapshot.connected(),
                    !snapshot.canTransmit(),
                    !snapshot.canReceive(),
                    speaking,
                    publicChannels
            ));
        }

        @Override
        public boolean isVoiceAvailable(UUID playerId) {
            return find(playerId).map(VoicePlayerView::voiceEnabled).orElse(false);
        }

        @Override
        public boolean isSpeaking(UUID playerId) {
            return find(playerId).map(VoicePlayerView::speaking).orElse(false);
        }

        @Override
        public Set<VoiceChannelId> channelsOf(UUID playerId) {
            return find(playerId).map(VoicePlayerView::activeChannels).orElseGet(Set::of);
        }
    }

    private final class ApiTransmissionService implements VoiceTransmissionService {

        @Override
        public TransmissionSelectionResult selectChannel(UUID playerId, VoiceChannelId channelId) {
            if (state != VoiceApiState.READY) {
                return TransmissionSelectionResult.failure(TransmissionSelectionStatus.API_NOT_READY, playerId, channelId, "API state is " + state);
            }
            ChannelRecord record = channelsByPublicId.get(channelId);
            if (record == null || record.closed) {
                return TransmissionSelectionResult.failure(TransmissionSelectionStatus.CHANNEL_NOT_FOUND, playerId, channelId, "Channel not found");
            }
            if (!record.members.contains(playerId)) {
                return TransmissionSelectionResult.failure(TransmissionSelectionStatus.NOT_A_MEMBER, playerId, channelId, "Player is not a channel member");
            }
            if (!record.definition.config().allowTransmission() || !safeCanSpeak(record, playerId)) {
                eventBus.fire(new VoiceFrameRejectedEvent(playerId, Optional.of(channelId), VoiceRejectionReason.CANNOT_SPEAK));
                return TransmissionSelectionResult.failure(TransmissionSelectionStatus.CANNOT_SPEAK, playerId, channelId, "Player cannot speak in channel");
            }
            if (!dynamicVoiceService.selectGroupChannel(playerId, record.internalId)) {
                return TransmissionSelectionResult.failure(TransmissionSelectionStatus.REJECTED, playerId, channelId, "Internal channel selection rejected");
            }
            return TransmissionSelectionResult.selected(playerId, channelId);
        }

        @Override
        public TransmissionSelectionResult clearSelection(UUID playerId) {
            dynamicVoiceService.clearGroupChannelSelection(playerId);
            return TransmissionSelectionResult.cleared(playerId);
        }

        @Override
        public Optional<VoiceChannelId> selectedChannel(UUID playerId) {
            return dynamicVoiceService.selectedGroupChannel(playerId)
                    .map(publicIdByInternalId::get);
        }
    }

    private final class ChannelRecord implements VoiceChannelHandle {
        private final VoiceChannelDefinition definition;
        private final net.mytrix.voice.api.VoiceChannelId internalId;
        private final Set<UUID> members = ConcurrentHashMap.newKeySet();
        private volatile VoiceChannelConfig config;
        private volatile boolean closed;

        private ChannelRecord(VoiceChannelDefinition definition, net.mytrix.voice.api.VoiceChannelId internalId) {
            this.definition = definition;
            this.internalId = internalId;
            this.config = definition.config();
        }

        @Override
        public VoiceChannelId id() {
            return definition.id();
        }

        @Override
        public VoiceChannelConfig config() {
            return config;
        }

        @Override
        public MembershipResult addMember(UUID playerId) {
            Objects.requireNonNull(playerId, "playerId");
            if (closed) return MembershipResult.of(MembershipStatus.CHANNEL_CLOSED, id(), playerId, "Channel is closed");
            if (!safeCanJoin(this, playerId)) return MembershipResult.of(MembershipStatus.REJECTED, id(), playerId, "Permission rejected join");
            if (members.contains(playerId)) return MembershipResult.of(MembershipStatus.ALREADY_MEMBER, id(), playerId, "Already a member");
            if (members.size() >= config.maxMembers()) return MembershipResult.of(MembershipStatus.MEMBER_LIMIT_REACHED, id(), playerId, "Member limit reached");
            members.add(playerId);
            dynamicVoiceService.addMember(
                    internalId,
                    VoiceMemberDefinition.builder(playerId)
                            .role("api_member")
                            .capabilities(new VoiceCapabilities(config.allowTransmission(), true, false, false, false))
                            .build()
            );
            eventBus.fire(new VoiceChannelMemberJoinedEvent(id(), playerId));
            return MembershipResult.of(MembershipStatus.ADDED, id(), playerId, "added");
        }

        @Override
        public MembershipResult removeMember(UUID playerId) {
            Objects.requireNonNull(playerId, "playerId");
            if (closed) return MembershipResult.of(MembershipStatus.CHANNEL_CLOSED, id(), playerId, "Channel is closed");
            if (!members.remove(playerId)) return MembershipResult.of(MembershipStatus.NOT_A_MEMBER, id(), playerId, "Not a member");
            dynamicVoiceService.removeMember(internalId, playerId);
            if (dynamicVoiceService.selectedGroupChannel(playerId).filter(internalId::equals).isPresent()) {
                dynamicVoiceService.clearGroupChannelSelection(playerId);
            }
            eventBus.fire(new VoiceChannelMemberLeftEvent(id(), playerId));
            return MembershipResult.of(MembershipStatus.REMOVED, id(), playerId, "removed");
        }

        @Override
        public boolean contains(UUID playerId) {
            return members.contains(playerId);
        }

        @Override
        public Set<UUID> members() {
            return Set.copyOf(members);
        }

        @Override
        public void clearMembers() {
            for (UUID member : new ArrayList<>(members)) {
                removeMember(member);
            }
        }

        @Override
        public VoiceChannelView view() {
            return new VoiceChannelView(id(), definition.displayName(), config, definition.owner(), true, closed, members);
        }

        @Override
        public UpdateResult updateConfig(VoiceChannelConfig config) {
            if (closed) return new UpdateResult(UpdateStatus.CHANNEL_CLOSED, id(), "Channel is closed");
            try {
                this.config = Objects.requireNonNull(config, "config");
                dynamicVoiceService.updateChannel(
                        internalId,
                        net.mytrix.voice.api.VoiceChannelUpdate.builder()
                                .policy(toDynamicPolicy(config))
                                .build()
                );
                return new UpdateResult(UpdateStatus.UPDATED, id(), "updated");
            } catch (RuntimeException exception) {
                return new UpdateResult(UpdateStatus.INVALID_CONFIG, id(), exception.toString());
            }
        }

        @Override
        public MembershipResult refreshMembership() {
            if (closed) return MembershipResult.of(MembershipStatus.CHANNEL_CLOSED, id(), new UUID(0L, 0L), "Channel is closed");
            Collection<UUID> resolved;
            try {
                resolved = definition.membershipProvider().resolveMembers(definition.context());
            } catch (RuntimeException exception) {
                logCallbackError(this, "resolveMembers", exception);
                return MembershipResult.of(MembershipStatus.REJECTED, id(), new UUID(0L, 0L), "Membership provider failed");
            }
            Set<UUID> desired = resolved == null ? Set.of() : Set.copyOf(resolved);
            for (UUID existing : new ArrayList<>(members)) {
                if (!desired.contains(existing)) removeMember(existing);
            }
            for (UUID playerId : desired) {
                if (!members.contains(playerId)) addMember(playerId);
            }
            return MembershipResult.of(MembershipStatus.ADDED, id(), new UUID(0L, 0L), "membership refreshed");
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            for (UUID member : new ArrayList<>(members)) {
                dynamicVoiceService.clearGroupChannelSelection(member);
                eventBus.fire(new VoiceChannelMemberLeftEvent(id(), member));
            }
            members.clear();
            dynamicVoiceService.deleteChannel(internalId);
            publicIdByInternalId.remove(internalId);
            channelsByPublicId.remove(id(), this);
            activeTransmissions.removeIf(key -> key.channelId.equals(id()));
            eventBus.fire(new VoiceChannelUnregisteredEvent(id()));
            logInfo("[MytrixVoice] API channel closed owner={} id={}", definition.owner().modId(), id());
        }

        @Override
        public boolean closed() {
            return closed;
        }
    }

    private final class SafeVoiceEventBus implements VoiceEventBus {
        private final ConcurrentMap<Class<?>, CopyOnWriteArrayList<ListenerRegistration<?>>> listeners = new ConcurrentHashMap<>();

        @Override
        public <E extends VoiceEvent> Subscription subscribe(Class<E> eventType, VoiceEventListener<E> listener) {
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(listener, "listener");
            ListenerRegistration<E> registration = new ListenerRegistration<>(eventType, listener);
            listeners.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>()).add(registration);
            return registration;
        }

        private void fire(VoiceEvent event) {
            CopyOnWriteArrayList<ListenerRegistration<?>> registrations = listeners.get(event.getClass());
            if (registrations == null || registrations.isEmpty()) return;
            for (ListenerRegistration<?> registration : registrations) {
                registration.dispatch(event);
            }
        }

        private void clear() {
            listeners.values().forEach(list -> list.forEach(ListenerRegistration::close));
            listeners.clear();
        }

        private final class ListenerRegistration<E extends VoiceEvent> implements Subscription {
            private final Class<E> eventType;
            private final VoiceEventListener<E> listener;
            private volatile boolean active = true;

            private ListenerRegistration(Class<E> eventType, VoiceEventListener<E> listener) {
                this.eventType = eventType;
                this.listener = listener;
            }

            @Override
            public boolean active() {
                return active;
            }

            @Override
            public void close() {
                if (!active) return;
                active = false;
                CopyOnWriteArrayList<ListenerRegistration<?>> registrations = listeners.get(eventType);
                if (registrations != null) registrations.remove(this);
            }

            @SuppressWarnings("unchecked")
            private void dispatch(VoiceEvent event) {
                if (!active) return;
                try {
                    listener.onEvent((E) event);
                } catch (RuntimeException exception) {
                    logWarn("[MytrixVoice] Public API event listener failed for {}: {}", eventType.getName(), exception.toString());
                }
            }
        }
    }

    private void logInfo(String message, Object... args) {
        try {
            BaseVoice.LOGGER.info(message, args);
        } catch (Throwable ignored) {
        }
    }

    private void logWarn(String message, Object... args) {
        try {
            BaseVoice.LOGGER.warn(message, args);
        } catch (Throwable ignored) {
        }
    }

    private record TransmissionKey(UUID playerId, VoiceChannelId channelId) {
    }
}
