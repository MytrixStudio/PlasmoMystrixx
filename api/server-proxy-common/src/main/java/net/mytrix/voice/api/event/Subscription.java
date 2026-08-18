package net.mytrix.voice.api.event;

/**
 * Removable event subscription.
 */
public interface Subscription extends AutoCloseable {

    boolean active();

    @Override
    void close();
}
