/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import java.util.Locale;

/** Final, display-only lyric cleanup, including an old-to-new UTF-16 offset map. */
public final class LyricsTextFormatter {
    public record Result(String text, int[] offsets) {
        public int offset(int original) {
            return original < 0 ? original : offsets[Math.min(original, offsets.length - 1)];
        }
    }

    private LyricsTextFormatter() { }

    public static Result format(String input, boolean capitalize, boolean strip) {
        String text = input == null ? "" : input;
        boolean[] remove = new boolean[text.length()];
        if (strip) {
            int lineStart = 0;
            while (lineStart <= text.length()) {
                int end = text.indexOf('\n', lineStart);
                if (end < 0) end = text.length();
                for (int at = end; at > lineStart;) {
                    final int cp = text.codePointBefore(at);
                    final int start = at - Character.charCount(cp);
                    if (!isPunctuation(cp) && !Character.isWhitespace(cp)
                            && !Character.isSpaceChar(cp)) break;
                    if (isRedundant(cp) && !isEllipsisDot(text, start, at, cp)) {
                        for (int i = start; i < at; i++) remove[i] = true;
                    }
                    at = start;
                }
                if (end == text.length()) break;
                lineStart = end + 1;
            }
        }
        StringBuilder out = new StringBuilder(text.length());
        int[] offsets = new int[text.length() + 1];
        boolean firstCased = true;
        for (int at = 0; at < text.length();) {
            final int cp = text.codePointAt(at);
            final int end = at + Character.charCount(cp);
            for (int i = at; i < end; i++) offsets[i] = out.length();
            if (!remove[at]) {
                String own = text.substring(at, end);
                if (capitalize && firstCased && (Character.isLowerCase(cp)
                        || Character.isUpperCase(cp) || Character.isTitleCase(cp))) {
                    out.append(own.toUpperCase(Locale.ROOT));
                    firstCased = false;
                } else {
                    out.append(own);
                }
            }
            if (cp == '\n') firstCased = true;
            offsets[end] = out.length();
            at = end;
        }
        return new Result(out.toString(), offsets);
    }

    private static boolean isPunctuation(int cp) {
        return switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                    Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                    Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    /** A conservative semantic allowlist within Unicode punctuation, not a \p{P} deletion. */
    private static boolean isRedundant(int cp) {
        if (Character.getType(cp) != Character.OTHER_PUNCTUATION
                || Character.isMirrored(cp) || cp == '\'' || cp == '"') return false;
        String name = Character.getName(cp);
        if (name == null || name.contains("QUESTION") || name.contains("EXCLAMATION")
                || name.contains("INTERROBANG") || name.contains("ELLIPSIS")
                || name.contains("QUOTATION") || name.contains("APOSTROPHE")) return false;
        return name.contains("FULL STOP") || name.endsWith("COMMA")
                || name.endsWith("SEMICOLON") || name.endsWith("COLON")
                || name.endsWith("DANDA") || cp == 0x2024;
    }

    private static boolean isEllipsisDot(String text, int start, int end, int cp) {
        String name = Character.getName(cp);
        if (cp != 0x2024 && (name == null || !name.contains("FULL STOP"))) return false;
        return (start > 0 && text.codePointBefore(start) == cp)
                || (end < text.length() && text.codePointAt(end) == cp);
    }
}
