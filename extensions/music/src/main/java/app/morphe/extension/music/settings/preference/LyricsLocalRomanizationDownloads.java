/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import java.util.Locale;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.romanizers.CldrTransformsStore;
import app.morphe.extension.music.patches.lyrics.romanizers.JapaneseDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.PinyinDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.RimeDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.RomanizationDict;
import app.morphe.extension.music.patches.lyrics.romanizers.UniDictStore;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;

/** Owns the Local romanization provider's dictionary downloads and status toasts. The Rime word
 * tables are not one of these: they are the Rime Wordlist provider's own download, and that provider
 * fetches what it needs rather than being chosen here. */
public final class LyricsLocalRomanizationDownloads {

    /** The providers, in the order the settings store them. Each is one download and one
     * deletion unit; only {@link #UNIDICT} writes two files, the character tables the Mandarin
     * and the Cantonese word tables both build on, and {@link #CLDR} writes the transliteration
     * rules every script read by rule draws on. */
    public static final int SUDACHI = 0;
    public static final int UNIDICT = 1;
    public static final int PINYIN = 2;
    public static final int CLDR = 3;
    /** Receives progress text while a dialog is open; a closed dialog sees no further updates. */
    public interface ProgressSink {
        void onHint(CharSequence hint);
    }

    private static final Progress[] progress = {new Progress(),
            new Progress(), new Progress(), new Progress()};

    private static boolean started;
    private static boolean failed;
    private static boolean announced;
    private static boolean startAnnounced;

    private static volatile ProgressSink sink;

    private static final class Progress {
        int entries;
        boolean done;
        @Nullable
        String label;
        int percent;
        boolean building;
    }

    private LyricsLocalRomanizationDownloads() {
    }

    /**
     * Which providers the user chose, or, until they chose, the ones the romanization language
     * needs. Automatic covers every script, so it asks for all of them.
     */
    public static boolean[] chosen() {
        final String stored = Settings.LYRICS_LOCAL_DICTIONARIES.get();
        if (stored != null && !stored.isBlank()) {
            final boolean[] picked = new boolean[4];
            for (String token : stored.split(",")) {
                switch (token.trim().toLowerCase(Locale.ROOT)) {
                    // The provider names this build stores.
                    case "sudachi" -> picked[SUDACHI] = true;
                    case "unidict" -> picked[UNIDICT] = true;
                    case "pinyin" -> picked[PINYIN] = true;
                    case "cldr" -> picked[CLDR] = true;
                    // A dev build stored the languages this provider split covers. The Cantonese
                    // word table became the Rime Wordlist provider, which fetches the character
                    // tables it needs itself, so "yue" is no longer a choice here either.
                    case "ja" -> picked[SUDACHI] = true;
                    case "zh" -> {
                        picked[UNIDICT] = true;
                        picked[PINYIN] = true;
                    }
                    default -> {
                    }
                }
            }
            return picked;
        }
        final String subtag = primarySubtag(Settings.LYRICS_ROMANIZE_LANGUAGE.get());
        // Hangul is read by its own algorithm, so Korean needs no dictionary and no rules.
        // The scripts read by rule from the CLDR data keep that one download on: Greek,
        // Cyrillic (BGN), Georgian, Armenian, Hebrew, Arabic (also reads Persian),
        // Ethiopic (also reads Tigrinya) and Syriac.
        return switch (subtag) {
            case "ja" -> new boolean[]{true, false, false, false};
            case "zh" -> new boolean[]{false, true, true, false};
            // Cantonese has a word table of its own, read by the Rime Wordlist provider, so no local
            // dictionary reads it: the Mandarin reading would be another language's.
            case "yue" -> new boolean[]{false, false, false, false};
            // The remaining languages are read by the online provider (or, for Korean,
            // by rule), so none of the local dictionaries are needed.
            case "ko", "th", "km", "as", "bal", "bo", "brx", "ckb", "doi", "dz", "gom",
                    "kk", "ks", "ky", "lo", "mai", "mn", "mni", "ps", "sd", "ug"
                    -> new boolean[]{false, false, false, false};
            case "el", "ru", "be", "uk", "sr", "bg", "mk", "ka", "hy", "he", "iw", "ar", "fa",
                    "am", "ti", "sy" -> new boolean[]{false, false, false, true};
            // A language the user added a word list for is read by the Rime Wordlist provider in the
            // same way, so the local dictionaries have nothing to add there either. Apart from
            // those, an unknown language keeps every reading.
            default -> RimeDictStore.hasLanguage(subtag)
                    ? new boolean[]{false, false, false, false}
                    : new boolean[]{true, true, true, true};
        };
    }

