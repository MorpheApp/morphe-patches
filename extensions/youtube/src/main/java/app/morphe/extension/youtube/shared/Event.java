/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;

/**
 * generic event provider class
 */
public final class Event<T> {
    private final Set<Consumer<T>> eventListeners = Collections.synchronizedSet(new LinkedHashSet<>());

    public void addObserver(Consumer<T> observer) {
        Logger.printDebug(() -> "Adding observer: " + observer);
        eventListeners.add(observer);
    }

    public void removeObserver(Consumer<T> observer) {
        eventListeners.remove(observer);
    }

    public void invoke(T value) {
        // Observers can remove themselves or add other observers while they are notified,
        // which would break the iteration of the set.
        final List<Consumer<T>> observers;
        synchronized (eventListeners) {
            observers = new ArrayList<>(eventListeners);
        }
        for (Consumer<T> observer : observers) {
            observer.accept(value);
        }
    }
}
