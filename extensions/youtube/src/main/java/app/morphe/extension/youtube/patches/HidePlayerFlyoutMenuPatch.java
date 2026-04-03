/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
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

    public static volatile boolean isQualityMenuVisible = false;
    public static volatile boolean isCaptionsMenuVisible = false;

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
                    if (!isQualityMenuVisible && !isCaptionsMenuVisible) return;
                    if (recyclerView.getChildCount() == 0) return;

                    View sheetContent = recyclerView.getChildAt(0);
                    if (!(sheetContent instanceof ViewGroup viewGroup)) return;

                    int childCount = viewGroup.getChildCount();
                    if (childCount < 2) return;

                    recyclerView.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                    int topHeightToSubtract = 0;
                    int bottomHeightToSubtract = 0;

                    boolean hideQualityFooter = isQualityMenuVisible && Settings.HIDE_PLAYER_FLYOUT_QUALITY_FOOTER.get();
                    boolean hideCaptionsFooter = isCaptionsMenuVisible && Settings.HIDE_PLAYER_FLYOUT_CAPTIONS_FOOTER.get();

                    if (hideQualityFooter || hideCaptionsFooter) {
                        for (int i = childCount - 1; i >= 0; i--) {
                            View child = viewGroup.getChildAt(i);
                            if (child == null || child.getVisibility() == View.GONE) continue;

                            if (child.getHeight() < 10) {
                                bottomHeightToSubtract += child.getHeight();
                                child.setVisibility(View.GONE);
                            } else {
                                bottomHeightToSubtract += child.getHeight();
                                child.setVisibility(View.GONE);
                                break;
                            }
                        }
                    }

                    boolean hideQualityHeader = isQualityMenuVisible && Settings.HIDE_PLAYER_FLYOUT_QUALITY_HEADER.get();
                    boolean hideCaptionsHeader = isCaptionsMenuVisible && Settings.HIDE_PLAYER_FLYOUT_CAPTIONS_HEADER.get();

                    if (hideQualityHeader || hideCaptionsHeader) {
                        for (int i = 0; i < childCount; i++) {
                            View child = viewGroup.getChildAt(i);
                            if (child == null || child.getVisibility() == View.GONE) continue;

                            if (child.getHeight() < 10) {
                                topHeightToSubtract += child.getHeight();
                                child.setVisibility(View.GONE);
                            } else {
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

                    isQualityMenuVisible = false;
                    isCaptionsMenuVisible = false;

                } catch (Exception ex) {
                    Logger.printException(() -> "HidePlayerFlyoutMenuPatch Litho failure", ex);
                }
            }
        });
    }
}