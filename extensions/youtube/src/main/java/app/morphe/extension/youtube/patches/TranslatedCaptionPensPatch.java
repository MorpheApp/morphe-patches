/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3837
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public class TranslatedCaptionPensPatch {

    /**
     * Pen id that is never declared by a timed text document.
     * Looking it up returns the default pen, which uses the user caption style.
     */
    private static final int DEFAULT_PEN_ID = Integer.MIN_VALUE;

    private static volatile boolean isAutoTranslatedTrack;

    /**
     * Injection point.
     */
    public static void setTimedTextUrl(@Nullable String url) {
        if (url == null || !url.contains("api/timedtext")) {
            return;
        }

        final boolean translated = url.contains("&tlang=") || url.contains("?tlang=");
        if (isAutoTranslatedTrack != translated) {
            Logger.printDebug(() -> "Auto-translated caption track: " + translated);
        }
        isAutoTranslatedTrack = translated;
    }

    /**
     * Injection point.
     *
     * Auto-translated tracks keep the pen ids of the original track, but the translated text
     * does not line up with the original styled segments. Channels that use an invisible pen
     * for padding characters can end up with the entire translated line using that pen,
     * which shows only the caption background.
     *
     * @param penId Pen id of a timed text element.
     * @return The pen id to use.
     */
    public static int getPenId(int penId) {
        return isAutoTranslatedTrack
                ? DEFAULT_PEN_ID
                : penId;
    }
}
