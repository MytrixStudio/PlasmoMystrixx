package net.mytrix.voice.api;

import java.util.Collection;
import java.util.UUID;

public record VoiceRoutingContext(
        UUID speakerId,
        VoicePacket packet,
        Collection<VoiceChannelSnapshot> memberships,
        Collection<VoiceRestrictionSnapshot> restrictions
) {

    public VoiceRoutingContext {
        memberships = ListCopy.copy(memberships);
        restrictions = ListCopy.copy(restrictions);
    }
}
