package net.mytrix.voice.api;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record VoiceRoutingResult(
        boolean cancelDefaultRoute,
        Set<UUID> recipients,
        boolean discardPacket,
        Optional<VoiceChannelId> selectedChannel
) {

    public VoiceRoutingResult {
        recipients = Set.copyOf(recipients == null ? Set.of() : recipients);
        selectedChannel = selectedChannel == null ? Optional.empty() : selectedChannel;
    }

    public static VoiceRoutingResult defaultRoute() {
        return new VoiceRoutingResult(false, Set.of(), false, Optional.empty());
    }

    public static VoiceRoutingResult discard(boolean cancelDefaultRoute, Optional<VoiceChannelId> selectedChannel) {
        return new VoiceRoutingResult(cancelDefaultRoute, Set.of(), true, selectedChannel);
    }
}
