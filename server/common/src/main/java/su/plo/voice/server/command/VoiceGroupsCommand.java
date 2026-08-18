package su.plo.voice.server.command;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.plo.slib.api.chat.component.McTextComponent;
import su.plo.slib.api.command.McCommand;
import su.plo.slib.api.command.McCommandSource;
import su.plo.slib.api.server.entity.player.McServerPlayer;
import su.plo.voice.server.BaseVoiceServer;
import su.plo.voice.server.group.MytrixVoiceGroupService;
import su.plo.voice.server.group.MytrixVoiceGroupService.CreateOptions;
import su.plo.voice.server.group.MytrixVoiceGroupService.GroupRecord;
import su.plo.voice.server.group.MytrixVoiceGroupService.JoinResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Player-facing group commands backed by MytrixVoiceGroupService.
 */
public final class VoiceGroupsCommand implements McCommand {

    private static final String[] ROOT_SUGGESTIONS = {
            "browse", "create", "join", "invite", "leave", "info", "delete",
            "kick", "ban", "unban", "transfer", "set", "unset", "select", "help"
    };

    private final BaseVoiceServer voiceServer;

    public VoiceGroupsCommand(BaseVoiceServer voiceServer) {
        this.voiceServer = voiceServer;
    }

    @Override
    public void execute(@NotNull McCommandSource source, @NotNull String[] arguments) {
        try {
            if (!groups().playerCommandsEnabled() && !source.hasPermission("mytrixvoice.groups.manage")) {
                send(source, "Los grupos manuales estan deshabilitados por el servidor");
                return;
            }

            if (arguments.length == 0 || arguments[0].equalsIgnoreCase("browse") || arguments[0].equalsIgnoreCase("list")) {
                browse(source);
                return;
            }

            switch (arguments[0].toLowerCase(Locale.ROOT)) {
                case "help" -> usage(source);
                case "create" -> create(player(source), tail(arguments));
                case "join" -> {
                    require(arguments, 2, "Uso: /groups join <grupo> [password]");
                    JoinResult result = groups().joinGroup(player(source), arguments[1], arguments.length >= 3 ? Optional.of(arguments[2]) : Optional.empty(), source.hasPermission("mytrixvoice.groups.manage"));
                    send(source, result.joined()
                            ? "Entraste al grupo " + result.group().orElseThrow().name()
                            : result.message());
                }
                case "invite" -> {
                    require(arguments, 2, "Uso: /groups invite <jugador>");
                    McServerPlayer target = groups().findOnlinePlayer(arguments[1])
                            .orElseThrow(() -> new IllegalArgumentException("Jugador no encontrado: " + arguments[1]));
                    groups().invite(player(source), target);
                }
                case "leave" -> {
                    boolean left = groups().leaveGroup(player(source).getUuid(), true);
                    if (!left) send(source, "No estas en ningun grupo");
                }
                case "info" -> info(source, arguments.length >= 2 ? Optional.of(arguments[1]) : Optional.empty());
                case "delete" -> delete(source, arguments.length >= 2 ? Optional.of(arguments[1]) : Optional.empty());
                case "kick" -> {
                    require(arguments, 2, "Uso: /groups kick <jugador>");
                    groups().kick(player(source), onlinePlayer(arguments[1]));
                }
                case "ban" -> {
                    require(arguments, 2, "Uso: /groups ban <jugador>");
                    groups().ban(player(source), onlinePlayer(arguments[1]));
                }
                case "unban" -> {
                    require(arguments, 2, "Uso: /groups unban <jugador|uuid>");
                    groups().unban(player(source), resolvePlayerId(arguments[1]));
                }
                case "transfer" -> {
                    require(arguments, 2, "Uso: /groups transfer <jugador>");
                    groups().transfer(player(source), onlinePlayer(arguments[1]));
                }
                case "set" -> set(player(source), tail(arguments));
                case "unset" -> unset(player(source), tail(arguments));
                case "select" -> {
                    boolean selected = groups().selectCurrentGroup(player(source));
                    send(source, selected ? "Canal de grupo seleccionado" : "No estas en ningun grupo");
                }
                default -> usage(source);
            }
        } catch (IllegalArgumentException exception) {
            send(source, exception.getMessage());
        } catch (Exception exception) {
            send(source, "Groups: error interno: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    @Override
    public boolean hasPermission(@NotNull McCommandSource source, @Nullable String[] arguments) {
        MytrixVoiceGroupService service = voiceServer.getGroupService();
        boolean manualCommandsAllowed = service == null ||
                !service.initialized() ||
                service.playerCommandsEnabled() ||
                source.hasPermission("mytrixvoice.groups.manage");
        return source.hasPermission("mytrixvoice.groups") && manualCommandsAllowed;
    }

    @Override
    public @NotNull List<String> suggest(@NotNull McCommandSource source, @NotNull String[] arguments) {
        if (arguments.length == 0) return List.of(ROOT_SUGGESTIONS);
        if (arguments.length == 1) {
            return startsWith(arguments[0], ROOT_SUGGESTIONS);
        }

        String action = arguments[0].toLowerCase(Locale.ROOT);
        if (arguments.length == 2 && Set.of("join", "info", "delete").contains(action)) {
            return safeGroupSuggestions(arguments[1]);
        }
        if (arguments.length == 2 && Set.of("invite", "kick", "ban", "unban", "transfer").contains(action)) {
            return Suggestions.players(voiceServer.getMinecraftServer(), source, arguments[1]);
        }
        if (arguments.length == 2 && action.equals("set")) {
            return startsWith(arguments[1], "name", "password", "persistent", "permissions");
        }
        if (arguments.length == 2 && action.equals("unset")) {
            return startsWith(arguments[1], "password", "permissions");
        }
        return McCommand.super.suggest(source, arguments);
    }

    private List<String> safeGroupSuggestions(String argument) {
        try {
            return groups().suggestGroups(argument);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void browse(McCommandSource source) {
        McServerPlayer viewer = source instanceof McServerPlayer player ? player : null;
        List<GroupRecord> groups = viewer == null
                ? new ArrayList<>(groups().allGroups())
                : new ArrayList<>(groups().visibleGroups(viewer, source.hasPermission("mytrixvoice.groups.manage")));
        if (groups.isEmpty()) {
            send(source, "No hay grupos de voz. Usa /groups create");
            return;
        }

        send(source, "Grupos de voz: " + groups.size());
        for (GroupRecord group : groups) {
            send(source, "- " + group.summary());
        }
    }

    private void create(McServerPlayer owner, String[] arguments) {
        ParsedCreateArgs parsed = parseCreateArgs(arguments);
        GroupRecord group = groups().createGroup(
                owner,
                new CreateOptions(
                        parsed.name().isBlank() ? Optional.empty() : Optional.of(parsed.name()),
                        parsed.password(),
                        parsed.persistent(),
                        parsed.permissions()
                )
        );
        send(owner, "Grupo creado: " + group.name() + " id=" + group.id());
    }

    private void info(McCommandSource source, Optional<String> groupToken) {
        GroupRecord group = groupToken
                .flatMap(groups()::findGroup)
                .or(() -> source instanceof McServerPlayer player ? groups().currentGroup(player.getUuid()) : Optional.empty())
                .orElseThrow(() -> new IllegalArgumentException("Grupo no encontrado"));

        send(source, "Grupo: " + group.name());
        send(source, "ID: " + group.id());
        send(source, "Owner: " + group.owner());
        send(source, "Miembros: " + group.members().size());
        send(source, "Password: " + (group.password() == null ? "no" : "si"));
        send(source, "Persistente: " + yes(group.persistent()));
        send(source, "Permisos: " + (group.permissions().isEmpty() ? "ninguno" : group.permissions()));
        group.members().stream().sorted().forEach(member -> send(source, "- " + member));
    }

    private void delete(McCommandSource source, Optional<String> groupToken) {
        McServerPlayer actor = player(source);
        GroupRecord group = groupToken
                .flatMap(groups()::findGroup)
                .or(() -> groups().currentGroup(actor.getUuid()))
                .orElseThrow(() -> new IllegalArgumentException("Grupo no encontrado"));
        if (!actor.hasPermission("mytrixvoice.groups.manage") && !actor.getUuid().equals(group.owner())) {
            throw new IllegalArgumentException("Solo el lider o un admin puede eliminar el grupo");
        }
        groups().deleteGroup(group, true);
        send(source, "Grupo eliminado: " + group.name());
    }

    private void set(McServerPlayer actor, String[] arguments) {
        require(arguments, 2, "Uso: /groups set name|password|persistent|permissions <valor>");
        switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "name" -> groups().updateName(actor, join(arguments, 1));
            case "password" -> groups().updatePassword(actor, arguments[1]);
            case "persistent" -> groups().updatePersistent(actor, Boolean.parseBoolean(arguments[1]));
            case "permissions" -> groups().updatePermissions(actor, splitCsv(join(arguments, 1)));
            default -> throw new IllegalArgumentException("Flag desconocido: " + arguments[0]);
        }
    }

    private void unset(McServerPlayer actor, String[] arguments) {
        require(arguments, 1, "Uso: /groups unset password|permissions");
        switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "password" -> groups().updatePassword(actor, null);
            case "permissions" -> groups().updatePermissions(actor, Set.of());
            default -> throw new IllegalArgumentException("Flag desconocido: " + arguments[0]);
        }
    }

    private ParsedCreateArgs parseCreateArgs(String[] arguments) {
        Map<String, String> flags = new LinkedHashMap<>();
        StringBuilder name = new StringBuilder();
        String currentFlag = null;
        StringBuilder currentValue = new StringBuilder();

        for (String argument : arguments) {
            String key = flagKey(argument);
            if (key != null) {
                if (currentFlag != null) {
                    flags.put(currentFlag, currentValue.toString().trim());
                    currentValue.setLength(0);
                }
                currentFlag = key;
                String inlineValue = inlineFlagValue(argument);
                if (inlineValue != null) currentValue.append(inlineValue);
                continue;
            }

            if (currentFlag == null) {
                if (name.length() > 0) name.append(' ');
                name.append(argument);
            } else {
                if (currentValue.length() > 0) currentValue.append(' ');
                currentValue.append(argument);
            }
        }

        if (currentFlag != null) {
            flags.put(currentFlag, currentValue.toString().trim());
        }

        String parsedName = flags.getOrDefault("name", name.toString().trim());
        Optional<String> password = Optional.ofNullable(blankToNull(flags.get("password")));
        boolean persistent = Boolean.parseBoolean(flags.getOrDefault("persistent", "false"));
        Set<String> permissions = splitCsv(flags.getOrDefault("permissions", ""));
        return new ParsedCreateArgs(parsedName, password, persistent, permissions);
    }

    private String flagKey(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        String key = colon >= 0 ? lower.substring(0, colon) : lower.endsWith(":") ? lower.substring(0, lower.length() - 1) : null;
        if (key == null) return null;
        return Set.of("name", "password", "persistent", "permissions").contains(key) ? key : null;
    }

    private String inlineFlagValue(String token) {
        int colon = token.indexOf(':');
        if (colon < 0 || colon == token.length() - 1) return null;
        return token.substring(colon + 1);
    }

    private Set<String> splitCsv(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private UUID resolvePlayerId(String nameOrUuid) {
        return groups().findOnlinePlayer(nameOrUuid)
                .map(McServerPlayer::getUuid)
                .orElseGet(() -> UUID.fromString(nameOrUuid));
    }

    private McServerPlayer onlinePlayer(String nameOrUuid) {
        return groups().findOnlinePlayer(nameOrUuid)
                .orElseThrow(() -> new IllegalArgumentException("Jugador no encontrado: " + nameOrUuid));
    }

    private McServerPlayer player(McCommandSource source) {
        if (source instanceof McServerPlayer player) return player;
        throw new IllegalArgumentException("Este comando debe ejecutarlo un jugador");
    }

    private MytrixVoiceGroupService groups() {
        MytrixVoiceGroupService service = voiceServer.getGroupService();
        if (service == null || !service.initialized()) {
            throw new IllegalStateException("El servicio de grupos todavia no esta listo");
        }
        return service;
    }

    private String[] tail(String[] values) {
        return Arrays.copyOfRange(values, 1, values.length);
    }

    private void require(String[] arguments, int length, String usage) {
        if (arguments.length < length) throw new IllegalArgumentException(usage);
    }

    private String join(String[] values, int start) {
        return String.join(" ", Arrays.copyOfRange(values, start, values.length)).trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private List<String> startsWith(String argument, String... values) {
        return Arrays.stream(values)
                .filter(value -> value.regionMatches(true, 0, argument, 0, argument.length()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private void usage(McCommandSource source) {
        send(source, "Uso: /groups browse");
        send(source, "Uso: /groups create [nombre] [password:<pass>] [persistent:true] [permissions:perm.a,perm.b]");
        send(source, "Uso: /groups join <grupo> [password]");
        send(source, "Uso: /groups invite|kick|ban|transfer <jugador>");
        send(source, "Uso: /groups leave|info|delete|select");
        send(source, "Uso: /groups set name|password|persistent|permissions <valor>");
        send(source, "Uso: /groups unset password|permissions");
    }

    private void send(McCommandSource source, String message) {
        source.sendMessage(McTextComponent.literal(message));
    }

    private String yes(boolean value) {
        return value ? "si" : "no";
    }

    private record ParsedCreateArgs(String name, Optional<String> password, boolean persistent, Set<String> permissions) {
    }
}
