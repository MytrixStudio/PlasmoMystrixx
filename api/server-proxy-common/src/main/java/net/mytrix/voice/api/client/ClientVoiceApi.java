package net.mytrix.voice.api.client;

import net.mytrix.voice.api.ApiVersion;
import net.mytrix.voice.api.VoiceApiState;
import net.mytrix.voice.api.VoiceCapability;
import net.mytrix.voice.api.event.VoiceEventBus;

/**
 * Public client-side API surface. It intentionally exposes controls and state,
 * not microphone buffers, encoders, OpenAL sources, or raw audio.
 */
public interface ClientVoiceApi {

    ApiVersion version();

    boolean supports(VoiceCapability capability);

    VoiceApiState state();

    ClientVoiceControl controls();

    VoiceEventBus events();
}
