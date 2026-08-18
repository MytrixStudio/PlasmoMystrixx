package net.mytrix.voice.api;

public record VoiceChannelPolicy(
        RoutingMode routingMode,
        boolean exclusive,
        boolean allowSelfMonitoring,
        boolean fallbackToProximity,
        int priority,
        double maximumDistance,
        boolean spatialAudio
) {

    public VoiceChannelPolicy {
        if (routingMode == null) routingMode = RoutingMode.PROXIMITY;
    }

    public static VoiceChannelPolicy proximity() {
        return new VoiceChannelPolicy(RoutingMode.PROXIMITY, false, false, true, 0, 0D, true);
    }

    public static VoiceChannelPolicy privateChannel(int priority) {
        return new VoiceChannelPolicy(RoutingMode.PRIVATE_CHANNEL, true, false, false, priority, 0D, false);
    }
}
