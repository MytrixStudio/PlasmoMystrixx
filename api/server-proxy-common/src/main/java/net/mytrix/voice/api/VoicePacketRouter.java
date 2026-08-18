package net.mytrix.voice.api;

public interface VoicePacketRouter {

    VoiceRoutingResult route(VoiceRoutingContext context);
}
