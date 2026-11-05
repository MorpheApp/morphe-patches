/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.spans;

import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;

import app.morphe.extension.shared.patches.spans.SpanFilter;
import app.morphe.extension.shared.patches.spans.SpanType;
import app.morphe.extension.shared.patches.spans.StringSpanFilterGroup;
import app.morphe.extension.youtube.patches.SnackbarPatch;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Colors the text of a Litho snackbar to match its custom background.
 */
@SuppressWarnings("unused")
public final class SnackbarFilter extends SpanFilter {

    public SnackbarFilter() {
        addCallbacks(
                new StringSpanFilterGroup(
                        Settings.CUSTOM_SNACKBAR_THEME,
                        "snackbar.eml"
                )
        );
    }

    @Override
    public boolean skip(String identifier, CharSequence path, SpannableString spannableString, Object span,
                        int start, int end, int flags, boolean isWord, SpanType spanType, StringSpanFilterGroup matchedGroup) {
        if (spanType != SpanType.FOREGROUND_COLOR) {
            return false;
        }

        Integer textColor = SnackbarPatch.getTextColor();
        if (textColor == null) {
            return false;
        }

        spannableString.setSpan(new ForegroundColorSpan(textColor), start, end, flags);
        return true;
    }
}
