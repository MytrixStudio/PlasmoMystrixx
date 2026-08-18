package su.plo.voice.server.command;

import net.mytrix.voice.api.RestrictionTarget;
import net.mytrix.voice.api.RoutingMode;
import net.mytrix.voice.api.VoiceApiException;
import net.mytrix.voice.api.VoiceCapabilities;
import net.mytrix.voice.api.VoiceChannelId;
import net.mytrix.voice.api.VoiceChannelOptions;
import net.mytrix.voice.api.VoiceChannelPolicy;
import net.mytrix.voice.api.VoiceChannelSnapshot;
import net.mytrix.voice.api.VoiceChannelUpdate;
import net.mytrix.voice.api.VoiceMemberDefinition;
import net.mytrix.voice.api.VoicePlayerSnapshot;
import net.mytrix.voice.api.VoiceRestrictionHandle;
import net.mytrix.voice.api.VoiceRestrictionRequest;
import net.mytrix.voice.api.VoiceRestrictionSnapshot;
import net.mytrix.voice.api.VoiceRestrictionType;
import net.mytrix.voice.api.VoiceSessionId;
import net.mytrix.voice.api.VoiceSessionOptions;
import net.mytrix.voice.api.VoiceSessionSnapshot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.plo.slib.api.chat.component.McTextComponent;
import su.plo.slib.api.command.McCommand;
import su.plo.slib.api.command.McCommandSource;
import su.plo.slib.api.server.entity.player.McServerPlayer;
import su.plo.slib.api.server.position.ServerPos3d;
import su.plo.voice.server.BaseVoiceServer;
import su.plo.voice.server.dynamic.DynamicVoiceService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class MytrixVoiceCommand implements McCommand {

    private static final VoiceSessionId ADMIN_GROUP_SESSION = VoiceSessionId.of("mytrixvoice", "admin_groups");
    private static final String ADMIN_GROUP_OWNER = "mytrixvoice";
    private static final String ADMIN_GROUP_REASON = "admin_group_mute";
    private static final int ADMIN_GROUP_PRIORITY = 700;
    private static final double DEFAULT_GROUP_DISTANCE = 0D;
    private static final double DEFAULT_NEARBY_RANGE = 128D;
    private static final String[] GROUP_ROOT_SUGGESTIONS = {
            "list", "replace", "manual", "create", "setup", "create-add",
            "distance", "delete", "add", "forcejoin", "nearby", "select",
            "unselect", "remove", "forceleave", "leave-all", "clear-members",
            "activate", "deactivate", "mute", "unmute", "inspect", "help"
    };
    private static final Set<String> PUBLIC_GROUP_ACTIONS = Set.of("list", "help");
    private static final Set<String> GROUP_ACTIONS_WITH_CHANNEL = Set.of(
            "delete", "add", "remove", "forcejoin", "forceleave", "nearby",
            "distance", "clear-members", "activate", "deactivate", "mute",
            "unmute", "inspect", "select", "use", "setup", "create-add"
    );
    private static final Set<String> GROUP_ACTIONS_WITH_TARGET_AFTER_CHANNEL = Set.of(
            "add", "remove", "forcejoin", "forceleave", "select", "use", "setup", "create-add"
    );
    private static final Set<String> GROUP_ACTIONS_WITH_TARGET_ONLY = Set.of("leave-all", "unselect");

    private final BaseVoiceServer voiceServer;
    private final boolean groupsOnly;

    public MytrixVoiceCommand(BaseVoiceServer voiceServer) {
        this(voiceServer, false);
    }

    public MytrixVoiceCommand(BaseVoiceServer voiceServer, boolean groupsOnly) {
        this.voiceServer = voiceServer;
        this.groupsOnly = groupsOnly;
    }

    @Override
    public void execute(@NotNull McCommandSource source, @NotNull String[] arguments) {
        try {
            if (groupsOnly) {
                groups(source, arguments);
                return;
            }

            if (arguments.length == 0) {
                usage(source);
                return;
            }

            switch (arguments[0].toLowerCase(Locale.ROOT)) {
                case "sessions" -> sessions(source, tail(arguments));
                case "channels" -> channels(source, tail(arguments));
                case "groups" -> groups(source, tail(arguments));
                case "players" -> players(source, tail(arguments));
                case "restrictions" -> restrictions(source, tail(arguments));
                case "debug" -> debug(source, tail(arguments));
                default -> usage(source);
            }
        } catch (VoiceApiException | IllegalArgumentException e) {
            send(source, "MytrixVoice: " + e.getMessage());
        } catch (Exception e) {
            send(source, "MytrixVoice: error interno: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public boolean hasPermission(@NotNull McCommandSource source, @Nullable String[] arguments) {
        if (!groupsOnly) {
            return hasAdminPermission(source);
        }
        if (arguments != null && arguments.length > 0) {
            return canUseGroupSubcommand(source, arguments[0]);
        }
        return hasAdminPermission(source) ||
                source.hasPermission("mytrixvoice.group") ||
                source.hasPermission("mytrixvoice.groups");
    }

    @Override
    public @NotNull List<String> suggest(@NotNull McCommandSource source, @NotNull String[] arguments) {
        if (groupsOnly) return suggestGroups(source, arguments);
        if (arguments.length == 0) return List.of();
        if (arguments.length == 1) {
            return startsWith(arguments[0], "sessions", "channels", "groups", "players", "restrictions", "debug");
        }
        String root = arguments[0].toLowerCase(Locale.ROOT);
        if (root.equals("groups")) {
            return suggestGroups(source, tail(arguments));
        }
        if (arguments.length == 2) {
            return switch (root) {
                case "sessions" -> startsWith(arguments[1], "list", "inspect");
                case "channels" -> startsWith(arguments[1], "list", "inspect");
                case "players" -> startsWith(arguments[1], "inspect");
                case "restrictions" -> startsWith(arguments[1], "list", "remove");
                case "debug" -> startsWith(arguments[1], "create-session", "create-channel", "add-player", "activate", "deactivate", "restrict", "clear", "routing");
                default -> List.of();
            };
        }
        if (root.equals("players") && arguments.length == 3) {
            return Suggestions.players(voiceServer.getMinecraftServer(), source, arguments[2]);
        }
        if (root.equals("debug") && arguments[1].equalsIgnoreCase("add-player") && arguments.length == 4) {
            return Suggestions.players(voiceServer.getMinecraftServer(), source, arguments[3]);
        }
        if (root.equals("debug") && arguments[1].equalsIgnoreCase("restrict") && arguments.length == 4) {
            return startsWith(arguments[3], "block_transmit", "block_receive", "block_both", "force_channel_only", "disable_proximity");
        }
        return McCommand.super.suggest(source, arguments);
    }

    private void sessions(McCommandSource source, String[] arguments) {
        if (arguments.length == 0 || arguments[0].equalsIgnoreCase("list")) {
            Collection<VoiceSessionSnapshot> sessions = api().inspectSessions();
            send(source, "Sesiones: " + sessions.size());
            sessions.forEach(session -> send(source, "- " + session.id() + " estado=" + session.state() + " canales=" + session.channels().size()));
            return;
        }
        if (arguments[0].equalsIgnoreCase("inspect") && arguments.length >= 2) {
            VoiceSessionId sessionId = VoiceSessionId.parse(arguments[1]);
            VoiceSessionSnapshot session = api().inspectSessions().stream()
                    .filter(snapshot -> snapshot.id().equals(sessionId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
            send(source, "Sesion: " + session.id());
            send(source, "Estado: " + session.state());
            send(source, "Persistencia: " + session.options().persistenceMode());
            send(source, "Canales: " + session.channels().size());
            session.channels().stream().sorted().forEach(channel -> send(source, "- " + channel));
            return;
        }
        usage(source);
    }

    private void channels(McCommandSource source, String[] arguments) {
        if (arguments.length >= 2 && arguments[0].equalsIgnoreCase("list")) {
            VoiceSessionId sessionId = VoiceSessionId.parse(arguments[1]);
            Collection<VoiceChannelSnapshot> channels = api().inspectChannels(sessionId);
            send(source, "Canales en " + sessionId + ": " + channels.size());
            channels.forEach(channel -> send(source, "- " + summarizeChannel(channel)));
            return;
        }
        if (arguments.length >= 2 && arguments[0].equalsIgnoreCase("inspect")) {
            VoiceChannelSnapshot channel = api().inspectChannel(VoiceChannelId.parse(arguments[1]));
            send(source, "Canal: " + channel.id());
            send(source, "Activo: " + channel.active());
            send(source, "Routing: " + channel.policy().routingMode());
            send(source, "Exclusivo: " + channel.policy().exclusive());
            send(source, "Prioridad: " + channel.policy().priority());
            send(source, "Miembros: " + channel.members().size());
            channel.members().stream().sorted().forEach(playerId -> {
                VoiceCapabilities capabilities = channel.capabilities().get(playerId);
                send(source, "- " + playerId + " rol=" + channel.roles().get(playerId) + " transmitir=" + capabilities.transmit() + " recibir=" + capabilities.receive());
            });
            return;
        }
        usage(source);
    }

    private void players(McCommandSource source, String[] arguments) {
        if (arguments.length >= 2 && arguments[0].equalsIgnoreCase("inspect")) {
            UUID playerId = resolvePlayer(arguments[1]);
            VoicePlayerSnapshot snapshot = api().inspectPlayer(playerId);
            send(source, "Jugador: " + arguments[1]);
            send(source, "UUID: " + snapshot.playerId());
            send(source, "Conectado: " + yes(snapshot.connected()));
            send(source, "Puede transmitir: " + yes(snapshot.canTransmit()));
            send(source, "Puede recibir: " + yes(snapshot.canReceive()));
            send(source, "Proximidad bloqueada: " + yes(snapshot.proximityDisabled()));
            send(source, "Canales: " + snapshot.channels().size());
            snapshot.channels().stream().sorted().forEach(channel -> {
                VoiceChannelSnapshot channelSnapshot = api().inspectChannel(channel);
                VoiceCapabilities capabilities = channelSnapshot.capabilities().get(playerId);
                send(source, "- " + channel + " activo=" + yes(channelSnapshot.active()) + " exclusivo=" + yes(channelSnapshot.policy().exclusive()) + " prioridad=" + channelSnapshot.policy().priority() + " transmitir=" + yes(capabilities.transmit()) + " recibir=" + yes(capabilities.receive()));
            });
            send(source, "Restricciones: " + snapshot.restrictions().size());
            snapshot.restrictions().forEach(restriction -> send(source, "- " + summarizeRestriction(restriction)));
            return;
        }
        usage(source);
    }

    private void restrictions(McCommandSource source, String[] arguments) {
        if (arguments.length == 0 || arguments[0].equalsIgnoreCase("list")) {
            if (arguments.length >= 3 && arguments[1].equalsIgnoreCase("channel")) {
                VoiceChannelId channelId = VoiceChannelId.parse(arguments[2]);
                api().inspectChannelRestrictions(channelId).forEach(restriction -> send(source, "- " + summarizeRestriction(restriction)));
                return;
            }
            Collection<VoiceRestrictionSnapshot> restrictions = api().inspectRestrictions();
            send(source, "Restricciones: " + restrictions.size());
            restrictions.forEach(restriction -> send(source, "- " + summarizeRestriction(restriction)));
            return;
        }
        if (arguments.length >= 3 && arguments[0].equalsIgnoreCase("remove")) {
            api().removeRestriction(new VoiceRestrictionHandle(UUID.fromString(arguments[2]), arguments[1]));
            send(source, "Restriccion eliminada: " + arguments[1] + "/" + arguments[2]);
            return;
        }
        usage(source);
    }

    private void groups(McCommandSource source, String[] arguments) {
        if (arguments.length == 0 || arguments[0].equalsIgnoreCase("help")) {
            groupUsage(source);
            return;
        }

        String action = arguments[0].toLowerCase(Locale.ROOT);
        if (!canUseGroupSubcommand(source, action)) {
            throw new IllegalArgumentException("No tienes permisos para usar /vcgroup " + arguments[0] + ". Se requiere mytrixvoice.admin o mytrixvoice.groups.manage.");
        }

        switch (action) {
            case "list" -> listGroups(source);
            case "replace", "reemplazar" -> {
                require(arguments, 2, "Uso: /vcgroup replace <on|off|status>");
                switch (arguments[1].toLowerCase(Locale.ROOT)) {
                    case "on", "true", "enable", "enabled" -> {
                        api().setProximityGroupReplacementEnabled(true);
                        setManualGroupsEnabled(false);
                        send(source, "Enrutamiento nativo de la tecla normal al grupo: " + yes(true));
                        send(source, "Grupos manuales /groups: " + yes(false));
                    }
                    case "off", "false", "disable", "disabled" -> {
                        api().setProximityGroupReplacementEnabled(false);
                        setManualGroupsEnabled(true);
                        send(source, "Enrutamiento nativo de la tecla normal al grupo: " + yes(false));
                        send(source, "Grupos manuales /groups: " + yes(true));
                    }
                    case "status", "estado" ->
                            send(source, "Enrutamiento nativo de la tecla normal al grupo: " + yes(api().isProximityGroupReplacementEnabled()));
                    default -> throw new IllegalArgumentException("Uso: /vcgroup replace <on|off|status>");
                }
            }
            case "manual", "manual-groups" -> {
                require(arguments, 2, "Uso: /vcgroup manual <on|off|status>");
                switch (arguments[1].toLowerCase(Locale.ROOT)) {
                    case "on", "true", "enable", "enabled" -> {
                        setManualGroupsEnabled(true);
                        send(source, "Grupos manuales /groups: " + yes(true));
                    }
                    case "off", "false", "disable", "disabled" -> {
                        setManualGroupsEnabled(false);
                        send(source, "Grupos manuales /groups: " + yes(false));
                    }
                    case "status", "estado" ->
                            send(source, "Grupos manuales /groups: " + yes(manualGroupsEnabled()));
                    default -> throw new IllegalArgumentException("Uso: /vcgroup manual <on|off|status>");
                }
            }
            case "create" -> {
                require(arguments, 2, "Uso: /vcgroup create <grupo> [distancia|global]");
                double distance = arguments.length >= 3 ? parseDistance(arguments[2]) : DEFAULT_GROUP_DISTANCE;
                VoiceChannelId channelId = adminGroupChannel(arguments[1]);
                createOrUpdateAdminGroup(channelId, distance);
                send(source, "Grupo de voz creado: " + channelId.value() + " distancia=" + formatDistance(distance));
            }
            case "setup", "create-add", "createandadd" -> {
                require(arguments, 3, "Uso: /vcgroup setup <grupo> <jugador|@a|@a[team=<equipo>]> [distancia|global]");
                VoiceChannelId channelId = adminGroupChannel(arguments[1]);
                ResolvedPlayers players = resolvePlayers(arguments[2]);
                double distance = arguments.length >= 4 ? parseDistance(arguments[3]) : DEFAULT_GROUP_DISTANCE;
                createOrUpdateAdminGroup(channelId, distance);
                MembershipChangeResult result = addMembersAndSelect(channelId, players.ids());
                send(source, "Grupo listo: " + channelId.value() +
                        " encontrados=" + players.size() +
                        " agregados=" + result.changed() +
                        " omitidos=" + result.skipped() +
                        " fallos=" + result.failed() +
                        " seleccionados=" + result.selected() +
                        " seleccion_fallida=" + result.selectionFailed() +
                        " distancia=" + formatDistance(distance));
            }
            case "distance" -> {
                require(arguments, 3, "Uso: /vcgroup distance <grupo> <distancia|global>");
                VoiceChannelId channelId = adminGroupChannel(arguments[1]);
                double distance = parseDistance(arguments[2]);
                requireExistingAdminGroupChannel(channelId);
                createOrUpdateAdminGroup(channelId, distance);
                send(source, "Distancia actualizada: " + channelId.value() + " distancia=" + formatDistance(distance));
            }
            case "delete" -> {
                require(arguments, 2, "Uso: /vcgroup delete <grupo>");
                VoiceChannelId channelId = adminGroupChannel(arguments[1]);
                requireExistingAdminGroupChannel(channelId);
                api().deleteChannel(channelId);
                send(source, "Grupo de voz eliminado: " + channelId.value());
            }
            case "add", "forcejoin" -> {
                require(arguments, 3, "Uso: /vcgroup add <grupo> <jugador|@a|@a[team=<equipo>]>");
                VoiceChannelId channelId = adminGroupChannel(arguments[1]);
                requireExistingAdminGroupChannel(channelId);
                ResolvedPlayers players = resolvePlayers(arguments[2]);
                MembershipChangeResult result = addMembersAndSelect(channelId, players.ids());
                send(source, "Grupo " + channelId.value() +
                        ": encontrados=" + players.size() +
                        " agregados=" + result.changed() +
                        " ya_estaban=" + result.skipped() +
                        " fallos=" + result.failed() +
                        " seleccionados=" + result.selected() +
                        " seleccion_fallida=" + result.selectionFailed());
            }
            case "nearby" -> {
                require(arguments, 2, "Uso: /vcgroup nearby <grupo> [rango]");
                if (!(source instanceof McServerPlayer executor)) {
                    throw new IllegalArgumentException("Este comando necesita un jugador como ejecutor");
                }
                double range = arguments.length >= 3 ? parseRange(arguments[2]) : DEFAULT_NEARBY_RANGE;
                VoiceChannelId channelId = adminGroupChannel(arguments[1]);
                requireExistingAdminGroupChannel(channelId);
                Collection<UUID> players = resolveNearbyPlayers(executor, range);
                MembershipChangeResult result = addMembersAndSelect(channelId, players);
                send(source, "Cercanos agregados a " + channelId.value() +
                        ": encontrados=" + players.size() +
                        " agregados=" + result.changed() +
                        " ya_estaban=" + result.skipped() +
                        " fallos=" + result.failed() +
                        " seleccionados=" + result.selected() +
                        " seleccion_fallida=" + result.selectionFailed() +
                        " rango=" + formatDistance(range));
            }
            case "select", "use" -> {
                require(arguments, 3, "Uso: /vcgroup select <grupo> <jugador|@a|@a[team=<equipo>]>");
                VoiceChannelId channelId = adminGroupChannel(arguments[1]);
                requireExistingAdminGroupChannel(channelId);
                ResolvedPlayers players = resolvePlayers(arguments[2]);
                MembershipChangeResult selected = selectGroupForPlayers(channelId, players.ids());
                send(source, "Canal de grupo seleccionado: " + channelId.value() +
                        " seleccionados=" + selected.selected() +
                        " fallos=" + selected.selectionFailed() +
                        " encontrados=" + players.size());
            }
            case "unselect" -> {
                require(arguments, 2, "Uso: /vcgroup unselect <jugador|@a|@a[team=<equipo>]>");
                ResolvedPlayers players = resolvePlayers(arguments[1]);
                int cleared = 0;
                int failed = 0;
                for (UUID playerId : players.ids()) {
                    try {
                        api().clearGroupChannelSelection(playerId);
                        cleared++;
                    } catch (RuntimeException exception) {
                        failed++;
                    }
                }
                send(source, "Seleccion de voz grupal limpiada: encontrados=" + players.size() +
                        " limpiados=" + cleared +
                        " fallos=" + failed);
            }
            case "remove" -> {
                require(arguments, 3, "Uso: /vcgroup remove <grupo> <jugador|@a|@a[team=<equipo>]>");
                VoiceChannelId channelId = adminGroupChannel(arguments[1]);
                requireExistingAdminGroupChannel(channelId);
                ResolvedPlayers players = resolvePlayers(arguments[2]);
                MembershipChangeResult result = removeMembers(channelId, players.ids());
                send(source, "Retirados de " + channelId.value() +
                        ": encontrados=" + players.size() +
                        " retirados=" + result.changed() +
                        " no_estaban=" + result.skipped() +
                        " fallos=" + result.failed());
            }
            case "forceleave" -> {
                require(arguments, 3, "Uso: /vcgroup forceleave <grupo> <jugador|@a|@a[team=<equipo>]>");
                VoiceChannelId channelId = adminGroupChannel(arguments[1]);
                requireExistingAdminGroupChannel(channelId);
                ResolvedPlayers players = resolvePlayers(arguments[2]);
                MembershipChangeResult result = removeMembers(channelId, players.ids());
                send(source, "Retirados de " + channelId.value() +
                        ": encontrados=" + players.size() +
                        " retirados=" + result.changed() +
                        " no_estaban=" + result.skipped() +
                        " fallos=" + result.failed());
            }
            case "leave-all" -> {
                require(arguments, 2, "Uso: /vcgroup leave-all <jugador|@a|@a[team=<equipo>]>");
                ResolvedPlayers players = resolvePlayers(arguments[1]);
                int removed = removeFromAllAdminGroups(players.ids());
                send(source, "Membresias retiradas: encontrados=" + players.size() + " retiradas=" + removed);
            }
            case "clear-members" -> {
                require(arguments, 2, "Uso: /vcgroup clear-members <grupo>");
                VoiceChannelId channelId = adminGroupChannel(arguments[1]);
                requireExistingAdminGroupChannel(channelId);
                VoiceChannelSnapshot channel = api().inspectChannel(channelId);
                channel.members().forEach(playerId -> api().removeMember(channelId, playerId));
                send(source, "Miembros limpiados de " + channelId.value() + ": " + channel.members().size());
            }
            case "activate" -> {
                require(arguments, 2, "Uso: /vcgroup activate <grupo>");
                VoiceChannelId channelId = adminGroupChannel(arguments[1]);
                requireExistingAdminGroupChannel(channelId);
                api().activateChannel(channelId);
                send(source, "Grupo activado: " + channelId.value());
            }
            case "deactivate" -> {
                require(arguments, 2, "Uso: /vcgroup deactivate <grupo>");
                VoiceChannelId channelId = adminGroupChannel(arguments[1]);
                requireExistingAdminGroupChannel(channelId);
                api().deactivateChannel(channelId);
                send(source, "Grupo desactivado: " + channelId.value());
            }
            case "mute" -> {
                require(arguments, 2, "Uso: /vcgroup mute <grupo> [segundos]");
                VoiceChannelId channelId = adminGroupChannel(arguments[1]);
                requireExistingAdminGroupChannel(channelId);
                VoiceRestrictionRequest.Builder builder = VoiceRestrictionRequest.builder()
                        .ownerNamespace(ADMIN_GROUP_OWNER)
                        .reason(ADMIN_GROUP_REASON)
                        .target(RestrictionTarget.channel(channelId))
                        .type(VoiceRestrictionType.BLOCK_BOTH)
                        .priority(1000);
                if (arguments.length >= 3) {
                    builder.duration(Duration.ofSeconds(Long.parseLong(arguments[2])));
                }
                VoiceRestrictionHandle handle = api().applyRestriction(builder.build());
                send(source, "Grupo muteado: " + channelId.value() + " handle=" + handle.id());
            }
            case "unmute" -> {
                require(arguments, 2, "Uso: /vcgroup unmute <grupo>");
                VoiceChannelId channelId = adminGroupChannel(arguments[1]);
                requireExistingAdminGroupChannel(channelId);
                int removed = 0;
                for (VoiceRestrictionSnapshot restriction : api().inspectChannelRestrictions(channelId)) {
                    if (ADMIN_GROUP_OWNER.equals(restriction.ownerNamespace()) &&
                            ADMIN_GROUP_REASON.equals(restriction.reason())) {
                        api().removeRestriction(restriction.handle());
                        removed++;
                    }
                }
                send(source, "Restricciones de mute retiradas de " + channelId.value() + ": " + removed);
            }
            case "inspect" -> {
                require(arguments, 2, "Uso: /vcgroup inspect <grupo>");
                VoiceChannelId channelId = adminGroupChannel(arguments[1]);
                requireExistingAdminGroupChannel(channelId);
                VoiceChannelSnapshot channel = api().inspectChannel(channelId);
                send(source, "Grupo: " + channel.id().value());
                send(source, "Activo: " + yes(channel.active()));
                send(source, "Distancia: " + formatDistance(channel.policy().maximumDistance()));
                send(source, "Exclusivo: " + yes(channel.policy().exclusive()));
                send(source, "Prioridad: " + channel.policy().priority());
                send(source, "Miembros: " + channel.members().size());
                channel.members().stream().sorted().forEach(playerId -> send(source, "- " + playerId));
                Collection<VoiceRestrictionSnapshot> restrictions = api().inspectChannelRestrictions(channel.id());
                send(source, "Restricciones: " + restrictions.size());
                restrictions.forEach(restriction -> send(source, "- " + summarizeRestriction(restriction)));
            }
            default -> groupUsage(source);
        }
    }

    private void debug(McCommandSource source, String[] arguments) {
        if (arguments.length == 0) {
            usage(source);
            return;
        }
        switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "create-session" -> {
                require(arguments, 2, "Uso: /mytrixvoice debug create-session <namespace:session>");
                VoiceSessionId sessionId = VoiceSessionId.parse(arguments[1]);
                api().createSession(sessionId, VoiceSessionOptions.memoryOnly());
                send(source, "Sesion creada: " + sessionId);
            }
            case "create-channel" -> {
                require(arguments, 2, "Uso: /mytrixvoice debug create-channel <namespace:session/channel>");
                VoiceChannelId channelId = VoiceChannelId.parse(arguments[1]);
                if (api().findSession(channelId.sessionId()).isEmpty()) {
                    api().createSession(channelId.sessionId(), VoiceSessionOptions.memoryOnly());
                }
                api().createChannel(
                        channelId.sessionId(),
                        channelId,
                        VoiceChannelOptions.builder()
                                .policy(new VoiceChannelPolicy(RoutingMode.PRIVATE_CHANNEL, true, false, false, 500, 0D, false))
                                .active(false)
                                .build()
                );
                send(source, "Canal creado: " + channelId);
            }
            case "add-player" -> {
                require(arguments, 3, "Uso: /mytrixvoice debug add-player <namespace:session/channel> <jugador>");
                VoiceChannelId channelId = VoiceChannelId.parse(arguments[1]);
                UUID playerId = resolvePlayer(arguments[2]);
                api().addMember(channelId, VoiceMemberDefinition.builder(playerId).role("participant").canTransmit(true).canReceive(true).build());
                send(source, "Jugador agregado: " + arguments[2] + " -> " + channelId);
            }
            case "activate" -> {
                require(arguments, 2, "Uso: /mytrixvoice debug activate <namespace:session/channel>");
                VoiceChannelId channelId = VoiceChannelId.parse(arguments[1]);
                api().activateChannel(channelId);
                send(source, "Canal activado: " + channelId);
            }
            case "deactivate" -> {
                require(arguments, 2, "Uso: /mytrixvoice debug deactivate <namespace:session/channel>");
                VoiceChannelId channelId = VoiceChannelId.parse(arguments[1]);
                api().deactivateChannel(channelId);
                send(source, "Canal desactivado: " + channelId);
            }
            case "restrict" -> {
                require(arguments, 3, "Uso: /mytrixvoice debug restrict <namespace:session/channel> <tipo> [segundos]");
                VoiceChannelId channelId = VoiceChannelId.parse(arguments[1]);
                VoiceRestrictionType type = parseRestrictionType(arguments[2]);
                VoiceRestrictionRequest.Builder builder = VoiceRestrictionRequest.builder()
                        .ownerNamespace(channelId.sessionId().namespace())
                        .reason("debug")
                        .target(RestrictionTarget.channel(channelId))
                        .type(type)
                        .priority(1000);
                if (arguments.length >= 4) {
                    builder.duration(Duration.ofSeconds(Long.parseLong(arguments[3])));
                }
                VoiceRestrictionHandle handle = api().applyRestriction(builder.build());
                send(source, "Restriccion aplicada: " + handle.ownerNamespace() + "/" + handle.id());
            }
            case "clear" -> {
                require(arguments, 2, "Uso: /mytrixvoice debug clear <namespace:session>");
                VoiceSessionId sessionId = VoiceSessionId.parse(arguments[1]);
                api().closeSession(sessionId);
                send(source, "Sesion cerrada: " + sessionId);
            }
            case "routing" -> {
                require(arguments, 2, "Uso: /mytrixvoice debug routing <on|off>");
                boolean enabled = arguments[1].equalsIgnoreCase("on") || arguments[1].equalsIgnoreCase("true");
                api().setDebugRouting(enabled);
                send(source, "Debug routing: " + yes(enabled));
            }
            default -> usage(source);
        }
    }

    private DynamicVoiceService api() {
        DynamicVoiceService api = voiceServer.getDynamicVoiceService();
        if (api == null) throw new IllegalStateException("Dynamic voice service is not initialized");
        return api;
    }

    private void setManualGroupsEnabled(boolean enabled) {
        if (voiceServer.getGroupService() != null) {
            voiceServer.getGroupService().setPlayerCommandsEnabled(enabled);
        }
    }

    private boolean manualGroupsEnabled() {
        return voiceServer.getGroupService() == null || voiceServer.getGroupService().playerCommandsEnabled();
    }

    private void ensureAdminGroupSession() {
        if (api().findSession(ADMIN_GROUP_SESSION).isEmpty()) {
            api().createSession(
                    ADMIN_GROUP_SESSION,
                    VoiceSessionOptions.builder()
                            .fallbackToProximityWhenPaused(false)
                            .metadata(Map.of("type", "admin_groups"))
                            .build()
            );
        }
    }

    private void ensureAdminGroupChannel(VoiceChannelId channelId) {
        ensureAdminGroupSession();
        if (api().findChannel(channelId).isPresent()) return;
        createOrUpdateAdminGroup(channelId, DEFAULT_GROUP_DISTANCE);
    }

    private void requireExistingAdminGroupChannel(VoiceChannelId channelId) {
        ensureAdminGroupSession();
        if (api().findChannel(channelId).isEmpty()) {
            throw new IllegalArgumentException("Grupo no encontrado: " + channelId.value() + ".");
        }
    }

    private void createOrUpdateAdminGroup(VoiceChannelId channelId, double distance) {
        ensureAdminGroupSession();
        VoiceChannelPolicy policy = new VoiceChannelPolicy(
                RoutingMode.PRIVATE_CHANNEL,
                true,
                false,
                false,
                ADMIN_GROUP_PRIORITY,
                distance,
                true
        );
        Map<String, String> metadata = Map.of(
                "type", "admin_group",
                "distance", Double.toString(distance)
        );
        if (api().findChannel(channelId).isPresent()) {
            api().updateChannel(
                    channelId,
                    VoiceChannelUpdate.builder()
                            .policy(policy)
                            .active(true)
                            .putMetadata(metadata)
                            .build()
            );
            return;
        }
        api().createChannel(
                ADMIN_GROUP_SESSION,
                channelId,
                VoiceChannelOptions.builder()
                        .policy(policy)
                        .active(true)
                        .metadata(metadata)
                        .build()
        );
    }

    private void listGroups(McCommandSource source) {
        if (api().findSession(ADMIN_GROUP_SESSION).isEmpty()) {
            send(source, "Grupos de voz: 0");
            return;
        }
        Collection<VoiceChannelSnapshot> groups = api().inspectChannels(ADMIN_GROUP_SESSION);
        send(source, "Grupos de voz: " + groups.size());
        groups.forEach(group -> send(source, "- " + group.id().value() +
                " activo=" + yes(group.active()) +
                " miembros=" + group.members().size() +
                " distancia=" + formatDistance(group.policy().maximumDistance())));
    }

    private VoiceChannelId adminGroupChannel(String rawGroup) {
        return VoiceChannelId.of(ADMIN_GROUP_SESSION, normalizeGroupName(rawGroup));
    }

    private String normalizeGroupName(String rawGroup) {
        String normalized = rawGroup == null
                ? ""
                : rawGroup.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        if (normalized.length() > 128) normalized = normalized.substring(0, 128);
        if (normalized.isBlank()) throw new IllegalArgumentException("Nombre de grupo invalido: " + rawGroup);
        return normalized;
    }

    private double parseDistance(String raw) {
        if ("global".equalsIgnoreCase(raw) || "all".equalsIgnoreCase(raw) || "unlimited".equalsIgnoreCase(raw)) {
            return 0D;
        }
        double distance = Double.parseDouble(raw);
        if (!Double.isFinite(distance) || distance < 0D) {
            throw new IllegalArgumentException("La distancia debe ser 0/global o mayor que 0");
        }
        return distance;
    }

    private double parseRange(String raw) {
        double range = Double.parseDouble(raw);
        if (!Double.isFinite(range) || range <= 0D) {
            throw new IllegalArgumentException("El rango debe ser mayor que 0");
        }
        return range;
    }

    private ResolvedPlayers resolvePlayers(String raw) {
        String selector = raw == null ? "" : raw.trim();
        if (selector.isBlank()) {
            throw new IllegalArgumentException("Debes indicar un jugador o selector.");
        }

        if ("@a".equalsIgnoreCase(selector)) {
            List<UUID> players = voiceServer.getMinecraftServer().getPlayers().stream()
                    .map(McServerPlayer::getUuid)
                    .collect(Collectors.toUnmodifiableList());
            return requireResolvedPlayers(selector, players);
        }

        if (selector.regionMatches(true, 0, "@a[", 0, 3)) {
            if (!selector.endsWith("]")) {
                throw new IllegalArgumentException("Selector invalido: falta cerrar ']' en " + selector + ".");
            }
            Map<String, String> options = parseSelectorOptions(selector.substring(3, selector.length() - 1));
            if (options.size() == 1 && options.containsKey("team")) {
                String team = options.get("team");
                List<UUID> players = voiceServer.getMinecraftServer().getPlayers().stream()
                        .filter(player -> matchesTeam(player, team))
                        .map(McServerPlayer::getUuid)
                        .collect(Collectors.toUnmodifiableList());
                return requireResolvedPlayers(selector, players);
            }
            throw new IllegalArgumentException("Selector invalido: solo se soporta @a, @a[team=<equipo>], @a[team=!<equipo>], @a[team=] y @a[team=!].");
        }

        if (selector.startsWith("@")) {
            throw new IllegalArgumentException("Selector invalido: " + selector + ". Usa @a o @a[team=<equipo>].");
        }

        return new ResolvedPlayers(selector, List.of(resolvePlayer(raw)));
    }

    private ResolvedPlayers requireResolvedPlayers(String selector, List<UUID> players) {
        if (players.isEmpty()) {
            throw new IllegalArgumentException("No se encontro ningun jugador que coincida con el selector " + selector + ".");
        }
        return new ResolvedPlayers(selector, players);
    }

    private Map<String, String> parseSelectorOptions(String rawOptions) {
        Map<String, String> options = Arrays.stream(rawOptions.split(","))
                .map(String::trim)
                .filter(option -> !option.isEmpty())
                .map(option -> {
                    int separator = option.indexOf('=');
                    if (separator < 0) {
                        throw new IllegalArgumentException("Selector invalido: falta '=' en " + option);
                    }
                    String key = option.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                    String value = option.substring(separator + 1).trim();
                    return Map.entry(key, value);
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> right));
        if (options.isEmpty()) {
            throw new IllegalArgumentException("Selector invalido: faltan opciones");
        }
        return options;
    }

    private boolean matchesTeam(McServerPlayer player, String rawTeam) {
        boolean negated = rawTeam.startsWith("!");
        String expected = negated ? rawTeam.substring(1) : rawTeam;
        Optional<String> actual = resolveTeamName(player);
        boolean matches = expected.isBlank()
                ? actual.isEmpty()
                : actual.filter(team -> team.equalsIgnoreCase(expected)).isPresent();
        return negated != matches;
    }

    private Optional<String> resolveTeamName(McServerPlayer player) {
        return Suggestions.playerTeamName(player);
    }

    private Collection<UUID> resolveNearbyPlayers(McServerPlayer center, double range) {
        ServerPos3d centerPosition = center.getServerPosition(new ServerPos3d());
        double rangeSquared = range * range;
        return voiceServer.getMinecraftServer().getPlayers().stream()
                .filter(player -> {
                    ServerPos3d playerPosition = player.getServerPosition(new ServerPos3d());
                    return Objects.equals(centerPosition.getWorld(), playerPosition.getWorld()) &&
                            centerPosition.distanceSquared(playerPosition) <= rangeSquared;
                })
                .map(McServerPlayer::getUuid)
                .collect(Collectors.toUnmodifiableList());
    }

    private int removeFromAllAdminGroups(Collection<UUID> players) {
        if (api().findSession(ADMIN_GROUP_SESSION).isEmpty()) return 0;
        int removed = 0;
        for (VoiceChannelSnapshot channel : api().inspectChannels(ADMIN_GROUP_SESSION)) {
            for (UUID playerId : players) {
                if (channel.members().contains(playerId)) {
                    api().removeMember(channel.id(), playerId);
                    removed++;
                }
            }
        }
        return removed;
    }

    private MembershipChangeResult addMembersAndSelect(VoiceChannelId channelId, Collection<UUID> players) {
        VoiceChannelSnapshot before = api().inspectChannel(channelId);
        int added = 0;
        int skipped = 0;
        int failed = 0;
        int selected = 0;
        int selectionFailed = 0;

        for (UUID playerId : players) {
            boolean alreadyMember = before.members().contains(playerId);
            if (alreadyMember) {
                skipped++;
            } else {
                try {
                    api().addMember(channelId, VoiceMemberDefinition.participant(playerId));
                    added++;
                } catch (RuntimeException exception) {
                    failed++;
                    BaseVoiceServer.LOGGER.debug("Failed to add {} to admin voice group {}", playerId, channelId, exception);
                    continue;
                }
            }

            try {
                if (api().selectGroupChannel(playerId, channelId)) {
                    selected++;
                } else {
                    selectionFailed++;
                }
            } catch (RuntimeException exception) {
                selectionFailed++;
                BaseVoiceServer.LOGGER.debug("Failed to select admin voice group {} for {}", channelId, playerId, exception);
            }
        }

        return new MembershipChangeResult(players.size(), added, skipped, failed, selected, selectionFailed);
    }

    private MembershipChangeResult selectGroupForPlayers(VoiceChannelId channelId, Collection<UUID> players) {
        int selected = 0;
        int selectionFailed = 0;
        for (UUID playerId : players) {
            try {
                if (api().selectGroupChannel(playerId, channelId)) {
                    selected++;
                } else {
                    selectionFailed++;
                }
            } catch (RuntimeException exception) {
                selectionFailed++;
                BaseVoiceServer.LOGGER.debug("Failed to select admin voice group {} for {}", channelId, playerId, exception);
            }
        }
        return new MembershipChangeResult(players.size(), 0, 0, 0, selected, selectionFailed);
    }

    private MembershipChangeResult removeMembers(VoiceChannelId channelId, Collection<UUID> players) {
        VoiceChannelSnapshot before = api().inspectChannel(channelId);
        int removed = 0;
        int skipped = 0;
        int failed = 0;

        for (UUID playerId : players) {
            if (!before.members().contains(playerId)) {
                skipped++;
                continue;
            }
            try {
                api().removeMember(channelId, playerId);
                removed++;
            } catch (RuntimeException exception) {
                failed++;
                BaseVoiceServer.LOGGER.debug("Failed to remove {} from admin voice group {}", playerId, channelId, exception);
            }
        }

        return new MembershipChangeResult(players.size(), removed, skipped, failed, 0, 0);
    }

    private UUID resolvePlayer(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
        }

        Optional<McServerPlayer> player = voiceServer.getMinecraftServer().getPlayers().stream()
                .filter(candidate -> candidate.getName().equalsIgnoreCase(raw))
                .findFirst();
        if (player.isPresent()) return player.get().getUuid();
        throw new IllegalArgumentException("Jugador no encontrado: " + raw);
    }

    private VoiceRestrictionType parseRestrictionType(String raw) {
        return VoiceRestrictionType.valueOf(raw.toUpperCase(Locale.ROOT));
    }

    private String summarizeChannel(VoiceChannelSnapshot channel) {
        return channel.id() +
                " activo=" + yes(channel.active()) +
                " routing=" + channel.policy().routingMode() +
                " exclusivo=" + yes(channel.policy().exclusive()) +
                " prioridad=" + channel.policy().priority() +
                " distancia=" + formatDistance(channel.policy().maximumDistance()) +
                " miembros=" + channel.members().size();
    }

    private String summarizeRestriction(VoiceRestrictionSnapshot restriction) {
        return restriction.type() +
                " owner=" + restriction.ownerNamespace() +
                " handle=" + restriction.handle().id() +
                " target=" + restriction.target() +
                " reason=" + restriction.reason() +
                restriction.expiresAt().map(instant -> " expires=" + instant).orElse("");
    }

    private String[] tail(String[] values) {
        return Arrays.copyOfRange(values, 1, values.length);
    }

    private void require(String[] arguments, int length, String usage) {
        if (arguments.length < length) throw new IllegalArgumentException(usage);
    }

    private void usage(McCommandSource source) {
        send(source, "Uso: /mytrixvoice sessions list|inspect <session>");
        send(source, "Uso: /mytrixvoice channels list <session> | inspect <channel>");
        send(source, "Uso: /mytrixvoice groups create|setup|replace|manual|nearby|forcejoin|forceleave|select|unselect|delete|add|remove|distance|clear-members|activate|deactivate|mute|unmute|list|inspect");
        send(source, "Uso: /mytrixvoice players inspect <jugador>");
        send(source, "Uso: /mytrixvoice restrictions list [channel <channel>] | remove <owner> <uuid>");
        send(source, "Uso: /mytrixvoice debug create-session|create-channel|add-player|activate|deactivate|restrict|clear|routing");
    }

    private void groupUsage(McCommandSource source) {
        send(source, "Uso rapido global: /vcgroup setup <grupo> @a[team=<equipo>] global  y luego  /vcgroup replace on");
        send(source, "Uso: /vcgroup replace <on|off|status>");
        send(source, "Uso: /vcgroup manual <on|off|status>");
        send(source, "Uso: /vcgroup create <grupo> [distancia|global]");
        send(source, "Uso: /vcgroup setup <grupo> <jugador|@a|@a[team=<equipo>]> [distancia|global]");
        send(source, "Uso: /vcgroup setup <grupo> @a[team=!<equipo>|team=|team=!] [distancia|global]");
        send(source, "Uso: /vcgroup nearby <grupo> [rango]");
        send(source, "Uso: /vcgroup distance <grupo> <distancia|global>");
        send(source, "Uso: /vcgroup add <grupo> <jugador|@a|@a[team=<equipo>]>");
        send(source, "Uso: /vcgroup forcejoin <grupo> <jugador|@a|@a[team=<equipo>]>");
        send(source, "Uso: /vcgroup select <grupo> <jugador|@a|@a[team=<equipo>]>");
        send(source, "Uso: /vcgroup unselect <jugador|@a|@a[team=<equipo>]>");
        send(source, "Uso: /vcgroup remove <grupo> <jugador|@a|@a[team=<equipo>]>");
        send(source, "Uso: /vcgroup forceleave <grupo> <jugador|@a|@a[team=<equipo>]>");
        send(source, "Uso: /vcgroup leave-all <jugador|@a|@a[team=<equipo>]>");
        send(source, "Selectores: @a, @a[team=rojo], @a[team=!rojo], @a[team=], @a[team=!]");
        send(source, "Uso: /vcgroup delete|activate|deactivate|mute|unmute|inspect <grupo>");
        send(source, "Uso: /vcgroup list");
    }

    private void send(McCommandSource source, String message) {
        source.sendMessage(McTextComponent.literal(message));
    }

    private String yes(boolean value) {
        return value ? "si" : "no";
    }

    private String formatDistance(double distance) {
        return distance <= 0D ? "global" : Double.toString(distance);
    }

    private List<String> startsWith(String argument, String... values) {
        return Arrays.stream(values)
                .filter(value -> value.regionMatches(true, 0, argument, 0, argument.length()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<String> suggestGroups(McCommandSource source, String[] arguments) {
        if (arguments.length == 0) return List.of();
        if (arguments.length == 1) {
            return Arrays.stream(GROUP_ROOT_SUGGESTIONS)
                    .filter(action -> canUseGroupSubcommand(source, action))
                    .filter(action -> action.regionMatches(true, 0, arguments[0], 0, arguments[0].length()))
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        String action = arguments[0].toLowerCase(Locale.ROOT);
        if (!canUseGroupSubcommand(source, action)) {
            return List.of();
        }
        if ((action.equals("replace") || action.equals("manual")) && arguments.length == 2) {
            return startsWith(arguments[1], "on", "off", "status");
        }
        if (arguments.length == 2 && GROUP_ACTIONS_WITH_CHANNEL.contains(action)) {
            return suggestAdminGroups(arguments[1]);
        }
        if (arguments.length == 2 && GROUP_ACTIONS_WITH_TARGET_ONLY.contains(action)) {
            return Suggestions.playersAndSelectors(voiceServer.getMinecraftServer(), source, arguments[1]);
        }
        if (arguments.length == 3 && GROUP_ACTIONS_WITH_TARGET_AFTER_CHANNEL.contains(action)) {
            return Suggestions.playersAndSelectors(voiceServer.getMinecraftServer(), source, arguments[2]);
        }
        if (arguments.length == 3 && (action.equals("create") || action.equals("distance"))) {
            return suggestDistance(arguments[2]);
        }
        if (arguments.length == 4 && (action.equals("setup") || action.equals("create-add"))) {
            return suggestDistance(arguments[3]);
        }
        return McCommand.super.suggest(source, arguments);
    }

    private List<String> suggestAdminGroups(String argument) {
        try {
            if (api().findSession(ADMIN_GROUP_SESSION).isEmpty()) return List.of();
            return api().inspectChannels(ADMIN_GROUP_SESSION).stream()
                    .map(channel -> channel.id().value())
                    .filter(value -> value.regionMatches(true, 0, argument, 0, argument.length()))
                    .collect(Collectors.toList());
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private boolean canUseGroupSubcommand(McCommandSource source, String rawAction) {
        String action = rawAction == null ? "" : rawAction.toLowerCase(Locale.ROOT);
        if (PUBLIC_GROUP_ACTIONS.contains(action)) {
            return hasGroupReadPermission(source);
        }
        return hasAdminPermission(source);
    }

    private boolean hasGroupReadPermission(McCommandSource source) {
        return hasAdminPermission(source) ||
                source.hasPermission("mytrixvoice.group") ||
                source.hasPermission("mytrixvoice.groups");
    }

    private boolean hasAdminPermission(McCommandSource source) {
        return !(source instanceof McServerPlayer) ||
                source.hasPermission("mytrixvoice.admin") ||
                source.hasPermission("mytrixvoice.groups.manage");
    }

    private List<String> suggestDistance(String argument) {
        return startsWith(argument, "global", "32", "64", "128", "256", "512");
    }

    private record ResolvedPlayers(String expression, List<UUID> ids) {

        private int size() {
            return ids.size();
        }
    }

    private record MembershipChangeResult(
            int found,
            int changed,
            int skipped,
            int failed,
            int selected,
            int selectionFailed
    ) {
    }
}
