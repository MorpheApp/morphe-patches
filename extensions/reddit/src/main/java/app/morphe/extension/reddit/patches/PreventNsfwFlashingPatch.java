/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4869
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

import com.reddit.agegating.impl.nsfw.composables.NsfwBottomSheet;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class PreventNsfwFlashingPatch {

    /**
     * Injection point.
     * Called instead of showing a NSFW profile, before any of its content is shown.
     * Replaces the profile with the native NSFW warning.
     */
    public static void showProfileWarning(Context context) {
        Utils.runOnMainThread(() -> {
            try {
                if (context instanceof Activity activity
                        && (activity.isFinishing() || activity.isDestroyed())) {
                    return;
                }

                Bundle arguments = new Bundle();
                arguments.putString("tag_age_target_screen_page_type", "profile");
                replaceScreen(context, new NsfwBottomSheet(arguments));
            } catch (Exception ex) {
                Logger.printException(() -> "showProfileWarning failure", ex);
            }
        });
    }

    /**
     * Replaces the current screen with the given screen.
     */
    private static void replaceScreen(Context context, Object screen) {
        // Modified during patching.
    }
}
