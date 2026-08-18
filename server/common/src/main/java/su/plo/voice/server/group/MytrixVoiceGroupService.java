package su.plo.voice.server.group;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.mytrix.voice.api.RoutingMode;
import net.mytrix.voice.api.VoiceCapabilities;
import net.mytrix.voice.api.VoiceChannelId;
import net.mytrix.voice.api.VoiceChannelOptions;
import net.mytrix.voice.api.VoiceChannelPolicy;
import net.mytrix.voice.api.VoiceMemberDefinition;
import net.mytrix.voice.api.VoiceSessionId;
import net.mytrix.voice.api.VoiceSessionOptions;
import su.plo.slib.api.chat.component.McTextComponent;
import su.plo.slib.api.server.entity.player.McServerPlayer;
import su.plo.voice.BaseVoice;
import su.plo.voice.api.event.EventSubscribe;
import su.plo.voice.api.server.event.connection.UdpClientConnectedEvent;
import su.plo.voice.server.BaseVoiceServer;
import su.plo.voice.server.dynamic.DynamicVoiceService;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Server-side user group layer backed by DynamicVoiceService.
 *
 * <p>This service intentionally manages membership and commands only. Audio
 * capture, encoding, routing and playback remain owned by the existing voice
 * runtime.</p>
 */
public final class MytrixVoiceGroupService {

    public static final VoiceSessionId GROUP_SESSION = VoiceSessionId.of("mytrixvoice", "player_groups");
    private static final int GROUP_PRIORITY = 650;
    private static final Duration INVITE_TTL = Duration.ofMinutes(5);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final BaseVoiceServer voiceServer;
    private final ConcurrentMap<UUID, GroupRecord> groupsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> groupIdByMember = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, PendingInvite> invitesByPlayer = new ConcurrentHashMap<>();

    private File storageFile;
    private volatile boolean initialized;
    private volatile boolean shuttingDown;
    private volatile boolean playerCommandsEnabled = true;

    public MytrixVoiceGroupService(BaseVoiceServer voiceServer) {
        this.voiceServer = Objects.requireNonNull(voiceServer, "voiceServer");
    }

    public void initialize() {
        this.storageFile = new File(new File(voiceServer.getConfigFolder(), "mytrix-groups"), "groups.json");
        File parent = storageFile.getParentFile();
        if (parent != null) parent.mkdirs();

        ensureSession();
        loadPersistentGroups();
        initialized = true;
        safeLogInfo("[MytrixVoice] Player voice groups initialized groups={}", groupsById.size());
    }

    public void shutdown() {
        shuttingDown = true;
        savePersistentGroups();
        for (GroupRecord group : new ArrayList<>(groupsById.values())) {
            deleteGroup(group, false);
        }
        groupsById.clear();
        groupIdByMember.clear();
        invitesByPlayer.clear();
        initialized = false;
        shuttingDown = false;
    }

    public boolean initialized() {
        return initialized;
    }

    public boolean playerCommandsEnabled() {
        return playerCommandsEnabled;
    }

    public void setPlayerCommandsEnabled(boolean enabled) {
        this.playerCommandsEnabled = enabled;
        safeLogInfo("[MytrixVoice] Player group commands {}", enabled ? "enabled" : "disabled");
    }

    public GroupRecord createGroup(McServerPlayer owner, CreateOptions options) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(options, "options");
        ensureSession();

        UUID groupId = UUID.randomUUID();
        String name = normalizeDisplayName(options.name().orElse(owner.getName() + "'s group"));
        if (groupsById.values().stream().anyMatch(group -> group.name().equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("Ya existe un grupo con ese nombre: " + name);
        }
        GroupRecord group = new GroupRecord(
                groupId,
                name,
                options.password().orElse(null),
                options.persistent(),
                owner.getUuid(),
                concurrentUuidSet(),
                concurrentUuidSet(),
                concurrentStringSet(options.permissions())
        );

        groupsById.put(group.id(), group);
        createOrUpdateChannel(group);
        joinGroup(owner, group, Optional.empty(), true);
        savePersistentGroups();
        safeLogInfo("[MytrixVoice] Created player group id={} owner={} persistent={}", group.id(), owner.getUuid(), group.persistent());
        return group;
    }

