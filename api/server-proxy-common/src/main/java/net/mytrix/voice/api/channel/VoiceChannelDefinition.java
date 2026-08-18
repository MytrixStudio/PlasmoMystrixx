package net.mytrix.voice.api.channel;

import java.util.Objects;

/**
 * Immutable definition used to register a public voice channel.
 */
public record VoiceChannelDefinition(
        VoiceChannelId id,
        String displayName,
        VoiceChannelConfig config,
        VoiceChannelOwner owner,
        VoiceChannelPermission permission,
        VoiceChannelMembershipProvider membershipProvider
) {

    public VoiceChannelDefinition {
        Objects.requireNonNull(id, "id");
        displayName = displayName == null || displayName.isBlank() ? id.toString() : displayName.trim();
        if (displayName.length() > 64) {
            throw new IllegalArgumentException("displayName is too long");
        }
        config = config == null ? VoiceChannelConfig.distanceFreeGroup() : config;
        owner = owner == null ? new VoiceChannelOwner(id.namespace()) : owner;
        if (!owner.modId().equals(id.namespace())) {
            throw new IllegalArgumentException("Channel namespace must match owner mod id");
        }
        permission = permission == null ? VoiceChannelPermission.allowAll() : permission;
        membershipProvider = membershipProvider == null ? VoiceChannelMembershipProvider.empty() : membershipProvider;
    }

    public VoiceChannelContext context() {
        return new VoiceChannelContext(id, displayName, config);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private VoiceChannelId id;
        private String displayName;
        private VoiceChannelConfig config = VoiceChannelConfig.distanceFreeGroup();
        private VoiceChannelOwner owner;
        private VoiceChannelPermission permission = VoiceChannelPermission.allowAll();
        private VoiceChannelMembershipProvider membershipProvider = VoiceChannelMembershipProvider.empty();

        public Builder id(VoiceChannelId id) {
            this.id = id;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder config(VoiceChannelConfig config) {
            this.config = config;
            return this;
        }

        public Builder owner(VoiceChannelOwner owner) {
            this.owner = owner;
            return this;
        }

        public Builder permission(VoiceChannelPermission permission) {
            this.permission = permission;
            return this;
        }

        public Builder membershipProvider(VoiceChannelMembershipProvider membershipProvider) {
            this.membershipProvider = membershipProvider;
            return this;
        }

        public VoiceChannelDefinition build() {
            return new VoiceChannelDefinition(id, displayName, config, owner, permission, membershipProvider);
        }
    }
}
