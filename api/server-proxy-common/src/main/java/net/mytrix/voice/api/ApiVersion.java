package net.mytrix.voice.api;

import java.util.Objects;

/**
 * Immutable semantic version for the public Mytrix Voice API.
 *
 * <p>This type is safe to use from any thread and has no dependency on
 * Minecraft, OpenAL, networking internals, or loader-specific classes.</p>
 */
public record ApiVersion(int major, int minor, int patch) implements Comparable<ApiVersion> {

    public ApiVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("API version numbers must be non-negative");
        }
    }

    @Override
    public int compareTo(ApiVersion other) {
        Objects.requireNonNull(other, "other");
        int majorCompare = Integer.compare(major, other.major);
        if (majorCompare != 0) return majorCompare;
        int minorCompare = Integer.compare(minor, other.minor);
        if (minorCompare != 0) return minorCompare;
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
