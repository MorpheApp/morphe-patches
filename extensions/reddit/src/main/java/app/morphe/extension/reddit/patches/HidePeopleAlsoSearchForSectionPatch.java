/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5225
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class HidePeopleAlsoSearchForSectionPatch {

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     *
     * @param isQuerySuggestion If a search row child is a query suggestion.
     * @return False to skip the query suggestion.
     */
    public static boolean isQuerySuggestion(boolean isQuerySuggestion) {
        return isQuerySuggestion && !Settings.HIDE_PEOPLE_ALSO_SEARCH_FOR_SECTION.get();
    }
}
