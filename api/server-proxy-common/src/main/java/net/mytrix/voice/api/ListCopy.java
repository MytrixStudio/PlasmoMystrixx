package net.mytrix.voice.api;

import java.util.Collection;
import java.util.List;

final class ListCopy {

    private ListCopy() {
    }

    static <T> List<T> copy(Collection<T> collection) {
        return collection == null ? List.of() : List.copyOf(collection);
    }
}
