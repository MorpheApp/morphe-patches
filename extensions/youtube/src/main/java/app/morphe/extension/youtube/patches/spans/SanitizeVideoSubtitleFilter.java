/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.spans;

import android.text.SpannableString;

import app.morphe.extension.shared.patches.spans.SpanFilter;
import app.morphe.extension.shared.patches.spans.SpanType;
import app.morphe.extension.shared.patches.spans.StringSpanFilterGroup;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings({"unused", "FieldCanBeLocal"})
public final class SanitizeVideoSubtitleFilter extends SpanFilter {

    public SanitizeVideoSubtitleFilter() {
        addCallbacks(
                new StringSpanFilterGroup(
                        Settings.SANITIZE_VIDEO_SUBTITLE,
                        "|video_subtitle."
                )
        );
    }

    @Override
    public boolean skip(String identifier, CharSequence path, SpannableString spannableString, Object span,
                        int start, int end, int flags, boolean isWord, SpanType spanType, StringSpanFilterGroup matchedGroup) {
        if (isWord) {
            if (spanType == SpanType.IMAGE) {
                hideImageSpan(spannableString, start, end, flags);
                return true;
            } else if (spanType == SpanType.CUSTOM_CHARACTER_STYLE) {
                hideSpan(spannableString, start, end, flags);
                return true;
            }
        }
        return false;
    }
}
