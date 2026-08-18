package net.mytrix.voice.api.server;

import net.mytrix.voice.api.ApiVersion;
import net.mytrix.voice.api.VoiceApiState;
import net.mytrix.voice.api.VoiceCapability;
import net.mytrix.voice.api.channel.VoiceChannelRegistry;
import net.mytrix.voice.api.event.VoiceEventBus;
import net.mytrix.voice.api.player.VoicePlayerService;
import net.mytrix.voice.api.transmission.VoiceTransmissionService;

/**
 * Public server-side API exposed by Mytrix Voice.
 */
public interface ServerVoiceApi {

    ApiVersion version();

    boolean supports(VoiceCapability capability);

    VoiceApiState state();

    VoiceChannelRegistry channels();

    VoicePlayerService players();

    VoiceTransmissionService transmissions();

    VoiceEventBus events();
}
