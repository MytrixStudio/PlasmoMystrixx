package net.mytrix.voice.api;

import java.time.Duration;
import java.util.Optional;

public record VoiceRestrictionRequest(
        String ownerNamespace,
        String reason,
        RestrictionTarget target,
        VoiceRestrictionType type,
        int priority,
        Optional<Duration> duration
) {

    public VoiceRestrictionRequest {
        VoiceIdentifierValidator.validateNamespace(ownerNamespace);
        if (reason == null || reason.isBlank()) reason = "unspecified";
        if (target == null) target = RestrictionTarget.global();
        if (type == null) type = VoiceRestrictionType.BLOCK_BOTH;
        duration = duration == null ? Optional.empty() : duration;
        duration.ifPresent(value -> {
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("duration must be positive");
            }
        });
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String ownerNamespace = "mytrixvoice";
        private String reason = "unspecified";
        private RestrictionTarget target = RestrictionTarget.global();
        private VoiceRestrictionType type = VoiceRestrictionType.BLOCK_BOTH;
        private int priority;
        private Optional<Duration> duration = Optional.empty();

        public Builder ownerNamespace(String ownerNamespace) {
            this.ownerNamespace = ownerNamespace;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder target(RestrictionTarget target) {
            this.target = target;
            return this;
        }

        public Builder type(VoiceRestrictionType type) {
            this.type = type;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder duration(Duration duration) {
            this.duration = Optional.of(duration);
            return this;
        }

        public Builder duration(Optional<Duration> duration) {
            this.duration = duration;
            return this;
        }

        public VoiceRestrictionRequest build() {
            return new VoiceRestrictionRequest(ownerNamespace, reason, target, type, priority, duration);
        }
    }
}
