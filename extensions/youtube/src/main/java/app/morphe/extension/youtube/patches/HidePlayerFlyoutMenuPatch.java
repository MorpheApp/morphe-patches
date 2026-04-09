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

import android.widget.ListView;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class HidePlayerFlyoutMenuPatch {

    private static final boolean HIDE_PLAYER_FLYOUT_CAPTIONS_FOOTER = Settings.HIDE_PLAYER_FLYOUT_CAPTIONS_FOOTER.get();
    private static final boolean HIDE_PLAYER_FLYOUT_CAPTIONS_HEADER = Settings.HIDE_PLAYER_FLYOUT_CAPTIONS_HEADER.get();
    private static final boolean HIDE_PLAYER_FLYOUT_QUALITY_FOOTER = Settings.HIDE_PLAYER_FLYOUT_QUALITY_FOOTER.get();
    private static final boolean HIDE_PLAYER_FLYOUT_QUALITY_HEADER = Settings.HIDE_PLAYER_FLYOUT_QUALITY_HEADER.get();

    public static volatile boolean isCaptionsMenuVisible = false;
    public static volatile boolean isQualityMenuVisible = false;

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

                    boolean hideQualityHeader = isQualityMenuVisible && HIDE_PLAYER_FLYOUT_QUALITY_HEADER;

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

                    boolean hideCaptionsFooter = isCaptionsMenuVisible && HIDE_PLAYER_FLYOUT_CAPTIONS_FOOTER;
                    boolean hideQualityFooter = isQualityMenuVisible && HIDE_PLAYER_FLYOUT_QUALITY_FOOTER;

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

    /**
     * Injection point.
     */
    public static void hideCaptionsOldBottomSheetFooter(ListView listView, View view, Object object, boolean bool) {
        if (HIDE_PLAYER_FLYOUT_CAPTIONS_FOOTER) {
            view = new View(listView.getContext());
        }

        listView.addFooterView(view, object, bool);
    }

    /**
     * Injection point.
     */
    public static View hideCaptionsOldBottomSheetHeader(View parentView, int resId) {
        View headerView = parentView.findViewById(resId);
        Utils.hideViewByRemovingFromParentUnderCondition(
                HIDE_PLAYER_FLYOUT_CAPTIONS_HEADER,
                headerView
        );

        return headerView;
    }

    /**
     * Injection point.
     */
    public static void hideQualityOldBottomSheetFooter(ListView listView, View view, Object object, boolean bool) {
        if (HIDE_PLAYER_FLYOUT_QUALITY_FOOTER) {
            view = new View(listView.getContext());
        }

        listView.addFooterView(view, object, bool);
    }

    /**
     * Injection point.
     */
    public static void hideQualityOldBottomSheetHeader(ListView listView, View view, Object object, boolean bool) {
        if (HIDE_PLAYER_FLYOUT_QUALITY_HEADER) {
            view = new View(listView.getContext());
        }

        listView.addHeaderView(view, object, bool);
    }
}