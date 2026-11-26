/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5295
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.view.View;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class DisablePostDetailSwipePatch {

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static void onPostDetailPagerCreated(View pager) {
        try {
            if (Settings.DISABLE_POST_DETAIL_SWIPE.get()) {
                pager.setEnabled(false);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onPostDetailPagerCreated failure", ex);
        }
    }
}
