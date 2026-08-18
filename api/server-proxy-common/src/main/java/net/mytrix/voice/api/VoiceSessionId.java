package net.mytrix.voice.api;

import java.util.Objects;

public record VoiceSessionId(String namespace, String value) implements Comparable<VoiceSessionId> {

    public VoiceSessionId {
        VoiceIdentifierValidator.validateNamespace(namespace);
        VoiceIdentifierValidator.validateValue(value, "session id");
    }

    public static VoiceSessionId of(String namespace, String value) {
        return new VoiceSessionId(namespace, value);
    }

    public static VoiceSessionId parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        int separator = raw.indexOf(':');
        if (separator <= 0 || separator == raw.length() - 1) {
            throw new InvalidVoiceIdentifierException("Expected session id namespace:value, got: " + raw);
        }
        return of(raw.substring(0, separator), raw.substring(separator + 1));
    }

    @Override
    public String toString() {
        return namespace + ":" + value;
    }

    @Override
    public int compareTo(VoiceSessionId other) {
        return toString().compareTo(other.toString());
    }
}
