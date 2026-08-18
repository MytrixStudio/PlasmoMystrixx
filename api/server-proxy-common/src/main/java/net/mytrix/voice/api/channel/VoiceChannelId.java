package net.mytrix.voice.api.channel;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Public namespaced channel identifier owned by an integration namespace.
 *
 * <p>Examples: {@code party_mod:party/15}, {@code examplemod:red_team}. The
 * namespace should be the owner mod id. The path is stable technical identity,
 * not display text.</p>
 */
public record VoiceChannelId(String namespace, String path) implements Comparable<VoiceChannelId> {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]{2,64}");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_.\\-/]{1,128}");

    public VoiceChannelId {
        namespace = Objects.requireNonNull(namespace, "namespace").trim();
        path = Objects.requireNonNull(path, "path").trim();
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid voice channel namespace: " + namespace);
        }
        if (!PATH.matcher(path).matches() || path.startsWith("/") || path.endsWith("/") || path.contains("//")) {
            throw new IllegalArgumentException("Invalid voice channel path: " + path);
        }
    }

    public static VoiceChannelId of(String namespace, String path) {
        return new VoiceChannelId(namespace, path);
    }

    public static VoiceChannelId parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        int separator = raw.indexOf(':');
        if (separator <= 0 || separator == raw.length() - 1) {
            throw new IllegalArgumentException("Expected voice channel id namespace:path, got: " + raw);
        }
        return of(raw.substring(0, separator), raw.substring(separator + 1));
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }

    @Override
    public int compareTo(VoiceChannelId other) {
        return toString().compareTo(other.toString());
    }
}
