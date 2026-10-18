/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.spans;

import android.graphics.Color;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.spans.SpanFilter;
import app.morphe.extension.shared.patches.spans.SpanType;
import app.morphe.extension.shared.patches.spans.StringSpanFilterGroup;
import app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch;

/**
 * Replaces the dark theme text colors of the video metadata panel with the light theme ones,
 * when the elevated player is turned off ({@link LegacyPlayerControlsPatch#fixMetapanelTextColors()}).
 * <p>
 * The colors are replaced when the spans are created,
 * because some texts, such as the like count, are animated and created again from the spans.
 */
@SuppressWarnings("unused")
public final class MetapanelTextColorFilter extends SpanFilter {

    private static final String[] METAPANEL_IDENTIFIERS = {
            "video_metadata.e",
            "video_metadata_carousel.e",
            "compactify_video_action_bar.e"
    };

    public MetapanelTextColorFilter() {
        addCallbacks(
                new StringSpanFilterGroup(
                        null,
                        METAPANEL_IDENTIFIERS
                )
        );
    }

    @Override
    public boolean skip(String identifier, CharSequence path, SpannableString spannableString, Object span,
                        int start, int end, int flags, boolean isWord, SpanType spanType, StringSpanFilterGroup matchedGroup) {
        if (spanType != SpanType.FOREGROUND_COLOR || !isMetapanelText(identifier, path)
                || !LegacyPlayerControlsPatch.fixMetapanelTextColors()) {
            return false;
        }

        final int color = ((ForegroundColorSpan) span).getForegroundColor();
        if (!isLightColor(color) || isBlank(spannableString, start, end)) {
            return false;
        }

        spannableString.setSpan(new ForegroundColorSpan(getLightThemeTextColor(color)), start, end, flags);
        return true;
    }

    private static boolean isMetapanelText(String identifier, CharSequence path) {
        // The subscribe button has its own background, with the white text of the light theme.
        return isMetapanelIdentifier(identifier) && Utils.indexOf(path, "compact_channel_bar") < 0;
    }

    /**
     * @return If the identifier is of the video metadata panel.
     */
    public static boolean isMetapanelIdentifier(@Nullable String identifier) {
        if (identifier == null) {
            return false;
        }

        for (String metapanelIdentifier : METAPANEL_IDENTIFIERS) {
            if (identifier.startsWith(metapanelIdentifier)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return If the span has no visible text, such as the light separator of the light theme.
     */
    private static boolean isBlank(CharSequence text, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int getBrightness(int color) {
        return (299 * Color.red(color) + 587 * Color.green(color) + 114 * Color.blue(color)) / 1000;
    }

    public static boolean isLightColor(int color) {
        return getBrightness(color) > 127;
    }

    /**
     * @return The light theme text color for a dark theme text color
     *         (#f1f1f1 primary and #aaaaaa secondary).
     */
    public static int getLightThemeTextColor(int color) {
        final int rgb = getBrightness(color) >= 204
                ? 0x0F0F0F  // Primary text.
                : 0x606060; // Secondary text.
        return (color & 0xFF000000) | rgb;
    }
}
