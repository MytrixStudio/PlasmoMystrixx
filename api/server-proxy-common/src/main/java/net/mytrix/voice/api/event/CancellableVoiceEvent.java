package net.mytrix.voice.api.event;

/**
 * Base contract for public events that can be cancelled before an operation is
 * committed.
 */
public interface CancellableVoiceEvent extends VoiceEvent {

    boolean cancelled();

    void cancel();
}
