package net.mytrix.voice.api;

import java.util.Map;

public record VoiceSessionOptions(
        PersistenceMode persistenceMode,
        boolean fallbackToProximityWhenPaused,
        Map<String, String> metadata
) {

    public VoiceSessionOptions {
        if (persistenceMode == null) persistenceMode = PersistenceMode.MEMORY_ONLY;
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }

    public static VoiceSessionOptions memoryOnly() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private PersistenceMode persistenceMode = PersistenceMode.MEMORY_ONLY;
        private boolean fallbackToProximityWhenPaused = true;
        private Map<String, String> metadata = Map.of();

        public Builder persistence(PersistenceMode persistenceMode) {
            this.persistenceMode = persistenceMode;
            return this;
        }

        public Builder fallbackToProximityWhenPaused(boolean fallbackToProximityWhenPaused) {
            this.fallbackToProximityWhenPaused = fallbackToProximityWhenPaused;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public VoiceSessionOptions build() {
            return new VoiceSessionOptions(persistenceMode, fallbackToProximityWhenPaused, metadata);
        }
    }
}
