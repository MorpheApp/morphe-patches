/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class HidePlayerFlyoutMenuPatch {

    public static volatile boolean isCaptionsMenuVisible = false;
    public static volatile boolean isQualityMenuVisible = false;

    private HidePlayerFlyoutMenuPatch() {}

    /**
     * Injection point.
     */
    public static void onFlyoutMenuCreate(RecyclerView recyclerView) {
        if (recyclerView == null) return;

        recyclerView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                try {
                    if (!isCaptionsMenuVisible && !isQualityMenuVisible) return;
                    if (recyclerView.getChildCount() == 0) return;

                    View sheetContent = recyclerView.getChildAt(0);
                    if (!(sheetContent instanceof ViewGroup viewGroup)) return;

                    int childCount = viewGroup.getChildCount();
                    if (childCount < 3) return;

                    recyclerView.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                    int topHeightToSubtract = 0;
                    int bottomHeightToSubtract = 0;

                    boolean hideQualityHeader = isQualityMenuVisible && Settings.HIDE_PLAYER_FLYOUT_QUALITY_HEADER.get();

                    if (hideQualityHeader) {
                        boolean headerFound = false;
                        for (int i = 0; i < childCount; i++) {
                            View child = viewGroup.getChildAt(i);
                            if (child == null || child.getVisibility() == View.GONE) continue;

                            if (child.getHeight() > 0 && child.getHeight() < 10) {
                                topHeightToSubtract += child.getHeight();
                                child.setVisibility(View.GONE);
                            } else if (child.getHeight() >= 10 && !headerFound) {
                                topHeightToSubtract += child.getHeight();
                                child.setVisibility(View.GONE);
                                headerFound = true;
                            } else if (child.getHeight() >= 10 && headerFound) {
                                break;
                            }
                        }
                    }

                    boolean hideCaptionsFooter = isCaptionsMenuVisible && Settings.HIDE_PLAYER_FLYOUT_CAPTIONS_FOOTER.get();
                    boolean hideQualityFooter = isQualityMenuVisible && Settings.HIDE_PLAYER_FLYOUT_QUALITY_FOOTER.get();

                    if (hideCaptionsFooter || hideQualityFooter) {
                        boolean footerFound = false;
                        for (int i = childCount - 1; i >= 0; i--) {
                            View child = viewGroup.getChildAt(i);
                            if (child == null || child.getVisibility() == View.GONE) continue;

                            if (child.getHeight() > 0 && child.getHeight() < 10) {
                                bottomHeightToSubtract += child.getHeight();
                                child.setVisibility(View.GONE);
                            } else if (child.getHeight() >= 10 && !footerFound) {
                                bottomHeightToSubtract += child.getHeight();
                                child.setVisibility(View.GONE);
                                footerFound = true;
                            } else if (child.getHeight() >= 10 && footerFound) {
                                break;
                            }
                        }
                    }

                    if (topHeightToSubtract > 0 || bottomHeightToSubtract > 0) {
                        ViewGroup.LayoutParams params = viewGroup.getLayoutParams();
                        if (params instanceof ViewGroup.MarginLayoutParams marginParams) {
                            marginParams.topMargin = -topHeightToSubtract;
                            marginParams.bottomMargin = -bottomHeightToSubtract;
                            viewGroup.setLayoutParams(marginParams);
                            viewGroup.requestLayout();
                        }
                    }

                    isCaptionsMenuVisible = false;
                    isQualityMenuVisible = false;

                } catch (Exception ex) {
                    Logger.printException(() -> "HidePlayerFlyoutMenuPatch failure", ex);
                }
            }
        });
    }
}