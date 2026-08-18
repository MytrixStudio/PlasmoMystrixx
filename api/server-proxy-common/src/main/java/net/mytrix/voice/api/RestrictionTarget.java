package net.mytrix.voice.api;

import java.util.UUID;

public sealed interface RestrictionTarget permits RestrictionTarget.Player, RestrictionTarget.Channel, RestrictionTarget.Session, RestrictionTarget.Global {

    static RestrictionTarget player(UUID playerId) {
        return new Player(playerId);
    }

    static RestrictionTarget channel(VoiceChannelId channelId) {
        return new Channel(channelId);
    }

    static RestrictionTarget session(VoiceSessionId sessionId) {
        return new Session(sessionId);
    }

    static RestrictionTarget global() {
        return new Global();
    }

    record Player(UUID playerId) implements RestrictionTarget {
    }

    record Channel(VoiceChannelId channelId) implements RestrictionTarget {
    }

    record Session(VoiceSessionId sessionId) implements RestrictionTarget {
    }

    record Global() implements RestrictionTarget {
    }
}
