package net.mytrix.voice.api.event;

/**
 * Listener callback for one public voice event type.
 *
 * @param <E> event type
 */
@FunctionalInterface
public interface VoiceEventListener<E extends VoiceEvent> {

    void onEvent(E event);
}
