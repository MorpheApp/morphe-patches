/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3851
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.WindowManager;

import java.util.Locale;

import app.morphe.extension.music.patches.lyrics.storage.LyricsCache;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * The stored lyrics are in the app's own files, so a cache clear no longer reclaims them and this
 * is the only way to. Clearing app data would take the reading dictionaries with it.
 */
@SuppressWarnings({"unused", "deprecation"})
public class LyricsClearCachePreference extends Preference {

    public LyricsClearCachePreference(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public LyricsClearCachePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public LyricsClearCachePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LyricsClearCachePreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        // Nothing is stored here, so a persisted value would only leave a key nothing reads.
        setPersistent(false);
        // The tap is the whole feature and the row is declared non-selectable, so this is what
        // lets it through.
        setSelectable(true);
        refreshSummary();
    }

    /** The size is measured off the main thread; the settings page reopens often enough that
     * a stale summary never shows for long. */
    private void refreshSummary() {
        Utils.runOnBackgroundThread(() -> {
            final String size = megabytes(LyricsCache.size());
            Utils.runOnMainThread(() -> setSummary(str(
                    "morphe_music_lyrics_clear_cache_summary", size)));
        });
    }

    @Override
    protected void onClick() {
        if (!isEnabled()) {
            return;
        }
        // Thousands of files can be stored, so the size is measured off the main thread.
        Utils.runOnBackgroundThread(() -> {
            final String size = megabytes(LyricsCache.size());
            Utils.runOnMainThread(() -> {
                try {
                    CustomDialog.create(getContext(),
                            str("morphe_music_lyrics_clear_cache_title"),
                            str("morphe_music_lyrics_clear_cache_summary", size),
                            null,
                            null,
                            LyricsClearCachePreference.this::clear,
                            () -> { },
                            null,
                            null,
                            true).first.show();
                } catch (WindowManager.BadTokenException ex) {
                    Logger.printException(() -> "Could not show the clear cache dialog", ex);
                }
            });
        });
    }

    /** Thousands of files can be stored, so they are deleted off the main thread. */
    private void clear() {
        Utils.runOnBackgroundThread(() -> {
            final String freed = megabytes(LyricsCache.clear());
            Utils.runOnMainThread(() -> {
                Utils.showToastShort(str("morphe_music_lyrics_clear_cache_done", freed));
                refreshSummary();
            });
        });
    }

    /** A byte count too long to read at a glance. */
    private static String megabytes(long bytes) {
        return String.format(Locale.getDefault(), "%.1f", bytes / (1024.0 * 1024.0));
    }
}
