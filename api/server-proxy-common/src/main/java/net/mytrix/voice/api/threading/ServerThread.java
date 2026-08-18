package net.mytrix.voice.api.threading;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that a mutating server API call is expected on the logical server
 * thread. Current Mytrix Voice operations are thread-safe where documented, but
 * server-thread usage keeps integrations deterministic.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.CLASS)
public @interface ServerThread {
}