    /** Stores the user's choice. Providers that were on and are now off are deleted, so nothing
     * unused lingers; pinyin keeps UniDict on, since it reads the rest of a line from the
     * character tables. */
    static void saveChoice(boolean sudachi, boolean unidict, boolean pinyin, boolean cldr) {
        if (pinyin) {
            unidict = true;
        }
        final boolean[] before = chosen();
        if (!sudachi && before[SUDACHI]) {
            JapaneseDictStore.delete();
        }
        if (!unidict && before[UNIDICT]) {
            UniDictStore.delete();
        }
        if (!pinyin && before[PINYIN]) {
            PinyinDictStore.delete();
        }
        if (!cldr && before[CLDR]) {
            CldrTransformsStore.delete();
        }
        final StringBuilder value = new StringBuilder();
        if (sudachi) {
            value.append("sudachi");
        }
        if (unidict) {
            if (value.length() > 0) value.append(',');
            value.append("unidict");
        }
        if (pinyin) {
            if (value.length() > 0) value.append(',');
            value.append("pinyin");
        }
        if (cldr) {
            if (value.length() > 0) value.append(',');
            value.append("cldr");
        }
        Settings.LYRICS_LOCAL_DICTIONARIES.save(value.toString());
    }

    /** Starts only the providers the choice needs. */
    public static synchronized void ensure() {
        final boolean[] picked = chosen();
        final boolean needsSudachi = picked[SUDACHI];
        final boolean needsUniDict = picked[UNIDICT];
        final boolean needsPinyin = picked[PINYIN];
        final boolean needsCldr = picked[CLDR];

        if (!needsSudachi && !needsUniDict && !needsPinyin && !needsCldr) {
            return;
        }
        if (!started) {
            if ((!needsSudachi || JapaneseDictStore.isReady())
                    && (!needsUniDict || UniDictStore.isReady())
                    && (!needsPinyin || PinyinDictStore.isComplete())
                    && (!needsCldr || CldrTransformsStore.isReady())) {
                return;
            }
            start(needsSudachi, needsUniDict, needsPinyin, needsCldr);
            return;
        }
        // Reattach in case runtime setup replaced the settings listeners.
        if (needsUniDict) {
            UniDictStore.ensure(listener(UNIDICT));
        }
        if (needsPinyin) {
            PinyinDictStore.ensure(listener(PINYIN));
        }
        if (needsSudachi) {
            JapaneseDictStore.ensure(listener(SUDACHI));
        }
        if (needsCldr) {
            CldrTransformsStore.ensure(listener(CLDR));
        }
    }

    /**
     * Forgets an earlier run, so a download the user asked for again reports its outcome. Without
     * this the announcement latched by a failure would keep the retry silent.
     */
    public static synchronized void resetForUserDownload() {
        failed = false;
        announced = false;
        started = false;
        startAnnounced = false;
        progress[SUDACHI] = new Progress();
        progress[UNIDICT] = new Progress();
        progress[PINYIN] = new Progress();
        progress[CLDR] = new Progress();
    }

    /** Whether every provider the choice needs is ready. */
    public static boolean isReady() {
        final boolean[] picked = chosen();
        if (picked[SUDACHI] && !JapaneseDictStore.isReady()) {
            return false;
        }
        if (picked[UNIDICT] && !UniDictStore.isReady()) {
            return false;
        }
        if (picked[PINYIN] && !PinyinDictStore.isComplete()) {
            return false;
        }
        return !picked[CLDR] || CldrTransformsStore.isReady();
    }

