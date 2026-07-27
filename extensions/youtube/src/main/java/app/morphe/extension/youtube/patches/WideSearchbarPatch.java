/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2221
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.ResourceUtils.getIdentifier;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.NavigationBar.NavigationButton;

@SuppressWarnings("unused")
public class WideSearchbarPatch {

    private static final Boolean WIDE_SEARCHBAR_ENABLED = Settings.WIDE_SEARCHBAR.get();
    private static final int ID_YOUTUBE_LOGO = getIdentifier(ResourceType.ID, "youtube_logo");
    private static final int ID_SEARCH_ICON = getIdentifier(ResourceType.DRAWABLE, "quantum_ic_search_grey600_24");
    private static final int ID_MENU_PRIVACY_POLICY = getIdentifier(ResourceType.ID, "menu_privacy_policy");
    private static final String SEARCH_HINT = ResourceUtils.getString("search_hint");
    private static final List<String> SEARCH_BUTTON_NAMES = List.of("SEARCH", "SEARCH_BOLD", "SEARCH_CAIRO");
    private static final int DP115 = Dim.dp(115);

    private static WeakReference<ImageView> searchButtonViewRef = new WeakReference<>(null);
    private static WeakReference<Menu> buttonMenuRef = new WeakReference<>(null);

    static {
        // Change listener is needed to handle YT hardware back button handler
        // that runs out of order with UI update code.
        NavigationBar.addOnNavigationButtonChangedListener(activeButton ->
                hideButtonMenuItems(buttonMenuRef.get(), activeButton)
        );
    }

    /**
     * Injection point.
     */
    public static void setButtonsMenu(Menu buttonsMenu) {
        if (WIDE_SEARCHBAR_ENABLED && buttonsMenu != null) {
            buttonMenuRef = new WeakReference<>(buttonsMenu);
            hideButtonMenuItems(buttonsMenu, NavigationButton.getSelectedNavigationButton());
        }
    }

    private static void hideButtonMenuItems(Menu buttonsMenu, @Nullable NavigationButton activeButton) {
        try {
            if (buttonsMenu == null) {
                return;
            }
            if (activeButton != NavigationButton.HOME && activeButton != NavigationButton.SUBSCRIPTIONS) {
                return;
            }
            if (NavigationBar.isBackButtonVisible()) {
                return; // User has navigated into a channel page or other subpage.
            }

            for (int i = 0, buttonsMenuSize = buttonsMenu.size(); i < buttonsMenuSize; i++) {
                buttonsMenu.getItem(i).setVisible(false);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "hideButtonMenuItems failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void setSearchButtonView(String enumName, View parentView, ImageView imageView) {
        if (WIDE_SEARCHBAR_ENABLED && SEARCH_BUTTON_NAMES.contains(enumName)) {
            searchButtonViewRef = new WeakReference<>(imageView);
        }
    }

    /**
     * Injection point.
     */
    public static void initializeContainer(View toolbar) {
        try {
            if (!WIDE_SEARCHBAR_ENABLED) {
                return;
            }

            if (!(toolbar instanceof ViewGroup toolbarViewGroup)) {
                return;
            }

            final boolean isDarkModeEnabled = Utils.isDarkModeEnabled();
            final int textColor = Color.parseColor(isDarkModeEnabled
                    ? "#808080"
                    : "#606060");
            final int backgroundColor = Color.parseColor(isDarkModeEnabled
                    ? "#1F1F1F"
                    : "#F1F1F1");

            TextView wideSearchBox = new TextView(toolbarViewGroup.getContext());
            wideSearchBox.setText(SEARCH_HINT);
            wideSearchBox.setTextColor(textColor);
            wideSearchBox.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            wideSearchBox.setFocusable(false);
            wideSearchBox.setClickable(true);

            GradientDrawable searchBackground = new GradientDrawable();
            searchBackground.setShape(GradientDrawable.RECTANGLE);
            searchBackground.setCornerRadius(Dim.dp24);
            searchBackground.setColor(backgroundColor);
            wideSearchBox.setBackground(searchBackground);

            final int paddingHorizontal = Dim.dp16;
            final int paddingVertical = 0;
            wideSearchBox.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical);

            if (ID_SEARCH_ICON != 0) {
                wideSearchBox.setCompoundDrawablesWithIntrinsicBounds(ID_SEARCH_ICON, 0, 0, 0);
                wideSearchBox.setCompoundDrawablePadding(Dim.dp8);
            }

            View logoView = toolbarViewGroup.findViewById(ID_YOUTUBE_LOGO);
            final int sideMargin = Dim.dp16;
            final int searchBarHeight = Dim.dp28;

            ViewGroup.MarginLayoutParams currentViewGroupParams;
            if (toolbarViewGroup instanceof LinearLayout) {
                LinearLayout.LayoutParams linearParams = new LinearLayout.LayoutParams(
                        0, searchBarHeight
                );
                linearParams.weight = 1.0f;
                linearParams.gravity = Gravity.CENTER_VERTICAL;
                currentViewGroupParams = linearParams;
                currentViewGroupParams.setMargins(sideMargin, 0, sideMargin, 0);
            } else {
                currentViewGroupParams = new ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, searchBarHeight
                );
                if (logoView != null) {
                    final int measuredWidth = logoView.getMeasuredWidth();
                    final int logoWidth = measuredWidth > 0 ? measuredWidth : DP115;
                    currentViewGroupParams.setMargins(logoWidth + Dim.dp6, 0, sideMargin, 0);
                } else {
                    currentViewGroupParams.setMargins(sideMargin, 0, sideMargin, 0);
                }

                if (toolbarViewGroup instanceof FrameLayout) {
                    FrameLayout.LayoutParams frameParams = new FrameLayout.LayoutParams(currentViewGroupParams);
                    frameParams.gravity = Gravity.CENTER_VERTICAL;
                    currentViewGroupParams = frameParams;
                }
            }
            wideSearchBox.setLayoutParams(currentViewGroupParams);

            ImageView searchButtonView = searchButtonViewRef.get();
            wideSearchBox.setOnClickListener(view -> {
                if (searchButtonView != null) {
                    searchButtonView.callOnClick();
                }
            });

            int targetIndex = toolbarViewGroup.getChildCount();
            if (logoView != null) {
                final int logoIndex = toolbarViewGroup.indexOfChild(logoView);
                if (logoIndex >= 0) {
                    targetIndex = logoIndex + 1;
                }
            }

            toolbarViewGroup.addView(wideSearchBox, targetIndex);
        } catch (Exception ex) {
            Logger.printException(() -> "initializeContainer failure", ex);
        }
    }
}
