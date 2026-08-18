package net.mytrix.voice.api;

public interface VoicePacketListener {

    VoiceRoutingResult onPacket(VoiceRoutingContext context);
}
