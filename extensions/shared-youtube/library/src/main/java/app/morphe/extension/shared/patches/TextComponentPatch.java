/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3187
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.theme.ThemeColorPatch;
import app.morphe.extension.shared.theme.ThemeUtils;

@SuppressWarnings("unused")
public final class TextComponentPatch {
    /**
     * Injection point.
     * <p>
     * Logs if new litho text layout is used.
     */
    public static boolean useNewLithoTextCreation(boolean useNewLithoTextCreation) {
        // Don't force flag on/off unless debugging patch hooks,
        // because forcing off with newer YT targets causes Shorts player to show no buttons,
        // presumably because the old litho data isn't in the layout data.
        Logger.printDebug(() -> "useNewLithoTextCreation: " + useNewLithoTextCreation);
        return useNewLithoTextCreation;
    }

    public static SpannableString newSpanUsingStylingOfAnotherSpan(Spanned sourceStyle, CharSequence newSpanText) {
        if (sourceStyle == newSpanText && sourceStyle instanceof SpannableString spannable) {
            return setSpanForegroundColor(spannable);
        }

        SpannableString destination = new SpannableString(newSpanText);
        Object[] spans = sourceStyle.getSpans(0, sourceStyle.length(), Object.class);
        for (Object span : spans) {
            destination.setSpan(span, 0, destination.length(), sourceStyle.getSpanFlags(span));
        }

        // Apply theme color.
        setSpanForegroundColor(destination);

        return destination;
    }

    private static SpannableString setSpanForegroundColor(SpannableString span) {
        if (ThemeColorPatch.isPatchIncluded()) {
            // Remove any existing colors
            ForegroundColorSpan[] existing = span.getSpans(0, span.length(), ForegroundColorSpan.class);
            for (ForegroundColorSpan fcs : existing) {
                span.removeSpan(fcs);
            }

            span.setSpan(
                    new ForegroundColorSpan(ThemeUtils.getAppForegroundColor()),
                    0,
                    span.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        return span;
    }
}
