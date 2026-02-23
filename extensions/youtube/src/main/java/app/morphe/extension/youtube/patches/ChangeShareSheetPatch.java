/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.youtube.patches;

import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.components.ChangeShareSheetFilter;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Replaces YouTube's in-app share sheet with the system share sheet.
 */
@SuppressWarnings("unused")
public final class ChangeShareSheetPatch {

    private ChangeShareSheetPatch() {
    }

    private static void clickSystemShareButton(final RecyclerView bottomSheetRecyclerView,
                                               final RecyclerView appsContainerRecyclerView) {

        if (!(appsContainerRecyclerView.getChildAt(appsContainerRecyclerView.getChildCount() - 1) instanceof ViewGroup parentView)) {
            return;
        }

        if (!(parentView.getChildAt(0) instanceof ViewGroup shareWithOtherAppsView)) {
            return;
        }

        if (!(Utils.getParentView(bottomSheetRecyclerView, 3) instanceof ViewGroup parentView3rd)) {
            return;
        }

        if (!(parentView3rd.getParent() instanceof ViewGroup parentView4th)) {
            return;
        }

        ChangeShareSheetFilter.isShareSheetVisible = false;

        // Phone layout: dismiss overlay
        View dismissView = parentView4th.getChildAt(0);
        if (dismissView != null) {
            dismissView.setSoundEffectsEnabled(false);
            dismissView.performClick();
        }

        // Tablet layout fallback
        parentView3rd.setVisibility(View.GONE);
        parentView4th.setVisibility(View.GONE);

        // Click "Share with other apps"
        shareWithOtherAppsView.setSoundEffectsEnabled(false);
        shareWithOtherAppsView.performClick();
    }

    /**
     * Injection point.
     */
    public static void onFlyoutMenuCreate(final RecyclerView recyclerView) {
        if (!Settings.CHANGE_SHARE_SHEET.get()) return;

        recyclerView.getViewTreeObserver().addOnDrawListener(() -> {
            try {
                if (!ChangeShareSheetFilter.isShareSheetVisible) return;
                if (recyclerView.getChildCount() != 1) return;

                if (!(recyclerView.getChildAt(0) instanceof ViewGroup parentView5th)) {
                    return;
                }

                if (!(parentView5th.getChildAt(1) instanceof ViewGroup parentView4th)) {
                    return;
                }

                // Case 1
                if (parentView4th.getChildAt(0) instanceof ViewGroup parentView3rd &&
                        parentView3rd.getChildAt(0) instanceof RecyclerView appsContainerRecyclerView) {

                    clickSystemShareButton(recyclerView, appsContainerRecyclerView);
                }

                // Case 2 (layout variation)
                else if (parentView4th.getChildAt(1) instanceof ViewGroup parentView3rd &&
                        parentView3rd.getChildAt(0) instanceof RecyclerView appsContainerRecyclerView) {

                    clickSystemShareButton(recyclerView, appsContainerRecyclerView);
                }

            } catch (Exception ex) {
                Logger.printException(() -> "onFlyoutMenuCreate failure", ex);
            }
        });
    }

    /**
     * Injection point.
     */
    public static boolean changeShareSheetEnabled() {
        return Settings.CHANGE_SHARE_SHEET.get();
    }
}