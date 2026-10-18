/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.SwitchPreference;
import android.util.AttributeSet;

import app.morphe.extension.music.patches.lyrics.romanizers.JapaneseDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.PinyinDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.RomanizationDict;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;

/**
 * The switch that turns on reading the lyrics on the device, and the row that fetches what it
 * needs. A dictionary is only asked for here, when this row is built or the switch turned on,
 * and a switch left on over a cache the system emptied is turned off.
 */
public class LyricsLocalRomanizationPreference extends SwitchPreference {

    private final Progress[] progress = {new Progress(), new Progress()};

    private boolean failed;

    private static final class Progress {
        String label;
        int percent;
        int entries;
        boolean building;
        boolean done;
    }

    public LyricsLocalRomanizationPreference(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public LyricsLocalRomanizationPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public LyricsLocalRomanizationPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LyricsLocalRomanizationPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        if (!Settings.LYRICS_LOCAL_ROMANIZATION.get()) return;
        if (PinyinDictStore.isReady() && JapaneseDictStore.isReady()) {
            start();
        } else {
            Settings.LYRICS_LOCAL_ROMANIZATION.save(false);
        }
    }

    @Override
    protected boolean callChangeListener(Object newValue) {
        if (Boolean.TRUE.equals(newValue)) {
            start();
        }
        return super.callChangeListener(newValue);
    }

    private void start() {
        failed = false;
        PinyinDictStore.ensure(listener(0));
        JapaneseDictStore.ensure(listener(1));
    }

    private RomanizationDict.Listener listener(final int which) {
        final Progress slot = progress[which];
        return new RomanizationDict.Listener() {
            @Override
            public void onDownload(String fileName, int percent) {
                slot.label = fileName;
                slot.percent = percent;
                slot.building = false;
                updateSummary();
            }

            @Override
            public void onBuilding(int percent) {
                slot.percent = percent;
                slot.building = true;
                updateSummary();
            }

            @Override
            public void onReady(int entries) {
                slot.entries = entries;
                slot.done = true;
                updateSummary();
            }

            @Override
            public void onFailed() {
                failed = true;
                updateSummary();
            }
        };
    }

    private void updateSummary() {
        Utils.runOnMainThread(() -> setSummary(summary()));
    }

    private CharSequence summary() {
        if (failed) {
            return str("morphe_music_lyrics_local_romanization_dict_failed");
        }
        if (progress[0].done && progress[1].done) {
            return str("morphe_music_lyrics_local_romanization_dict_ready",
                    progress[0].entries + progress[1].entries);
        }
        for (Progress slot : progress) {
            if (slot.done || slot.label == null) continue;
            return str(slot.building
                            ? "morphe_music_lyrics_local_romanization_dict_building"
                            : "morphe_music_lyrics_local_romanization_dict_downloading",
                    slot.label, slot.percent);
        }
        return str("morphe_music_lyrics_local_romanization_summary");
    }
}
