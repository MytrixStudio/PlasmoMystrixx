package su.plo.voice.server.command;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.plo.slib.api.chat.component.McTextComponent;
import su.plo.slib.api.command.McCommand;
import su.plo.slib.api.command.McCommandSource;
import su.plo.voice.server.BaseVoiceServer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class VoiceDistanceCommand implements McCommand {

    private static final int MIN_DISTANCE = 1;
    private static final int MAX_DISTANCE = 1024;

    private final BaseVoiceServer voiceServer;

    public VoiceDistanceCommand(BaseVoiceServer voiceServer) {
        this.voiceServer = voiceServer;
    }

    @Override
    public void execute(@NotNull McCommandSource source, @NotNull String[] arguments) {
        try {
            if (arguments.length == 0 || arguments[0].equalsIgnoreCase("info")) {
                info(source);
                return;
            }

            switch (arguments[0].toLowerCase(Locale.ROOT)) {
                case "set" -> {
                    require(arguments, 2, "Uso: /vcdistance set <distancia> [distancias_csv]");
                    int defaultDistance = parseDistance(arguments[1]);
                    List<Integer> distances = arguments.length >= 3
                            ? parseDistances(arguments[2])
                            : List.of(defaultDistance);
                    apply(source, distances, defaultDistance);
                }
                case "default" -> {
                    require(arguments, 2, "Uso: /vcdistance default <distancia>");
                    int defaultDistance = parseDistance(arguments[1]);
                    List<Integer> distances = currentDistances();
                    if (!distances.contains(defaultDistance)) {
                        distances = new ArrayList<>(distances);
                        distances.add(defaultDistance);
                    }
                    apply(source, distances, defaultDistance);
                }
                case "add" -> {
                    require(arguments, 2, "Uso: /vcdistance add <distancia>");
                    int distance = parseDistance(arguments[1]);
                    List<Integer> distances = new ArrayList<>(currentDistances());
                    if (!distances.contains(distance)) distances.add(distance);
                    apply(source, distances, currentDefaultDistance());
                }
                case "remove" -> {
                    require(arguments, 2, "Uso: /vcdistance remove <distancia>");
                    int distance = parseDistance(arguments[1]);
                    List<Integer> distances = new ArrayList<>(currentDistances());
                    distances.removeIf(value -> value == distance);
                    if (distances.isEmpty()) throw new IllegalArgumentException("Debe quedar al menos una distancia");
                    int defaultDistance = currentDefaultDistance() == distance ? distances.get(distances.size() - 1) : currentDefaultDistance();
                    apply(source, distances, defaultDistance);
                }
                case "reset" -> apply(source, List.of(8, 16, 32), 16);
                default -> usage(source);
            }
        } catch (IllegalArgumentException exception) {
            send(source, exception.getMessage());
        } catch (Exception exception) {
            send(source, "VoiceDistance: error interno: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    @Override
    public boolean hasPermission(@NotNull McCommandSource source, @Nullable String[] arguments) {
        return source.hasPermission("mytrixvoice.distance");
    }

    @Override
    public @NotNull List<String> suggest(@NotNull McCommandSource source, @NotNull String[] arguments) {
        if (arguments.length == 0) return List.of("info", "set", "default", "add", "remove", "reset");
        if (arguments.length == 1) return startsWith(arguments[0], "info", "set", "default", "add", "remove", "reset");
        if (arguments.length == 2 && Set.of("set", "default", "add", "remove").contains(arguments[0].toLowerCase(Locale.ROOT))) {
            return startsWith(arguments[1], "8", "16", "32", "48", "64", "96", "128", "256");
        }
        if (arguments.length == 3 && arguments[0].equalsIgnoreCase("set")) {
            return startsWith(arguments[2], "8,16,32", "16,32,64", "32,64,128", "64,128,256");
        }
        return McCommand.super.suggest(source, arguments);
    }

    private void apply(McCommandSource source, List<Integer> distances, int defaultDistance) {
        List<Integer> normalized = distances.stream()
                .map(this::validateDistance)
                .distinct()
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));
        if (!normalized.contains(defaultDistance)) normalized.add(defaultDistance);
        normalized.sort(Integer::compareTo);

        voiceServer.updateProximityDistances(normalized, defaultDistance);
        send(source, "Distancia de proximidad actualizada para todos. Default=" + defaultDistance + " opciones=" + normalized);
    }

    private void info(McCommandSource source) {
        send(source, "Distancia de proximidad default=" + currentDefaultDistance() + " opciones=" + currentDistances());
        send(source, "Usa /vcdistance set <distancia> para cambiarla para todos.");
    }

    private List<Integer> currentDistances() {
        return new ArrayList<>(voiceServer.getConfig().voice().proximity().distances());
    }

    private int currentDefaultDistance() {
        return voiceServer.getConfig().voice().proximity().defaultDistance();
    }

    private List<Integer> parseDistances(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .map(this::parseDistance)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private int parseDistance(String raw) {
        return validateDistance(Integer.parseInt(raw));
    }

    private int validateDistance(int distance) {
        if (distance < MIN_DISTANCE || distance > MAX_DISTANCE) {
            throw new IllegalArgumentException("La distancia debe estar entre " + MIN_DISTANCE + " y " + MAX_DISTANCE);
        }
        return distance;
    }

    private void require(String[] arguments, int length, String usage) {
        if (arguments.length < length) throw new IllegalArgumentException(usage);
    }

    private void usage(McCommandSource source) {
        send(source, "Uso: /vcdistance");
        send(source, "Uso: /vcdistance set <distancia> [distancias_csv]");
        send(source, "Uso: /vcdistance default <distancia>");
        send(source, "Uso: /vcdistance add|remove <distancia>");
        send(source, "Uso: /vcdistance reset");
    }

    private List<String> startsWith(String argument, String... values) {
        String value = argument == null ? "" : argument;
        return Arrays.stream(values)
                .filter(option -> option.regionMatches(true, 0, value, 0, value.length()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private void send(McCommandSource source, String message) {
        source.sendMessage(McTextComponent.literal(message));
    }
}
