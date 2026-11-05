/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.soundproof.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a track credit such as "A, B & C feat. D" into the names of each artist.
 * <p>
 * Only separators that are rarely part of a single artist name are used. "+", "and" and "with"
 * are left out on purpose: they appear in many band names ("Florence + the Machine").
 */
public final class ArtistCreditSplitter {

    private static final Pattern CREDIT_SEPARATOR = Pattern.compile(
            "\\s*[,&]\\s*|\\s+(?:x|×|feat\\.?|ft\\.?|featuring|vs\\.?)\\s+",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private ArtistCreditSplitter() {
    }

    /**
     * @return The non-blank artist names of the credit, in their original order.
     */
    public static List<String> split(String credit) {
        List<String> artistNames = new ArrayList<>();
        for (String part : CREDIT_SEPARATOR.split(credit)) {
            String artistName = part.trim();
            if (!artistName.isEmpty()) {
                artistNames.add(artistName);
            }
        }
        return artistNames;
    }
}