    /** Whether a fetch the user asked for is running and not finished yet. */
    public static synchronized boolean isDownloading() {
        return started && !isReady();
    }

    /** Text a dialog shows while a download runs; null once everything is ready. */
    @Nullable
    public static CharSequence progressHint() {
        final Progress slot = busy();
        if (slot == null || slot.label == null) {
            return null;
        }
        return str(slot.building
                        ? "morphe_music_lyrics_local_romanization_dict_building"
                        : "morphe_music_lyrics_local_romanization_dict_downloading",
                slot.label, slot.percent);
    }

    @Nullable
    private static Progress busy() {
        for (int i = 0; i < progress.length; i++) {
            if (!progress[i].done && progress[i].label != null) {
                return progress[i];
            }
        }
        return null;
    }

    public static void attach(@Nullable ProgressSink target) {
        sink = target;
    }

    public static void detach(@Nullable ProgressSink target) {
        if (sink == target) {
            sink = null;
        }
    }

    private static String primarySubtag(String language) {
        if (language == null || language.isEmpty()
                || "DEFAULT".equalsIgnoreCase(language)) {
            return "";
        }
        final String lower = language.toLowerCase(Locale.ROOT);
        final int dash = lower.indexOf('-');
        return dash >= 0 ? lower.substring(0, dash) : lower;
    }

    private static void start(boolean sudachi, boolean unidict, boolean pinyin, boolean cldr) {
        started = true;
        if (!startAnnounced) {
            startAnnounced = true;
            Utils.runOnMainThread(() -> Utils.showToastShort(
                    str("morphe_music_lyrics_local_romanization_download_started")));
        }
        if (unidict) {
            UniDictStore.ensure(listener(UNIDICT));
        } else {
            progress[UNIDICT].done = true;
        }
        if (pinyin) {
            PinyinDictStore.ensure(listener(PINYIN));
        } else {
            progress[PINYIN].done = true;
        }
        if (sudachi) {
            JapaneseDictStore.ensure(listener(SUDACHI));
        } else {
            progress[SUDACHI].done = true;
        }
        if (cldr) {
            CldrTransformsStore.ensure(listener(CLDR));
        } else {
            progress[CLDR].done = true;
        }
    }

    private static RomanizationDict.Listener listener(int which) {
        final Progress slot = progress[which];
        return new RomanizationDict.Listener() {
            @Override
            public void onDownload(String fileName, int percent) {
                slot.label = fileName;
                slot.percent = percent;
                slot.building = false;
                hint();
            }

            @Override
            public void onBuilding(int percent) {
                slot.percent = percent;
                slot.building = true;
                hint();
            }

            @Override
            public void onReady(int entries) {
                slot.entries = entries;
                slot.done = true;
                hint();
                announce();
            }

            @Override
            public void onFailed() {
                failed = true;
                announce();
            }
        };
    }

    /** Only a dialog that is still open is told about progress; a closed one gets the toast. */
    private static void hint() {
        final ProgressSink target = sink;
        if (target == null) {
            return;
        }
        final CharSequence text = progressHint();
        if (text != null) {
            Utils.runOnMainThread(() -> target.onHint(text));
        }
    }

    private static synchronized void announce() {
        if (announced) {
            return;
        }
        if (failed) {
            announced = true;
            Utils.runOnMainThread(() -> Utils.showToastShort(
                    str("morphe_music_lyrics_local_romanization_dict_failed")));
            return;
        }
        if (!progress[SUDACHI].done || !progress[UNIDICT].done
                || !progress[PINYIN].done || !progress[CLDR].done) {
            return;
        }
        announced = true;
        final int total = progress[SUDACHI].entries + progress[UNIDICT].entries
                + progress[PINYIN].entries + progress[CLDR].entries;
        Utils.runOnMainThread(() -> Utils.showToastShort(
                str("morphe_music_lyrics_local_romanization_dict_ready", total)));
    }
}
