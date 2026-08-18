package net.mytrix.voice.api.channel;

/**
 * Server-side permission callbacks for one public voice channel.
 *
 * <p>Callbacks must be fast, non-blocking, and must not perform network or
 * database work in the audio hot path. Throwing an exception causes the action
 * to be rejected safely by the runtime boundary.</p>
 */
public interface VoiceChannelPermission {

    VoiceChannelPermission ALLOW_ALL = new VoiceChannelPermission() {
    };

    default boolean canJoin(VoicePlayerContext player, VoiceChannelContext channel) {
        return true;
    }

    default boolean canSpeak(VoicePlayerContext player, VoiceChannelContext channel) {
        return true;
    }

    default boolean canListen(VoicePlayerContext listener, VoicePlayerContext speaker, VoiceChannelContext channel) {
        return true;
    }

    static VoiceChannelPermission allowAll() {
        return ALLOW_ALL;
    }
}
