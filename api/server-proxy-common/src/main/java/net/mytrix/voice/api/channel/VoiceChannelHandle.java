package net.mytrix.voice.api.channel;

import java.util.Set;
import java.util.UUID;

/**
 * Non-forgeable handle returned to the owner that registered a channel.
 *
 * <p>Closing a handle is idempotent. After close, mutating operations return
 * a closed-channel result and do not resurrect the channel.</p>
 */
public interface VoiceChannelHandle extends ManagedVoiceChannel, AutoCloseable {

    VoiceChannelView view();

    UpdateResult updateConfig(VoiceChannelConfig config);

    MembershipResult refreshMembership();

    @Override
    void close();

    boolean closed();

    @Override
    Set<UUID> members();
}
