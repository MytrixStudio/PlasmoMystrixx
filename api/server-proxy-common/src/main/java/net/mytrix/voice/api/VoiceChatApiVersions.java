package net.mytrix.voice.api;

/**
 * Public API version constants.
 *
 * <p>Compatibility policy: major changes can break binary/source
 * compatibility, minor changes add compatible functionality, and patch changes
 * are compatible fixes.</p>
 */
public final class VoiceChatApiVersions {

    public static final int MAJOR = 1;
    public static final int MINOR = 0;
    public static final int PATCH = 0;

    public static final ApiVersion CURRENT = new ApiVersion(MAJOR, MINOR, PATCH);

    private VoiceChatApiVersions() {
    }
}
