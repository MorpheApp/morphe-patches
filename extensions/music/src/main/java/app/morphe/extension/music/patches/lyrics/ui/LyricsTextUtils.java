/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.text.Spanned;
import android.text.style.RelativeSizeSpan;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.model.LyricsBracketSplitter;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;

/**
 * Text-level helpers shared by the span builder and the views: stripping invisible characters,
 * telling a background vocal from a lead line, and walking past line breaks.
 */
final class LyricsTextUtils {

    private LyricsTextUtils() {
    }

    static boolean isSmallAt(CharSequence text, int offset) {
        if (!(text instanceof Spanned spanned)) {
            return false;
        }
        final int window = Math.min(offset + 1, text.length());
        RelativeSizeSpan[] spans = spanned.getSpans(offset, window, RelativeSizeSpan.class);
        for (RelativeSizeSpan span : spans) {
            final int spanStart = spanned.getSpanStart(span);
            final int spanEnd = spanned.getSpanEnd(span);
            if (spanStart <= offset && offset < spanEnd) {
                return true;
            }
        }
        return false;
    }

    /** Advance past line breaks to find the next visible character run. */
    static int skipNewlines(CharSequence text, int offset) {
        final int length = text.length();
        int at = offset;
        while (at < length && (text.charAt(at) == '\n' || text.charAt(at) == '\r')) {
            at++;
        }
        return at;
    }

    /** A background vocal, whether the source says so or only writes it in brackets. */
    static boolean isSecondaryLine(LyricsLine line) {
        return line.isBG() || LyricsBracketSplitter.isBracketedVocal(line.text());
    }

    /** The text a line is drawn with: a background vocal sits on a line of its own, so it loses its brackets. */
    static String displayLineText(LyricsLine line) {
        String raw = line.text();
        return isSecondaryLine(line) ? LyricsBracketSplitter.stripAllBrackets(raw).trim() : raw;
    }

    /** Characters that take no room, which a reading laid out against the text must not count. */
    static boolean isBidiOrInvisible(char c) {
        return switch (c) {
            case '\u200B', '\u200C', '\u200D', '\u200E', '\u200F',
                 '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
                 '\u2066', '\u2067', '\u2068', '\u2069', '\uFEFF' -> true;
            default -> false;
        };
    }

    static String stripBidiControls(@Nullable String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (isBidiOrInvisible(c)) {
                if (sb == null) {
                    sb = new StringBuilder(s.length());
                    sb.append(s, 0, i);
                }
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb != null ? sb.toString() : s;
    }

    /** Arabic script or combining marks, whose letters join up and break apart under letter spacing. */
    static boolean containsJoiningScript(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            final int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            if ((cp >= 0x0600 && cp <= 0x06FF)
                    || (cp >= 0x0750 && cp <= 0x077F)
                    || (cp >= 0x08A0 && cp <= 0x08FF)
                    || (cp >= 0xFB50 && cp <= 0xFDFF)
                    || (cp >= 0xFE70 && cp <= 0xFEFF)
                    || cp == 0x200D || cp == 0x200C) {
                return true;
            }
            final int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) {
                return true;
            }
        }
        return false;
    }

    static int indexOfIgnoreCase(String haystack, String needle, int fromIndex) {
        final int max = haystack.length() - needle.length();
        final int nLen = needle.length();
        for (int i = Math.max(0, fromIndex); i <= max; i++) {
            if (haystack.regionMatches(true, i, needle, 0, nLen)) {
                return i;
            }
        }
        return -1;
    }
}
