/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4080
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.niconicocomments;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Main-thread image load states; worker results are delivered through View.post(). */
final class EmojiImageCache<T> {
    static final int MAX_ENTRIES = 128;
    static final int MAX_QUEUED_LOADS = 32;

    private final Map<String, Entry<T>> entries = new LinkedHashMap<>(16, 0.75f, true);

    static ThreadPoolExecutor createExecutor() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_LOADS), runnable -> {
                    Thread thread = new Thread(runnable, "NiconicoEmoji");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    Entry<T> get(String url) {
        return entries.get(url);
    }

    Entry<T> beginLoad(String url, Set<String> neededUrls) {
        Entry<T> existing = entries.get(url);
        if (existing != null) return existing;

        if (entries.size() >= MAX_ENTRIES) {
            Iterator<Map.Entry<String, Entry<T>>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Entry<T>> candidate = iterator.next();
                if (!candidate.getValue().loading && !neededUrls.contains(candidate.getKey())) {
                    iterator.remove();
                    break;
                }
            }
            if (entries.size() >= MAX_ENTRIES) return null;
        }
        Entry<T> entry = new Entry<>();
        entries.put(url, entry);
        return entry;
    }

    boolean complete(String url, Entry<T> entry, T value) {
        if (entries.get(url) != entry) return false;
        entry.value = value;
        entry.failed = value == null;
        entry.loading = false;
        return true;
    }

    void discard(String url, Entry<T> entry) {
        if (entries.get(url) == entry) entries.remove(url);
    }

    void clear() {
        entries.clear();
    }

    static final class Entry<T> {
        T value;
        boolean loading = true;
        boolean failed;
    }
}
