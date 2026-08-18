package net.mytrix.voice.api;

import java.util.Map;

public record VoiceChannelOptions(
        VoiceChannelPolicy policy,
        boolean active,
        Map<String, String> metadata
) {

    public VoiceChannelOptions {
        if (policy == null) policy = VoiceChannelPolicy.proximity();
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private VoiceChannelPolicy policy = VoiceChannelPolicy.proximity();
        private boolean active;
        private Map<String, String> metadata = Map.of();

        public Builder policy(VoiceChannelPolicy policy) {
            this.policy = policy;
            return this;
        }

        public Builder active(boolean active) {
            this.active = active;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public VoiceChannelOptions build() {
            return new VoiceChannelOptions(policy, active, metadata);
        }
    }
}