    public JoinResult joinGroup(McServerPlayer player, String groupToken, Optional<String> password, boolean bypass) {
        GroupRecord group = findGroup(groupToken)
                .orElseThrow(() -> new IllegalArgumentException("Grupo no encontrado: " + groupToken));
        return joinGroup(player, group, password, bypass);
    }

    public JoinResult joinGroup(McServerPlayer player, GroupRecord group, Optional<String> password, boolean bypass) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(group, "group");
        UUID playerId = player.getUuid();

        cleanupExpiredInvites();
        PendingInvite invite = invitesByPlayer.get(playerId);
        boolean invited = invite != null && invite.groupId().equals(group.id()) && !invite.expired();

        if (group.bannedPlayers().contains(playerId) && !bypass) {
            return JoinResult.rejected("Estas baneado de ese grupo");
        }
        if (!group.permissions().isEmpty() && !bypass && !hasAnyPermission(player, group.permissions())) {
            return JoinResult.rejected("No tienes permiso para entrar a ese grupo");
        }
        if (group.password() != null && !bypass && !invited && !group.password().equals(password.orElse(null))) {
            return JoinResult.rejected("Password incorrecta");
        }

        leaveGroup(playerId, false);
        group.members().add(playerId);
        groupIdByMember.put(playerId, group.id());
        invitesByPlayer.remove(playerId);
        createOrUpdateChannel(group);
        dynamicVoiceService().selectGroupChannel(playerId, channelId(group));
        savePersistentGroups();
        notifyGroup(group, player.getName() + " entro al grupo");
        return JoinResult.joined(group);
    }

    public boolean leaveGroup(UUID playerId, boolean notifyPlayer) {
        UUID groupId = groupIdByMember.remove(playerId);
        if (groupId == null) return false;

        GroupRecord group = groupsById.get(groupId);
        if (group == null) return false;

        group.members().remove(playerId);
        dynamicVoiceService().removeMember(channelId(group), playerId);
        dynamicVoiceService().clearGroupChannelSelection(playerId);

        if (notifyPlayer) {
            findOnlinePlayer(playerId).ifPresent(player -> send(player, "Saliste del grupo " + group.name()));
        }

        if (playerId.equals(group.owner())) {
            group.owner(firstMember(group).orElse(null));
            if (group.owner() != null) {
                findOnlinePlayer(group.owner()).ifPresent(owner -> send(owner, "Ahora eres el lider del grupo " + group.name()));
            }
        }

        createOrUpdateChannel(group);

        savePersistentGroups();
        return true;
    }

    public void deleteGroup(GroupRecord group, boolean announce) {
        GroupRecord removed = groupsById.remove(group.id());
        if (removed == null) return;

        for (UUID memberId : new ArrayList<>(removed.members())) {
            groupIdByMember.remove(memberId, removed.id());
            dynamicVoiceService().clearGroupChannelSelection(memberId);
        }
        removed.members().clear();
        dynamicVoiceService().deleteChannel(channelId(removed));
        invitesByPlayer.entrySet().removeIf(entry -> entry.getValue().groupId().equals(removed.id()));
        if (announce) safeLogInfo("[MytrixVoice] Deleted player group id={} name={}", removed.id(), removed.name());
        if (!shuttingDown) savePersistentGroups();
    }

    public void invite(McServerPlayer inviter, McServerPlayer target) {
        GroupRecord group = currentGroup(inviter.getUuid())
                .orElseThrow(() -> new IllegalArgumentException("No estas en ningun grupo"));
        if (!canManage(inviter, group) && !group.members().contains(inviter.getUuid())) {
            throw new IllegalArgumentException("No puedes invitar en este grupo");
        }
        if (group.bannedPlayers().contains(target.getUuid())) {
            throw new IllegalArgumentException("Ese jugador esta baneado del grupo");
        }
        invitesByPlayer.put(target.getUuid(), new PendingInvite(group.id(), target.getUuid(), Instant.now().plus(INVITE_TTL)));
        send(target, inviter.getName() + " te invito al grupo de voz " + group.name() + ". Usa /groups join " + group.id());
        send(inviter, "Invitacion enviada a " + target.getName());
    }

    public void kick(McServerPlayer actor, McServerPlayer target) {
        GroupRecord group = currentGroup(actor.getUuid())
                .orElseThrow(() -> new IllegalArgumentException("No estas en ningun grupo"));
        requireManage(actor, group);
        if (!group.members().contains(target.getUuid())) {
            throw new IllegalArgumentException("Ese jugador no esta en tu grupo");
        }
        leaveGroup(target.getUuid(), true);
        notifyGroup(group, target.getName() + " fue expulsado del grupo");
    }

    public void ban(McServerPlayer actor, McServerPlayer target) {
        GroupRecord group = currentGroup(actor.getUuid())
                .orElseThrow(() -> new IllegalArgumentException("No estas en ningun grupo"));
        requireManage(actor, group);
        group.bannedPlayers().add(target.getUuid());
        if (group.members().contains(target.getUuid())) leaveGroup(target.getUuid(), true);
        notifyGroup(group, target.getName() + " fue baneado del grupo");
        savePersistentGroups();
    }

    public void unban(McServerPlayer actor, UUID targetId) {
        GroupRecord group = currentGroup(actor.getUuid())
                .orElseThrow(() -> new IllegalArgumentException("No estas en ningun grupo"));
        requireManage(actor, group);
        group.bannedPlayers().remove(targetId);
        send(actor, "Jugador desbaneado del grupo");
        savePersistentGroups();
    }

    public void transfer(McServerPlayer actor, McServerPlayer target) {
        GroupRecord group = currentGroup(actor.getUuid())
                .orElseThrow(() -> new IllegalArgumentException("No estas en ningun grupo"));
        requireManage(actor, group);
        if (!group.members().contains(target.getUuid())) {
            throw new IllegalArgumentException("El nuevo lider debe estar en el grupo");
        }
        group.owner(target.getUuid());
        notifyGroup(group, target.getName() + " ahora es lider del grupo");
        savePersistentGroups();
    }

    public void updateName(McServerPlayer actor, String name) {
        GroupRecord group = currentGroup(actor.getUuid())
                .orElseThrow(() -> new IllegalArgumentException("No estas en ningun grupo"));
        requireManage(actor, group);
        String normalized = normalizeDisplayName(name);
        if (groupsById.values().stream().anyMatch(existing ->
                !existing.id().equals(group.id()) && existing.name().equalsIgnoreCase(normalized))) {
            throw new IllegalArgumentException("Ya existe un grupo con ese nombre: " + normalized);
        }
        group.name(normalized);
        notifyGroup(group, "Nombre del grupo actualizado a " + group.name());
        savePersistentGroups();
    }

    public void updatePassword(McServerPlayer actor, String password) {
        GroupRecord group = currentGroup(actor.getUuid())
                .orElseThrow(() -> new IllegalArgumentException("No estas en ningun grupo"));
        requireManage(actor, group);
        group.password(password == null || password.isBlank() ? null : password);
        send(actor, group.password() == null ? "Password eliminado" : "Password actualizado");
        savePersistentGroups();
    }

    public void updatePersistent(McServerPlayer actor, boolean persistent) {
        GroupRecord group = currentGroup(actor.getUuid())
                .orElseThrow(() -> new IllegalArgumentException("No estas en ningun grupo"));
        requireManage(actor, group);
        group.persistent(persistent);
        send(actor, "Persistente: " + yes(persistent));
        savePersistentGroups();
    }

    public void updatePermissions(McServerPlayer actor, Collection<String> permissions) {
        GroupRecord group = currentGroup(actor.getUuid())
                .orElseThrow(() -> new IllegalArgumentException("No estas en ningun grupo"));
        requireManage(actor, group);
        group.permissions().clear();
        permissions.stream()
                .map(String::trim)
                .filter(permission -> !permission.isBlank())
                .forEach(group.permissions()::add);
        send(actor, "Permisos actualizados: " + group.permissions());
        savePersistentGroups();
    }

    public boolean selectCurrentGroup(McServerPlayer player) {
        Optional<GroupRecord> group = currentGroup(player.getUuid());
        if (group.isEmpty()) return false;
        return dynamicVoiceService().selectGroupChannel(player.getUuid(), channelId(group.get()));
    }

    public Optional<GroupRecord> currentGroup(UUID playerId) {
        UUID groupId = groupIdByMember.get(playerId);
        return groupId == null ? Optional.empty() : Optional.ofNullable(groupsById.get(groupId));
    }

    public Optional<GroupRecord> findGroup(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String normalized = token.trim();
        try {
            UUID id = UUID.fromString(normalized);
            return Optional.ofNullable(groupsById.get(id));
        } catch (IllegalArgumentException ignored) {
        }

        String lower = normalized.toLowerCase(Locale.ROOT);
        return groupsById.values().stream()
                .filter(group -> group.name().equalsIgnoreCase(normalized) ||
                        group.id().toString().regionMatches(true, 0, lower, 0, lower.length()))
                .min(Comparator.comparing(GroupRecord::name, String.CASE_INSENSITIVE_ORDER));
    }

    public Collection<GroupRecord> visibleGroups(McServerPlayer viewer, boolean includeRestricted) {
        cleanupExpiredInvites();
        return groupsById.values().stream()
                .filter(group -> includeRestricted ||
                        group.permissions().isEmpty() ||
                        hasAnyPermission(viewer, group.permissions()) ||
                        group.members().contains(viewer.getUuid()))
                .sorted(Comparator.comparing(GroupRecord::name, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toUnmodifiableList());
    }

    public Collection<GroupRecord> allGroups() {
        return groupsById.values().stream()
                .sorted(Comparator.comparing(GroupRecord::name, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toUnmodifiableList());
    }

    public List<String> suggestGroups(String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return groupsById.values().stream()
                .flatMap(group -> List.of(group.id().toString(), group.name()).stream())
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    public Optional<McServerPlayer> findOnlinePlayer(String nameOrUuid) {
        try {
            UUID uuid = UUID.fromString(nameOrUuid);
            return findOnlinePlayer(uuid);
        } catch (IllegalArgumentException ignored) {
        }
        return voiceServer.getMinecraftServer().getPlayers().stream()
                .filter(player -> player.getName().equalsIgnoreCase(nameOrUuid))
                .findFirst();
    }

    public Optional<McServerPlayer> findOnlinePlayer(UUID playerId) {
        return voiceServer.getMinecraftServer().getPlayers().stream()
                .filter(player -> player.getUuid().equals(playerId))
                .findFirst();
    }

    private void createOrUpdateChannel(GroupRecord group) {
        DynamicVoiceService api = dynamicVoiceService();
        ensureSession();
        VoiceChannelId channelId = channelId(group);
        VoiceChannelOptions options = VoiceChannelOptions.builder()
                .policy(new VoiceChannelPolicy(
                        RoutingMode.PRIVATE_CHANNEL,
                        true,
                        false,
                        false,
                        GROUP_PRIORITY,
                        0D,
                        false
                ))
                .active(true)
                .metadata(Map.of(
                        "type", "player_group",
                        "name", group.name(),
                        "owner", group.owner() == null ? "" : group.owner().toString()
                ))
                .build();

        if (api.findChannel(channelId).isEmpty()) {
            api.createChannel(GROUP_SESSION, channelId, options);
        }
        api.syncMembers(channelId, group.members().stream()
                .map(playerId -> VoiceMemberDefinition.builder(playerId)
                        .role(playerId.equals(group.owner()) ? "owner" : "member")
                        .capabilities(new VoiceCapabilities(true, true, false, false, false))
                        .build())
                .collect(Collectors.toUnmodifiableList()));
    }

    private VoiceChannelId channelId(GroupRecord group) {
        return VoiceChannelId.of(GROUP_SESSION, group.id().toString());
    }

    private void ensureSession() {
        DynamicVoiceService api = dynamicVoiceService();
        if (api.findSession(GROUP_SESSION).isEmpty()) {
            api.createSession(
                    GROUP_SESSION,
                    VoiceSessionOptions.builder()
                            .fallbackToProximityWhenPaused(false)
                            .metadata(Map.of("type", "player_groups"))
                            .build()
            );
        }
    }

    private DynamicVoiceService dynamicVoiceService() {
        DynamicVoiceService service = voiceServer.getDynamicVoiceService();
        if (service == null) throw new IllegalStateException("Dynamic voice service is not initialized");
        return service;
    }

    @EventSubscribe
    public void onUdpClientConnected(UdpClientConnectedEvent event) {
        UUID playerId = event.getConnection().getPlayer().getInstance().getUuid();
        currentGroup(playerId).ifPresent(group -> dynamicVoiceService().selectGroupChannel(playerId, channelId(group)));
    }

    private void loadPersistentGroups() {
        if (storageFile == null || !storageFile.isFile()) return;
        try (FileReader reader = new FileReader(storageFile)) {
            StoredState state = GSON.fromJson(reader, StoredState.class);
            if (state == null || state.groups == null) return;
            for (StoredGroup stored : state.groups) {
                if (stored == null || stored.id == null || stored.name == null) continue;
                GroupRecord group = new GroupRecord(
                        stored.id,
                        normalizeDisplayName(stored.name),
                        stored.password,
                        true,
                        stored.owner,
                        concurrentUuidSet(stored.members),
                        concurrentUuidSet(stored.bannedPlayers),
                        concurrentStringSet(stored.permissions)
                );
                groupsById.put(group.id(), group);
                for (UUID member : group.members()) {
                    groupIdByMember.put(member, group.id());
                }
                createOrUpdateChannel(group);
            }
        } catch (Exception exception) {
            safeLogWarn("[MytrixVoice] Failed to load player groups: {}", exception.toString());
        }
    }

    private void savePersistentGroups() {
        if (storageFile == null) return;
        StoredState state = new StoredState();
        groupsById.values().stream()
                .filter(GroupRecord::persistent)
                .sorted(Comparator.comparing(GroupRecord::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(group -> state.groups.add(StoredGroup.from(group)));

        try (FileWriter writer = new FileWriter(storageFile)) {
            GSON.toJson(state, writer);
        } catch (IOException exception) {
            safeLogWarn("[MytrixVoice] Failed to save player groups: {}", exception.toString());
        }
    }

    private void cleanupExpiredInvites() {
        invitesByPlayer.entrySet().removeIf(entry -> entry.getValue().expired());
    }

    private Optional<UUID> firstMember(GroupRecord group) {
        return group.members().stream().findFirst();
    }

    private boolean hasAnyPermission(McServerPlayer player, Collection<String> permissions) {
        return permissions.stream().anyMatch(player::hasPermission);
    }

    private boolean canManage(McServerPlayer actor, GroupRecord group) {
        return actor.hasPermission("mytrixvoice.groups.manage") ||
                actor.getUuid().equals(group.owner());
    }

    private void requireManage(McServerPlayer actor, GroupRecord group) {
        if (!canManage(actor, group)) {
            throw new IllegalArgumentException("Solo el lider o un admin puede hacer eso");
        }
    }

    private void notifyGroup(GroupRecord group, String message) {
        for (UUID memberId : group.members()) {
            findOnlinePlayer(memberId).ifPresent(player -> send(player, "[" + group.name() + "] " + message));
        }
    }

    private void send(McServerPlayer player, String message) {
        player.sendMessage(McTextComponent.literal(message));
    }

    private String normalizeDisplayName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isBlank()) throw new IllegalArgumentException("Nombre de grupo invalido");
        if (name.length() > 32) name = name.substring(0, 32);
        return name;
    }

    private Set<UUID> concurrentUuidSet() {
        return ConcurrentHashMap.newKeySet();
    }

    private Set<UUID> concurrentUuidSet(Collection<UUID> values) {
        Set<UUID> set = ConcurrentHashMap.newKeySet();
        if (values != null) set.addAll(values);
        return set;
    }

    private Set<String> concurrentStringSet(Collection<String> values) {
        Set<String> set = ConcurrentHashMap.newKeySet();
        if (values != null) {
            values.stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .forEach(set::add);
        }
        return set;
    }

    private String yes(boolean value) {
        return value ? "si" : "no";
    }

    private void safeLogInfo(String message, Object... args) {
        try {
            BaseVoice.LOGGER.info(message, args);
        } catch (Throwable ignored) {
        }
    }

    private void safeLogWarn(String message, Object... args) {
        try {
            BaseVoice.LOGGER.warn(message, args);
        } catch (Throwable ignored) {
        }
    }

    public record CreateOptions(
            Optional<String> name,
            Optional<String> password,
            boolean persistent,
            Set<String> permissions
    ) {
        public CreateOptions {
            name = name == null ? Optional.empty() : name;
            password = password == null ? Optional.empty() : password;
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }
    }

    public record JoinResult(boolean joined, Optional<GroupRecord> group, String message) {
        public static JoinResult joined(GroupRecord group) {
            return new JoinResult(true, Optional.of(group), "joined");
        }

        public static JoinResult rejected(String message) {
            return new JoinResult(false, Optional.empty(), message);
        }
    }

    public static final class GroupRecord {
        private final UUID id;
        private volatile String name;
        private volatile String password;
        private volatile boolean persistent;
        private volatile UUID owner;
        private final Set<UUID> members;
        private final Set<UUID> bannedPlayers;
        private final Set<String> permissions;

        private GroupRecord(
                UUID id,
                String name,
                String password,
                boolean persistent,
                UUID owner,
                Set<UUID> members,
                Set<UUID> bannedPlayers,
                Set<String> permissions
        ) {
            this.id = id;
            this.name = name;
            this.password = password;
            this.persistent = persistent;
            this.owner = owner;
            this.members = members;
            this.bannedPlayers = bannedPlayers;
            this.permissions = permissions;
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }

        private void name(String name) {
            this.name = name;
        }

        public String password() {
            return password;
        }

        private void password(String password) {
            this.password = password;
        }

        public boolean persistent() {
            return persistent;
        }

        private void persistent(boolean persistent) {
            this.persistent = persistent;
        }

        public UUID owner() {
            return owner;
        }

        private void owner(UUID owner) {
            this.owner = owner;
        }

        public Set<UUID> members() {
            return members;
        }

        public Set<UUID> bannedPlayers() {
            return bannedPlayers;
        }

        public Set<String> permissions() {
            return permissions;
        }

        public String summary() {
            return name + " id=" + id + " miembros=" + members.size() +
                    " password=" + (password == null ? "no" : "si") +
                    " persistente=" + (persistent ? "si" : "no");
        }
    }

    private record PendingInvite(UUID groupId, UUID playerId, Instant expiresAt) {
        private boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private static final class StoredState {
        private final List<StoredGroup> groups = new ArrayList<>();
    }

    private static final class StoredGroup {
        private UUID id;
        private String name;
        private String password;
        private boolean persistent;
        private UUID owner;
        private List<UUID> members = new ArrayList<>();
        private List<UUID> bannedPlayers = new ArrayList<>();
        private List<String> permissions = new ArrayList<>();

        private static StoredGroup from(GroupRecord group) {
            StoredGroup stored = new StoredGroup();
            stored.id = group.id();
            stored.name = group.name();
            stored.password = group.password();
            stored.persistent = group.persistent();
            stored.owner = group.owner();
            stored.members = new ArrayList<>(new LinkedHashSet<>(group.members()));
            stored.bannedPlayers = new ArrayList<>(new LinkedHashSet<>(group.bannedPlayers()));
            stored.permissions = new ArrayList<>(new LinkedHashSet<>(group.permissions()));
            return stored;
        }
    }
}
