package net.mytrix.voice.api;

import net.mytrix.voice.api.client.ClientVoiceApi;
import net.mytrix.voice.api.server.ServerVoiceApi;

import java.util.Optional;

/**
 * Stable entry point for integrations.
 *
 * <p>Consumers must not instantiate API implementations manually. The voice
 * runtime registers the server/client services during its own lifecycle.
 * Optional integrations should keep all references to this class inside a
 * loader-gated integration class so a missing voice mod does not cause
 * {@code NoClassDefFoundError} before {@link Optional} can be returned.</p>
 */
public final class VoiceChatApi {

    private VoiceChatApi() {
    }

    /**
     * Finds the server-side API for the current voice runtime.
     *
     * @return the server API when Mytrix Voice is installed and registered
     */
    public static Optional<ServerVoiceApi> server() {
        return MytrixVoiceServices.find(ServerVoiceApi.class);
    }

    /**
     * Finds the client-side API for the current voice runtime.
     *
     * @return the client API when running on a client with Mytrix Voice active
     */
    public static Optional<ClientVoiceApi> client() {
        return MytrixVoiceServices.find(ClientVoiceApi.class);
    }
}
