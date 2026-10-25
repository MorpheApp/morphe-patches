/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 *
 * Pair data: Unicode 17.0 BidiBrackets and CLDR 48 quotation delimiters.
 * https://www.unicode.org/Public/17.0.0/ucd/BidiBrackets.txt
 * https://unicode.org/reports/tr35/tr35-general.html#Delimiters
 *
 * The pair data is distributed under the following license:
 *
 * UNICODE LICENSE V3
 *
 * COPYRIGHT AND PERMISSION NOTICE
 *
 * Copyright (C) 1991-2026 Unicode, Inc.
 *
 * NOTICE TO USER: Carefully read the following legal agreement. BY
 * DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
 * SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
 * TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
 * DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of data files and any associated documentation (the "Data Files") or
 * software and any associated documentation (the "Software") to deal in the
 * Data Files or Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, and/or sell
 * copies of the Data Files or Software, and to permit persons to whom the
 * Data Files or Software are furnished to do so, provided that either (a)
 * this copyright and permission notice appear with all copies of the Data
 * Files or Software, or (b) this copyright and permission notice appear in
 * associated Documentation.
 *
 * THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
 * KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
 * THIRD PARTY RIGHTS.
 *
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
 * BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
 * OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
 * WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
 * ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
 * FILES OR SOFTWARE.
 *
 * Except as contained in this notice, the name of a copyright holder shall
 * not be used in advertising or otherwise to promote the sale, use or other
 * dealings in these Data Files or Software without prior written
 * authorization of the copyright holder.
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
            new Pair("«", "»"),
            new Pair("»", "«"),
            new Pair("༺", "༻"),
            new Pair("༼", "༽"),
            new Pair("᚛", "᚜"),
            new Pair("‘", "’"),
            new Pair("’", "‘"),
            new Pair("’", "’"),
            new Pair("‚", "‘"),
            new Pair("‚", "’"),
            new Pair("“", "’"),
            new Pair("“", "”"),
            new Pair("”", "“"),
            new Pair("”", "”"),
            new Pair("„", "“"),
            new Pair("„", "”"),
            new Pair("‹", "›"),
            new Pair("›", "‹"),
            new Pair("⁅", "⁆"),
            new Pair("⁽", "⁾"),
            new Pair("₍", "₎"),
            new Pair("⌈", "⌉"),
            new Pair("⌊", "⌋"),
            new Pair("〈", "〉"),
            new Pair("❨", "❩"),
            new Pair("❪", "❫"),
            new Pair("❬", "❭"),
            new Pair("❮", "❯"),
            new Pair("❰", "❱"),
            new Pair("❲", "❳"),
            new Pair("❴", "❵"),
            new Pair("⟅", "⟆"),
            new Pair("⟦", "⟧"),
            new Pair("⟨", "⟩"),
            new Pair("⟪", "⟫"),
            new Pair("⟬", "⟭"),
            new Pair("⟮", "⟯"),
            new Pair("⦃", "⦄"),
            new Pair("⦅", "⦆"),
            new Pair("⦇", "⦈"),
            new Pair("⦉", "⦊"),
            new Pair("⦋", "⦌"),
            new Pair("⦍", "⦐"),
            new Pair("⦏", "⦎"),
            new Pair("⦑", "⦒"),
            new Pair("⦓", "⦔"),
            new Pair("⦕", "⦖"),
            new Pair("⦗", "⦘"),
            new Pair("⧘", "⧙"),
            new Pair("⧚", "⧛"),
            new Pair("⧼", "⧽"),
            new Pair("⸢", "⸣"),
            new Pair("⸤", "⸥"),
            new Pair("⸦", "⸧"),
            new Pair("⸨", "⸩"),
            new Pair("⹕", "⹖"),
            new Pair("⹗", "⹘"),
            new Pair("⹙", "⹚"),
            new Pair("⹛", "⹜"),
            new Pair("〈", "〉"),
            new Pair("《", "》"),
            new Pair("「", "」"),
            new Pair("『", "』"),
            new Pair("【", "】"),
            new Pair("〔", "〕"),
            new Pair("〖", "〗"),
            new Pair("〘", "〙"),
            new Pair("〚", "〛"),
            new Pair("﹙", "﹚"),
            new Pair("﹛", "﹜"),
            new Pair("﹝", "﹞"),
            new Pair("＂", "＂"),
            new Pair("＇", "＇"),
            new Pair("（", "）"),
            new Pair("［", "］"),
            new Pair("｛", "｝"),
            new Pair("｟", "｠"),
            new Pair("｢", "｣")
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
            final boolean equals = pair.opening().equals(pair.closing());
            final boolean inWord = equals && at > 0
                    && at + pair.closing().length() < text.length()
                    && Character.isLetterOrDigit(text.codePointBefore(at))
                    && Character.isLetterOrDigit(text.codePointAt(at + pair.closing().length()));
            if (!inWord && text.startsWith(pair.closing(), at)) {
                if (--depth == 0) return at == closing;
                at += pair.closing().length();
            } else if (!equals && text.startsWith(pair.opening(), at)) {
                depth++;
                at += pair.opening().length();
            } else at += Character.charCount(text.codePointAt(at));
        }
        return false;
    }
}
