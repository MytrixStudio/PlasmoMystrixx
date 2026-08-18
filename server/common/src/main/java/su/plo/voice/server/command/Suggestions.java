package su.plo.voice.server.command;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.plo.slib.api.command.McCommandSource;
import su.plo.slib.api.server.McServerLib;
import su.plo.slib.api.server.entity.player.McServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class Suggestions {

    public static List<String> players(
            @NotNull McServerLib minecraftServer,
            @Nullable McCommandSource source,
            @NotNull String argument
    ) {
        List<String> suggestions = new ArrayList<>();
        if ("@a".startsWith(argument)) {
            suggestions.add("@a");
        }

        suggestions.addAll(minecraftServer.getPlayers()
                .stream()
                .filter(player -> source != null
                        && (!(source instanceof McServerPlayer) || ((McServerPlayer) source).canSee(player))
                        && player.getName().regionMatches(true, 0, argument, 0, argument.length()))
                .map(McServerPlayer::getName)
                .collect(Collectors.toList()));

        return suggestions;
    }

    public static List<String> playersAndSelectors(
            @NotNull McServerLib minecraftServer,
            @Nullable McCommandSource source,
            @NotNull String argument
    ) {
        LinkedHashSet<String> suggestions = new LinkedHashSet<>();
        suggestions.addAll(selectorSuggestions(minecraftServer, argument));
        suggestions.addAll(players(minecraftServer, source, argument));
        return new ArrayList<>(suggestions);
    }

    public static List<String> selectorSuggestions(
            @NotNull McServerLib minecraftServer,
            @NotNull String argument
    ) {
        LinkedHashSet<String> suggestions = new LinkedHashSet<>();

        addIfStartsWith(suggestions, argument, "@a");
        addIfStartsWith(suggestions, argument, "@a[team=]");
        addIfStartsWith(suggestions, argument, "@a[team=!]");

        String lower = argument.toLowerCase(Locale.ROOT);
        String positivePrefix = "@a[team=";
        String negativePrefix = "@a[team=!";
        if (lower.startsWith(positivePrefix) || lower.startsWith(negativePrefix)) {
            boolean negated = lower.startsWith(negativePrefix);
            String teamPrefix = argument.substring(negated ? negativePrefix.length() : positivePrefix.length());
            if (teamPrefix.endsWith("]")) {
                teamPrefix = teamPrefix.substring(0, teamPrefix.length() - 1);
            }

            String selectorPrefix = negated ? negativePrefix : positivePrefix;
            for (String teamName : scoreboardTeamNames(minecraftServer)) {
                if (teamName.regionMatches(true, 0, teamPrefix, 0, teamPrefix.length())) {
                    suggestions.add(selectorPrefix + teamName + "]");
                }
            }
        }

        return new ArrayList<>(suggestions);
    }

    public static Set<String> scoreboardTeamNames(@NotNull McServerLib minecraftServer) {
        LinkedHashSet<String> teamNames = new LinkedHashSet<>();
        for (McServerPlayer player : minecraftServer.getPlayers()) {
            playerTeamName(player).ifPresent(teamNames::add);
            collectScoreboardTeamNames(player.getInstance(), teamNames);
        }
        return teamNames.stream()
                .sorted(Comparator.comparing(value -> value.toLowerCase(Locale.ROOT)))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static Optional<String> playerTeamName(@NotNull McServerPlayer player) {
        Object nativePlayer = player.getInstance();
        Optional<Object> team = invokeNoArg(nativePlayer, "getTeam", "m_5647_", "cD")
                .or(() -> invokeNoArg(player, "getTeam", "m_5647_", "cD"));
        if (team.isEmpty()) return Optional.empty();
        return teamName(team.get());
    }

    private static Optional<String> teamName(Object team) {
        return invokeNoArg(team, "getName", "m_5758_", "b")
                .map(String::valueOf)
                .filter(value -> !value.isBlank());
    }

    private static void collectScoreboardTeamNames(Object nativePlayer, Set<String> teamNames) {
        if (nativePlayer == null) return;

        readField(nativePlayer, "server", "f_8924_")
                .or(() -> invokeNoArg(nativePlayer, "getServer", "m_20194_"))
                .flatMap(server -> invokeNoArg(server, "getScoreboard", "m_129896_", "aJ"))
                .ifPresent(scoreboard -> collectTeamCollection(scoreboard, teamNames));

        invokeNoArg(nativePlayer, "serverLevel", "m_9236_", "level", "m_9236_", "getLevel", "m_20194_")
                .flatMap(level -> invokeNoArg(level, "getScoreboard", "m_6188_", "R", "ab", "M"))
                .ifPresent(scoreboard -> collectTeamCollection(scoreboard, teamNames));
    }

    private static void collectTeamCollection(Object scoreboard, Set<String> teamNames) {
        Optional<Object> teamsObject = invokeNoArg(scoreboard, "getPlayerTeams", "m_83491_", "g", "f");
        if (teamsObject.isPresent() && teamsObject.get() instanceof Collection<?> teams) {
            teams.forEach(team -> teamName(team).ifPresent(teamNames::add));
        }
    }

    private static Optional<Object> invokeNoArg(Object target, String... methodNames) {
        if (target == null) return Optional.empty();
        for (String methodName : methodNames) {
            try {
                Method method = findNoArgMethod(target.getClass(), methodName);
                method.setAccessible(true);
                Object value = method.invoke(target);
                if (value != null) return Optional.of(value);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return Optional.empty();
    }

    private static Optional<Object> readField(Object target, String... fieldNames) {
        if (target == null) return Optional.empty();
        for (String fieldName : fieldNames) {
            try {
                Field field = findField(target.getClass(), fieldName);
                field.setAccessible(true);
                Object value = field.get(target);
                if (value != null) return Optional.of(value);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return Optional.empty();
    }

    private static Method findNoArgMethod(Class<?> type, String methodName) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(methodName);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return type.getMethod(methodName);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return type.getField(fieldName);
    }

    private static void addIfStartsWith(Set<String> suggestions, String argument, String value) {
        if (value.regionMatches(true, 0, argument, 0, argument.length())) {
            suggestions.add(value);
        }
    }

    public Suggestions() {
    }
}
