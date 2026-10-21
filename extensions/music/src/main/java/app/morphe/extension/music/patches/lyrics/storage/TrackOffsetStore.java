/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.storage;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.shared.Utils;

import java.util.ArrayList;
import java.util.List;

/**
 * The timing offset the user dragged out for one track, kept so a loop, a trip to another song
 * and a restart all find it again.
 *
 * <p>Keyed by the track alone: a different provider or a refreshed cache must not silently drop a
 * value the user dialled in, and one that no longer fits is corrected by dragging as before.
 *
 * <p>Kept apart from the lyrics on purpose: those are trimmed to a fixed number of entries, and an
 * offset the user dialled in should not be one of the ones that goes.
 */
public final class TrackOffsetStore {

    private static final String PREFERENCES_NAME = "morphe_lyrics_offsets";

    /** SharedPreferences never evicts, so entries are capped and the oldest go first. */
    private static final int MAX_ENTRIES = 1000;

    /** Insertion order, newest last. */
    private static final String KEY_ORDER = "__order";
    private static final String SEPARATOR = ",";

    private TrackOffsetStore() {
    }

    /** Safe on the main thread: only the first read of the file touches disk. */
    public static int get(@NonNull TrackInfo track) {
        SharedPreferences preferences = preferences();
        if (preferences == null) {
            return 0;
        }
        return preferences.getInt(key(track), 0);
    }

    /** A 0 drops the entry, so a track dragged back to square stops holding a slot. */
    public static void put(@NonNull TrackInfo track, int ms) {
        SharedPreferences preferences = preferences();
        if (preferences == null) {
            return;
        }
        final String key = key(track);
        SharedPreferences.Editor editor = preferences.edit();
        if (ms == 0) {
            editor.remove(key);
        } else {
            editor.putInt(key, ms);
        }
        // Eviction shares the editor, so it cannot outlive the entry it belongs to.
        List<String> order = order(preferences, key, ms != 0);
        for (String oldest : evicted(order)) {
            editor.remove(oldest);
        }
        editor.putString(KEY_ORDER, join(order));
        editor.apply();
    }

    private static List<String> order(SharedPreferences preferences, String key, boolean present) {
        List<String> order = new ArrayList<>();
        final String stored = preferences.getString(KEY_ORDER, null);
        if (stored != null) {
            for (String entry : stored.split(SEPARATOR)) {
                if (!entry.isEmpty() && !entry.equals(key)) {
                    order.add(entry);
                }
            }
        }
        if (present) {
            order.add(key);
        }
        return order;
    }

    private static List<String> evicted(List<String> order) {
        List<String> evicted = new ArrayList<>();
        while (order.size() > MAX_ENTRIES) {
            evicted.add(order.remove(0));
        }
        return evicted;
    }

    private static String join(List<String> order) {
        StringBuilder result = new StringBuilder();
        for (String entry : order) {
            if (result.length() > 0) {
                result.append(SEPARATOR);
            }
            result.append(entry);
        }
        return result.toString();
    }

    private static String key(@NonNull TrackInfo track) {
        return Integer.toHexString(track.cacheKey().hashCode());
    }

    @Nullable
    private static SharedPreferences preferences() {
        try {
            Context context = Utils.getContext();
            return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, 0);
        } catch (Exception ex) {
            return null;
        }
    }
}