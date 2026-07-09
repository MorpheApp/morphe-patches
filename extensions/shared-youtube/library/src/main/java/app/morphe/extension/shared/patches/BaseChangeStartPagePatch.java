/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

public abstract class BaseChangeStartPagePatch {

    /**
     * Intent action when the app is cold started from the launcher.
     */
    protected static final String ACTION_MAIN = "android.intent.action.MAIN";

    /**
     * Helper method to process browseId overrides.
     *
     * @param original   The original browseId.
     * @param isBrowseId Whether the target start page is a browseId.
     * @param targetId   The target browseId to switch to.
     * @param debugName  The name to print in the debug log.
     * @return The new or original browseId.
     */
    protected static String processBrowseId(@Nullable String original, boolean isBrowseId, String targetId, String debugName) {
        if (!isBrowseId) {
            return original;
        }

        if (targetId == null || targetId.isEmpty()) {
            return original;
        }

        Logger.printDebug(() -> "Changing browseId to: " + debugName);
        return targetId;
    }
}
