package net.mytrix.voice.api.channel;

import java.util.Optional;

/**
 * Typed result returned by channel registration.
 */
public record RegistrationResult(
        RegistrationStatus status,
        Optional<VoiceChannelHandle> handle,
        String message
) {

    public RegistrationResult {
        status = status == null ? RegistrationStatus.ERROR : status;
        handle = handle == null ? Optional.empty() : handle;
        message = message == null ? "" : message;
    }

    public boolean successful() {
        return status == RegistrationStatus.SUCCESS;
    }

    public static RegistrationResult success(VoiceChannelHandle handle) {
        return new RegistrationResult(RegistrationStatus.SUCCESS, Optional.of(handle), "registered");
    }

    public static RegistrationResult failure(RegistrationStatus status, String message) {
        return new RegistrationResult(status, Optional.empty(), message);
    }
}
