/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.support.v7.widget.RecyclerView;

import java.lang.ref.WeakReference;
import java.util.regex.Pattern;

@SuppressWarnings("unused")
public final class OpenSystemShareSheetPatch {

    public static boolean systemSheetOpened = false;
    public static final Pattern rawVideoURLRegex =
            Pattern.compile(
                    "ANDROID_SYSTEM_SHARE_DIALOG.*?android\\.intent\\.extra\\.TEXT.*?❙([^❙]+)❙"
            );
    public static WeakReference<RecyclerView> flyoutMenuRecyclerView;

    /**
     * Injection point.
     */
    public static void onFlyoutMenuCreate(final RecyclerView recyclerView) {
        flyoutMenuRecyclerView = new WeakReference<>(recyclerView);
    }
}
