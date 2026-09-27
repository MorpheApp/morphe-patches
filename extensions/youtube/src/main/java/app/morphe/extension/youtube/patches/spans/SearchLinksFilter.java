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

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.spans.SpanFilter;
import app.morphe.extension.shared.patches.spans.SpanType;
import app.morphe.extension.shared.patches.spans.StringSpanFilterGroup;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings({"unused", "FieldCanBeLocal"})
public final class SearchLinksFilter extends SpanFilter {

    /**
     * Located in front of the search icon.
     */
    private final String WORD_JOINER_CHARACTER = "\u2060";

    public SearchLinksFilter() {
        addCallbacks(
                new StringSpanFilterGroup(
                        Settings.SANITIZE_COMMENTS_HIGHLIGHTED_SEARCH_LINKS,
                        "|comment."
                )
        );
    }

    /**
     * @return Whether the word contains a search icon or not.
     */
    private boolean isSearchLinks(SpannableString original, int end) {
        int wordJoinerIndex = Utils.indexOf(original, WORD_JOINER_CHARACTER);
        // There may be more than one highlight keyword in the comments.
        // Check the index of all highlight keywords.
        while (wordJoinerIndex >= 0) {
            if (end - wordJoinerIndex == 2) return true;
            wordJoinerIndex = Utils.indexOf(original, WORD_JOINER_CHARACTER, wordJoinerIndex + 1);
        }
        return false;
    }

    @Override
    public boolean skip(String identifier, CharSequence path, SpannableString spannableString, Object span,
                        int start, int end, int flags, boolean isWord, SpanType spanType, StringSpanFilterGroup matchedGroup) {
        if (isWord && isSearchLinks(spannableString, end)) {

            if (spanType == SpanType.IMAGE) {
                hideImageSpan(spannableString, start, end, flags);
            }

            return true;
        }
        return false;
    }
}
