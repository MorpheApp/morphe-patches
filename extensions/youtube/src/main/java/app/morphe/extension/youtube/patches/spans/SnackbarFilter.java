/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.spans;

import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.spans.SpanFilter;
import app.morphe.extension.shared.patches.spans.SpanType;
import app.morphe.extension.shared.patches.spans.StringSpanFilterGroup;
import app.morphe.extension.youtube.patches.SnackbarPatch;

@SuppressWarnings({"unused", "FieldCanBeLocal"})
public final class SnackbarFilter extends SpanFilter {

    public SnackbarFilter() {
        addCallbacks(
                new StringSpanFilterGroup(
                        null,
                        "snackbar."
                )
        );
    }

    @Override
    public boolean skip(String identifier, CharSequence path, SpannableString spannableString, Object span,
                        int start, int end, int flags, boolean isWord, SpanType spanType, StringSpanFilterGroup matchedGroup) {
        try {
            if (spanType == SpanType.FOREGROUND_COLOR && span instanceof ForegroundColorSpan) {
                Integer targetColor = SnackbarPatch.getTextColorForSpans();
                if (targetColor != null) {
                    spannableString.setSpan(
                            new ForegroundColorSpan(targetColor),
                            start,
                            end,
                            flags
                    );
                    return true;
                }
            }
        } catch (Exception e) {
            Logger.printException(() -> "skip failure", e);
        }
        return false;
    }
}
