/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.playback.quality;

import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.components.AdvancedVideoQualityMenuFilter;
import app.morphe.extension.youtube.settings.Settings;

/**
 * This patch contains the logic to always open the advanced video quality menu.
 */
@SuppressWarnings("unused")
public final class AdvancedVideoQualityMenuPatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface ShortsQualityMenuInterface {
        // Method is added during patching.
        void patch_showShortsQualityMenu();
    }

    private static WeakReference<ShortsQualityMenuInterface> shortsQualityMenuRef = new WeakReference<>(null);

    /**
     * Injection point.
     * <p>
     * Shorts quality flyout.
     */
    public static void initialize(@NonNull ShortsQualityMenuInterface shortsQualityMenu) {
        shortsQualityMenuRef = new WeakReference<>(shortsQualityMenu);
    }

    /**
     * Injection point.  Regular videos.
     * <p>
     * Regular video quality flyout.
     */
    public static void onFlyoutMenuCreate(RecyclerView recyclerView) {
        if (!Settings.ADVANCED_VIDEO_QUALITY_MENU.get()) return;

        recyclerView.getViewTreeObserver().addOnDrawListener(() -> {
            try {
                // Check if the current view is the quality menu.
                if (!AdvancedVideoQualityMenuFilter.isVideoQualityMenuVisible || recyclerView.getChildCount() == 0) {
                    return;
                }
                AdvancedVideoQualityMenuFilter.isVideoQualityMenuVisible = false;

                if (!(Utils.getParentView(recyclerView, 3) instanceof ViewGroup quickQualityViewParent)) {
                    return;
                }

                if (!(recyclerView.getChildAt(0) instanceof ViewGroup firstChildGroup)) {
                    return;
                }

                if (firstChildGroup.getChildCount() < 4) {
                    return;
                }

                if (!(firstChildGroup.getChildAt(3) instanceof ViewGroup advancedQualityView)) {
                    return;
                }

                quickQualityViewParent.setVisibility(View.GONE);

                // Click the "Advanced" quality menu to show the "old" quality menu.
                advancedQualityView.callOnClick();
            } catch (Exception ex) {
                Logger.printException(() -> "onFlyoutMenuCreate failure", ex);
            }
        });
    }

    /**
     * Injection point.
     * <p>
     * Shorts quality flyout.
     */
    public static boolean showShortsQualityMenu() {
        if (Settings.ADVANCED_VIDEO_QUALITY_MENU.get()) {
            ShortsQualityMenuInterface shortsQualityMenu = shortsQualityMenuRef.get();
            if (shortsQualityMenu != null) {
                Utils.runOnMainThread(shortsQualityMenu::patch_showShortsQualityMenu);
                return true;
            }
        }

        return false;
    }
}