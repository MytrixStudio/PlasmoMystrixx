package net.mytrix.voice.api;

import java.util.Objects;
import java.util.regex.Pattern;

public final class VoiceIdentifierValidator {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]{2,64}");
    private static final Pattern VALUE = Pattern.compile("[a-zA-Z0-9_.-]{1,128}");

    private VoiceIdentifierValidator() {
    }

    public static void validateNamespace(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new InvalidVoiceIdentifierException("Invalid namespace: " + namespace);
        }
    }

    public static void validateValue(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!VALUE.matcher(value).matches()) {
            throw new InvalidVoiceIdentifierException("Invalid " + label + ": " + value);
        }
    }
}
