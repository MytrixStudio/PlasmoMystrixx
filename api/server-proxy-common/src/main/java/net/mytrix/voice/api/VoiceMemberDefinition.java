package net.mytrix.voice.api;

import java.util.Objects;
import java.util.UUID;

public record VoiceMemberDefinition(
        UUID playerId,
        String role,
        boolean canTransmit,
        boolean canReceive,
        VoiceCapabilities capabilities
) {

    public VoiceMemberDefinition {
        Objects.requireNonNull(playerId, "playerId");
        if (role == null || role.isBlank()) role = MembershipRole.MEMBER.name().toLowerCase();
        if (capabilities == null) capabilities = new VoiceCapabilities(canTransmit, canReceive, false, false, false);
    }

    public static VoiceMemberDefinition participant(UUID playerId) {
        return builder(playerId).build();
    }

    public static Builder builder(UUID playerId) {
        return new Builder(playerId);
    }

    public static final class Builder {
        private final UUID playerId;
        private String role = "participant";
        private boolean canTransmit = true;
        private boolean canReceive = true;
        private VoiceCapabilities capabilities;

        private Builder(UUID playerId) {
            this.playerId = playerId;
        }

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder canTransmit(boolean canTransmit) {
            this.canTransmit = canTransmit;
            return this;
        }

        public Builder canReceive(boolean canReceive) {
            this.canReceive = canReceive;
            return this;
        }

        public Builder capabilities(VoiceCapabilities capabilities) {
            this.capabilities = capabilities;
            this.canTransmit = capabilities.transmit();
            this.canReceive = capabilities.receive();
            return this;
        }

        public VoiceMemberDefinition build() {
            return new VoiceMemberDefinition(playerId, role, canTransmit, canReceive, capabilities);
        }
    }
}
