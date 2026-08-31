/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.translation.TextTranslator;

/**
 * Translates lyrics line by line into the device language.
 */
public final class LyricsTranslator {

    public interface Callback {
        /**
         * Called on the main thread with one translated line per original line,
         * or {@code null} if the translation failed.
         */
        void onTranslated(@Nullable List<String> translatedLines);
    }

    /** Separate from the lyrics executor, so a translation never delays a lyrics lookup. */
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private LyricsTranslator() {
    }

    public static String deviceLanguage() {
        return Locale.getDefault().getLanguage();
    }

    /**
     * Translates the lyrics of a track, using the cache when possible.
     */
    public static void translate(TrackInfo track, Lyrics lyrics, LyricsSource source, Callback callback) {
        Utils.verifyOnMainThread();

        List<String> lines = new ArrayList<>(lyrics.lines().size());
        for (LyricsLine line : lyrics.lines()) {
            lines.add(line.text());
        }

        final String language = deviceLanguage();

        executor.execute(() -> {
            List<String> translated = LyricsCache.getTranslation(track, source, language, lines.size());
            if (translated == null) {
                translated = translateOnline(lines, language);
                if (translated != null) {
                    LyricsCache.putTranslation(track, source, language, translated);
                }
            }

            final List<String> result = translated;
            Utils.runOnMainThread(() -> callback.onTranslated(result));
        });
    }

    /**
     * @return One line per input line, or {@code null} if any batch failed or came
     * back with a different number of lines than it was given.
     */
    @Nullable
    private static List<String> translateOnline(List<String> lines, String language) {
        if (!Utils.isNetworkConnected()) {
            return null;
        }

        // Blank lines are instrumental breaks. They are held back because the endpoint
        // collapses empty lines between the newlines it is given, which would shift
        // every following translation onto the wrong lyrics line.
        List<String> toTranslate = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (!line.isEmpty()) {
                toTranslate.add(line);
            }
        }

        List<String> translated = new ArrayList<>(toTranslate.size());
        try {
            for (List<String> batch : TextTranslator.splitByCharacterBudget(
                    toTranslate, TextTranslator.MAXIMUM_BATCH_CHARACTERS)) {
                List<String> translatedBatch = TextTranslator.translate(batch, language);

                // A mismatched count cannot be mapped back safely, and showing lines
                // under the wrong lyrics is worse than showing no translation at all.
                if (translatedBatch.size() != batch.size()) {
                    Logger.printDebug(() -> "Discarding translation: expected " + batch.size()
                            + " lines but got " + translatedBatch.size());
                    return null;
                }
                translated.addAll(translatedBatch);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not translate the lyrics", ex);
            return null;
        }

        List<String> result = new ArrayList<>(lines.size());
        int next = 0;
        for (String line : lines) {
            result.add(line.isEmpty() ? "" : translated.get(next++));
        }
        return result;
    }
}
