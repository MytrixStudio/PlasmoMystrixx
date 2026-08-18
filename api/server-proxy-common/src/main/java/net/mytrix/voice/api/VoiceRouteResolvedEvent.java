package net.mytrix.voice.api;

import java.time.Instant;

public record VoiceRouteResolvedEvent(VoiceRoutingContext context, VoiceRoutingResult result, Instant createdAt) implements DynamicVoiceEvent {
}
