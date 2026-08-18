package net.mytrix.voice.api;

import java.util.Objects;

public record VoiceChannelId(VoiceSessionId sessionId, String value) implements Comparable<VoiceChannelId> {

    public VoiceChannelId {
        Objects.requireNonNull(sessionId, "sessionId");
        VoiceIdentifierValidator.validateValue(value, "channel id");
    }

    public static VoiceChannelId of(VoiceSessionId sessionId, String value) {
        return new VoiceChannelId(sessionId, value);
    }

    public static VoiceChannelId parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        int separator = raw.lastIndexOf('/');
        if (separator <= 0 || separator == raw.length() - 1) {
            throw new InvalidVoiceIdentifierException("Expected channel id namespace:session/channel, got: " + raw);
        }
        return of(VoiceSessionId.parse(raw.substring(0, separator)), raw.substring(separator + 1));
    }

    @Override
    public String toString() {
        return sessionId + "/" + value;
    }

    @Override
    public int compareTo(VoiceChannelId other) {
        return toString().compareTo(other.toString());
    }
}
