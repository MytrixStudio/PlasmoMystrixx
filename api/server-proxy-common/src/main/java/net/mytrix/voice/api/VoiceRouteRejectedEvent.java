package net.mytrix.voice.api;

import java.time.Instant;

public record VoiceRouteRejectedEvent(VoiceRoutingContext context, String reason, Instant createdAt) implements DynamicVoiceEvent {
}
