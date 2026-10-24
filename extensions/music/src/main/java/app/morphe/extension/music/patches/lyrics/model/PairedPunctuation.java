/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 *
 * Pair data: Unicode 17.0 BidiBrackets and CLDR 48 quotation delimiters.
 * https://www.unicode.org/Public/17.0.0/ucd/BidiBrackets.txt
 * https://unicode.org/reports/tr35/tr35-general.html#Delimiters
 * Unicode data is distributed under https://www.unicode.org/license.txt .
 */
package app.morphe.extension.music.patches.lyrics.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Restores only complete enclosing punctuation shells that a split crosses.
 */
public final class PairedPunctuation {
    public record Pair(String opening, String closing) {
    }

    public static final List<Pair> PAIRS = List.of(
            new Pair("\"", "\""),
            new Pair("'", "'"),
            new Pair("(", ")"),
            new Pair("[", "]"),
            new Pair("{", "}"),
            new Pair("\u00ab", "\u00bb"),
            new Pair("\u00bb", "\u00ab"),
            new Pair("\u0f3a", "\u0f3b"),
            new Pair("\u0f3c", "\u0f3d"),
            new Pair("\u169b", "\u169c"),
            new Pair("\u2018", "\u2019"),
            new Pair("\u2019", "\u2018"),
            new Pair("\u2019", "\u2019"),
            new Pair("\u201a", "\u2018"),
            new Pair("\u201a", "\u2019"),
            new Pair("\u201c", "\u2019"),
            new Pair("\u201c", "\u201d"),
            new Pair("\u201d", "\u201c"),
            new Pair("\u201d", "\u201d"),
            new Pair("\u201e", "\u201c"),
            new Pair("\u201e", "\u201d"),
            new Pair("\u2039", "\u203a"),
            new Pair("\u203a", "\u2039"),
            new Pair("\u2045", "\u2046"),
            new Pair("\u207d", "\u207e"),
            new Pair("\u208d", "\u208e"),
            new Pair("\u2308", "\u2309"),
            new Pair("\u230a", "\u230b"),
            new Pair("\u2329", "\u232a"),
            new Pair("\u2768", "\u2769"),
            new Pair("\u276a", "\u276b"),
            new Pair("\u276c", "\u276d"),
            new Pair("\u276e", "\u276f"),
            new Pair("\u2770", "\u2771"),
            new Pair("\u2772", "\u2773"),
            new Pair("\u2774", "\u2775"),
            new Pair("\u27c5", "\u27c6"),
            new Pair("\u27e6", "\u27e7"),
            new Pair("\u27e8", "\u27e9"),
            new Pair("\u27ea", "\u27eb"),
            new Pair("\u27ec", "\u27ed"),
            new Pair("\u27ee", "\u27ef"),
            new Pair("\u2983", "\u2984"),
            new Pair("\u2985", "\u2986"),
            new Pair("\u2987", "\u2988"),
            new Pair("\u2989", "\u298a"),
            new Pair("\u298b", "\u298c"),
            new Pair("\u298d", "\u2990"),
            new Pair("\u298f", "\u298e"),
            new Pair("\u2991", "\u2992"),
            new Pair("\u2993", "\u2994"),
            new Pair("\u2995", "\u2996"),
            new Pair("\u2997", "\u2998"),
            new Pair("\u29d8", "\u29d9"),
            new Pair("\u29da", "\u29db"),
            new Pair("\u29fc", "\u29fd"),
            new Pair("\u2e22", "\u2e23"),
            new Pair("\u2e24", "\u2e25"),
            new Pair("\u2e26", "\u2e27"),
            new Pair("\u2e28", "\u2e29"),
            new Pair("\u2e55", "\u2e56"),
            new Pair("\u2e57", "\u2e58"),
            new Pair("\u2e59", "\u2e5a"),
            new Pair("\u2e5b", "\u2e5c"),
            new Pair("\u3008", "\u3009"),
            new Pair("\u300a", "\u300b"),
            new Pair("\u300c", "\u300d"),
            new Pair("\u300e", "\u300f"),
            new Pair("\u3010", "\u3011"),
            new Pair("\u3014", "\u3015"),
            new Pair("\u3016", "\u3017"),
            new Pair("\u3018", "\u3019"),
            new Pair("\u301a", "\u301b"),
            new Pair("\ufe59", "\ufe5a"),
            new Pair("\ufe5b", "\ufe5c"),
            new Pair("\ufe5d", "\ufe5e"),
            new Pair("\uff02", "\uff02"),
            new Pair("\uff07", "\uff07"),
            new Pair("\uff08", "\uff09"),
            new Pair("\uff3b", "\uff3d"),
            new Pair("\uff5b", "\uff5d"),
            new Pair("\uff5f", "\uff60"),
            new Pair("\uff62", "\uff63")
    );

    private record Shell(Pair pair, int openEnd, int closeStart) {
    }

    private PairedPunctuation() {
    }

    public static String restore(String whole, String chunk, int start, int end) {
        List<Shell> shells = new ArrayList<>();
        int left = 0, right = whole.length();
        while (left < right) {
            while (left < right && space(whole.codePointAt(left)))
                left += Character.charCount(whole.codePointAt(left));
            while (right > left && space(whole.codePointBefore(right)))
                right -= Character.charCount(whole.codePointBefore(right));
            Shell found = null;
            for (Pair pair : PAIRS) {
                int openEnd = left + pair.opening().length(), closeStart = right - pair.closing().length();
                if (openEnd > closeStart || !whole.startsWith(pair.opening(), left)
                        || !whole.startsWith(pair.closing(), closeStart)
                        || !closesAtEnd(whole, pair, openEnd, closeStart)) continue;
                found = new Shell(pair, openEnd, closeStart);
                break;
            }
            if (found == null) break;
            shells.add(found);
            left = found.openEnd();
            right = found.closeStart();
        }
        StringBuilder out = new StringBuilder();
        for (Shell shell : shells) if (start >= shell.openEnd()) out.append(shell.pair().opening());
        out.append(chunk);
        for (int i = shells.size() - 1; i >= 0; i--) {
            Shell shell = shells.get(i);
            if (end <= shell.closeStart()) out.append(shell.pair().closing());
        }
        return out.toString();
    }

    private static boolean space(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    private static boolean escaped(String text, int at) {
        int count = 0;
        while (at > 0 && text.charAt(--at) == '\\') count++;
        return (count & 1) != 0;
    }

    private static boolean closesAtEnd(String text, Pair pair, int at, int closing) {
        int depth = 1;
        while (at <= closing) {
            if (escaped(text, at)) {
                at += Character.charCount(text.codePointAt(at));
                continue;
            }
            // Apostrophes inside a word are not quotation boundaries.
            boolean inWord = pair.opening().equals(pair.closing()) && at > 0
                    && at + pair.closing().length() < text.length()
                    && Character.isLetterOrDigit(text.codePointBefore(at))
                    && Character.isLetterOrDigit(text.codePointAt(at + pair.closing().length()));
            if (!inWord && text.startsWith(pair.closing(), at)) {
                if (--depth == 0) return at == closing;
                at += pair.closing().length();
            } else if (!pair.opening().equals(pair.closing()) && text.startsWith(pair.opening(), at)) {
                depth++;
                at += pair.opening().length();
            } else at += Character.charCount(text.codePointAt(at));
        }
        return false;
    }
}
