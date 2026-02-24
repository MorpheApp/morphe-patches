/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.youtube.patches;

import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;

import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.components.OpenSystemShareSheetFilter;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Replaces YouTube's in-app share sheet with the system share sheet.
 */
@SuppressWarnings("unused")
public final class OpenSystemShareSheetPatch {

    private OpenSystemShareSheetPatch() {
    }

    /**
     * Injection point.
     */
    public static void onFlyoutMenuCreate(final RecyclerView recyclerView) {
        if (!Settings.OPEN_SYSTEM_SHARE_SHEET.get()) return;

        recyclerView.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (!OpenSystemShareSheetFilter.isShareSheetVisible) {

                    recyclerView.getViewTreeObserver().removeOnPreDrawListener(this);
                    return true;
                }

                try {
                    RecyclerView appsContainer = findNestedRecyclerView(recyclerView, recyclerView);

                    if (appsContainer != null && appsContainer.getChildCount() > 0) {

                        View lastChild = appsContainer.getChildAt(appsContainer.getChildCount() - 1);

                        if (lastChild instanceof ViewGroup parentView) {
                            View shareWithOtherAppsView = parentView.getChildAt(0);

                            if (shareWithOtherAppsView != null) {
                                OpenSystemShareSheetFilter.isShareSheetVisible = false;

                                View rootView = recyclerView.getRootView();
                                Objects.requireNonNullElse(rootView, recyclerView).setVisibility(View.GONE);

                                recyclerView.getViewTreeObserver().removeOnPreDrawListener(this);

                                shareWithOtherAppsView.setSoundEffectsEnabled(false);
                                shareWithOtherAppsView.performClick();

                                return false;
                            }
                        }
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "onFlyoutMenuCreate failure", ex);
                    recyclerView.getViewTreeObserver().removeOnPreDrawListener(this);
                }

                return true;
            }
        });
    }

    /**
     * Recursively searches the view hierarchy for a nested RecyclerView.
     */
    private static RecyclerView findNestedRecyclerView(View view, View root) {
        if (view instanceof RecyclerView && view != root) {
            return (RecyclerView) view;
        }

        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                RecyclerView result = findNestedRecyclerView(group.getChildAt(i), root);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * Injection point.
     */
    public static boolean openSystemShareSheetEnabled() {
        return Settings.OPEN_SYSTEM_SHARE_SHEET.get();
    }
}