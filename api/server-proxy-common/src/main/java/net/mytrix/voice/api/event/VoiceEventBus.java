package net.mytrix.voice.api.event;

/**
 * Public event bus with explicit unsubscribe support.
 */
public interface VoiceEventBus {

    <E extends VoiceEvent> Subscription subscribe(Class<E> eventType, VoiceEventListener<E> listener);
}
