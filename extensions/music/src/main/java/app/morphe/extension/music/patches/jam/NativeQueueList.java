/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.jam;

import androidx.annotation.NonNull;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

/** Patched to implement YTM's observable-list ABI. Items are genuine native queue models. */
public final class NativeQueueList extends AbstractList<Object> {

    private final List<Object> items = new ArrayList<>();

    public synchronized Object get(int index) {
        return items.get(index);
    }

    public synchronized int size() {
        return items.size();
    }

    @NonNull
    public synchronized List<Object> subList(int from, int to) {
        return new ArrayList<>(items.subList(from, to));
    }

    public synchronized void replace(List<Object> next) {
        items.clear();
        items.addAll(next);
    }

    @Override
    public Object remove(int index) {
        Object item = get(index);
        JamMirror.remove(item);
        return item;
    }

    public void move(int from, int to) {
        JamMirror.move(this, from, to);
    }

    // JamMirror refreshes YTM's outer display list after replacing these items or
    // applying an optimistic edit. That native list notifies its own listeners;
    // this backing list must not send a second notification for the same update.
    public void addListener(@SuppressWarnings("unused") Object listener) {}

    @SuppressWarnings("unused")
    public void removeListener(Object listener) {}
}
