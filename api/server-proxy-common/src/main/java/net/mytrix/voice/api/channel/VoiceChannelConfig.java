package net.mytrix.voice.api.channel;

import java.util.Objects;

/**
 * Immutable public channel configuration.
 *
 * <p>For distance-free group calls use {@link VoiceChannelMode#GROUP},
 * {@link VoiceSpatialMode#NON_POSITIONAL}, {@link DimensionPolicy#CROSS_DIMENSION},
 * and {@link VoiceTransmissionPolicy#EXCLUSIVE}. The runtime maps that to a
 * non-spatial direct audio source, not to an inflated proximity distance.</p>
 */
public record VoiceChannelConfig(
        VoiceChannelMode channelMode,
        VoiceSpatialMode spatialMode,
        DimensionPolicy dimensionPolicy,
        MembershipMode membershipMode,
        VoiceTransmissionPolicy transmissionPolicy,
        float baseVolume,
        boolean allowTransmission,
        boolean showSpeakingIndicator,
        int maxMembers
) {

    public VoiceChannelConfig {
        channelMode = Objects.requireNonNull(channelMode, "channelMode");
        spatialMode = Objects.requireNonNull(spatialMode, "spatialMode");
        dimensionPolicy = Objects.requireNonNull(dimensionPolicy, "dimensionPolicy");
        membershipMode = Objects.requireNonNull(membershipMode, "membershipMode");
        transmissionPolicy = Objects.requireNonNull(transmissionPolicy, "transmissionPolicy");
        if (!Float.isFinite(baseVolume) || baseVolume < 0F || baseVolume > 4F) {
            throw new IllegalArgumentException("baseVolume must be finite and between 0.0 and 4.0");
        }
        if (maxMembers <= 0) {
            throw new IllegalArgumentException("maxMembers must be positive");
        }
        if (channelMode == VoiceChannelMode.PROXIMITY && spatialMode == VoiceSpatialMode.NON_POSITIONAL) {
            throw new IllegalArgumentException("Proximity channels must be positional");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static VoiceChannelConfig distanceFreeGroup() {
        return builder()
                .channelMode(VoiceChannelMode.GROUP)
                .spatialMode(VoiceSpatialMode.NON_POSITIONAL)
                .dimensionPolicy(DimensionPolicy.CROSS_DIMENSION)
                .baseVolume(1.0F)
                .allowTransmission(true)
                .showSpeakingIndicator(true)
                .maxMembers(20)
                .build();
    }

    public static final class Builder {
        private VoiceChannelMode channelMode = VoiceChannelMode.GROUP;
        private VoiceSpatialMode spatialMode = VoiceSpatialMode.NON_POSITIONAL;
        private DimensionPolicy dimensionPolicy = DimensionPolicy.CROSS_DIMENSION;
        private MembershipMode membershipMode = MembershipMode.MANAGED;
        private VoiceTransmissionPolicy transmissionPolicy = VoiceTransmissionPolicy.EXCLUSIVE;
        private float baseVolume = 1.0F;
        private boolean allowTransmission = true;
        private boolean showSpeakingIndicator = true;
        private int maxMembers = 20;

        public Builder channelMode(VoiceChannelMode channelMode) {
            this.channelMode = channelMode;
            return this;
        }

        public Builder spatialMode(VoiceSpatialMode spatialMode) {
            this.spatialMode = spatialMode;
            return this;
        }

        public Builder dimensionPolicy(DimensionPolicy dimensionPolicy) {
            this.dimensionPolicy = dimensionPolicy;
            return this;
        }

        public Builder membershipMode(MembershipMode membershipMode) {
            this.membershipMode = membershipMode;
            return this;
        }

        public Builder transmissionPolicy(VoiceTransmissionPolicy transmissionPolicy) {
            this.transmissionPolicy = transmissionPolicy;
            return this;
        }

        public Builder baseVolume(float baseVolume) {
            this.baseVolume = baseVolume;
            return this;
        }

        public Builder allowTransmission(boolean allowTransmission) {
            this.allowTransmission = allowTransmission;
            return this;
        }

        public Builder showSpeakingIndicator(boolean showSpeakingIndicator) {
            this.showSpeakingIndicator = showSpeakingIndicator;
            return this;
        }

        public Builder maxMembers(int maxMembers) {
            this.maxMembers = maxMembers;
            return this;
        }

        public VoiceChannelConfig build() {
            return new VoiceChannelConfig(
                    channelMode,
                    spatialMode,
                    dimensionPolicy,
                    membershipMode,
                    transmissionPolicy,
                    baseVolume,
                    allowTransmission,
                    showSpeakingIndicator,
                    maxMembers
            );
        }
    }
}
