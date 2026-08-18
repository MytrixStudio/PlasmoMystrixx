package net.mytrix.voice.api;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public record VoiceChannelUpdate(
        Optional<VoiceChannelPolicy> policy,
        Optional<Boolean> active,
        Map<String, String> putMetadata,
        Set<String> removeMetadata
) {

    public VoiceChannelUpdate {
        policy = policy == null ? Optional.empty() : policy;
        active = active == null ? Optional.empty() : active;
        putMetadata = Map.copyOf(putMetadata == null ? Map.of() : putMetadata);
        removeMetadata = Set.copyOf(removeMetadata == null ? Set.of() : removeMetadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Optional<VoiceChannelPolicy> policy = Optional.empty();
        private Optional<Boolean> active = Optional.empty();
        private Map<String, String> putMetadata = Map.of();
        private Set<String> removeMetadata = Set.of();

        public Builder policy(VoiceChannelPolicy policy) {
            this.policy = Optional.of(policy);
            return this;
        }

        public Builder active(boolean active) {
            this.active = Optional.of(active);
            return this;
        }

        public Builder putMetadata(Map<String, String> putMetadata) {
            this.putMetadata = putMetadata;
            return this;
        }

        public Builder removeMetadata(Set<String> removeMetadata) {
            this.removeMetadata = removeMetadata;
            return this;
        }

        public VoiceChannelUpdate build() {
            return new VoiceChannelUpdate(policy, active, putMetadata, removeMetadata);
        }
    }
}
