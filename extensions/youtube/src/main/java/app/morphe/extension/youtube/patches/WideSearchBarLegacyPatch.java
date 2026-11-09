/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class WideSearchBarLegacyPatch {

    // These targets only have the app's own wide search bar, which always replaces the logo.
    private static final boolean WIDE_SEARCHBAR_ENABLED = Settings.SEARCHBAR_TYPE.get().isEnabled();

    /**
     * Injection point.
     */
    public static boolean enableWideSearchbar(boolean original) {
        return WIDE_SEARCHBAR_ENABLED || original;
    }

    /**
     * Injection point.
     */
    public static void setActionBar(View view) {
        if (WIDE_SEARCHBAR_ENABLED) {
            try {
                View searchBarView = Utils.getChildViewByResourceName(view, "search_bar");

                searchBarView.setPaddingRelative(
                        Dim.dp8,
                        searchBarView.getPaddingTop(),
                        searchBarView.getPaddingEnd(),
                        searchBarView.getPaddingBottom()
                );
            } catch (Exception ex) {
                Logger.printException(() -> "setActionBar failure", ex);
            }
        }
    }
}
