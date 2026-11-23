/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2221
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.NavigationBar.NavigationButton;

@SuppressWarnings("unused")
public class WideSearchBarPatch {

    public enum SearchbarType {
        DISABLED,
        /** Search bar is shown beside the header logo. */
        WIDE,
        /** Search bar replaces the header logo and spans the entire toolbar. */
        EXTRA_WIDE;

        public boolean isEnabled() {
            return this != DISABLED;
        }
    }

    private static final SearchbarType SEARCHBAR_TYPE = Settings.SEARCHBAR_TYPE.get();
    private static final boolean WIDE_SEARCHBAR_ENABLED = SEARCHBAR_TYPE.isEnabled();
    private static final int ID_YOUTUBE_LOGO =
            ResourceUtils.getIdentifier(ResourceType.ID, "youtube_logo");
    private static final int ID_SEARCH_ICON =
            ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "morphe_settings_search_icon_bold");
    private static final String SEARCH_HINT = ResourceUtils.getString("search_hint");
    private static final int DP115 = Dim.dp(115);
    private static final String LEADING_ACCESSORIES_SEARCHBAR_TAG = "morphe_wide_searchbar";
    private static final float LEADING_ACCESSORIES_SEARCHBAR_WEIGHT = 1.0f;

    private static WeakReference<View> searchButtonViewParentRef = new WeakReference<>(null);
    private static WeakReference<View> searchButtonViewRef = new WeakReference<>(null);

    static {
        // Change listener is needed to handle YT hardware back button handler
        // that runs out of order with UI update code.
        NavigationBar.addOnNavigationButtonChangedListener(activeButton ->
                hideSearchButton(searchButtonViewParentRef.get(), activeButton)
        );
    }

    /**
     * Injection point.
     */
    public static void setSearchButtonView(String enumName, View parentView, ImageView imageView) {
        if (WIDE_SEARCHBAR_ENABLED && NavigationButton.SEARCH.ytEnumNames.contains(enumName)) {
            searchButtonViewParentRef = new WeakReference<>(parentView);
            searchButtonViewRef = new WeakReference<>(imageView);
            hideSearchButton(parentView, NavigationButton.getSelectedNavigationButton());
        }
    }

    private static void hideSearchButton(@Nullable View searchParentView, @Nullable NavigationButton activeButton) {
        try {
            if (searchParentView == null) {
                return;
            }
            if (activeButton != NavigationButton.HOME
                    && activeButton != NavigationButton.SUBSCRIPTIONS
                    && activeButton != NavigationButton.NOTIFICATIONS
                    && activeButton != NavigationButton.LIBRARY) {
                return;
            }
            if (NavigationBar.isBackButtonVisible()) {
                return; // User has navigated into a channel page or other subpage.
            }

            searchParentView.setVisibility(View.GONE);
        } catch (Exception ex) {
            Logger.printException(() -> "hideSearchButton failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void initializeWideSearchbar(View toolbar) {
        try {
            if (!WIDE_SEARCHBAR_ENABLED) {
                return;
            }

            if (!(toolbar instanceof ViewGroup toolbarViewGroup)) {
                return;
            }

            TextView wideSearchBox = createSearchBox(toolbarViewGroup.getContext());

            View logoView = toolbarViewGroup.findViewById(ID_YOUTUBE_LOGO);
            final boolean extraWide = SEARCHBAR_TYPE == SearchbarType.EXTRA_WIDE;
            final int sideMargin = Dim.dp10;
            final int searchBarHeight = Dim.dp32;

            ViewGroup.MarginLayoutParams currentViewGroupParams;
            if (toolbarViewGroup instanceof LinearLayout) {
                LinearLayout.LayoutParams linearParams =
                        new LinearLayout.LayoutParams(0, searchBarHeight);
                linearParams.weight = 1.0f;
                linearParams.gravity = Gravity.CENTER_VERTICAL;
                currentViewGroupParams = linearParams;
                currentViewGroupParams.setMargins(sideMargin, 0, sideMargin, 0);
            } else {
                currentViewGroupParams = new ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, searchBarHeight
                );

                int startMargin = extraWide ? Dim.dp12 : sideMargin;
                final int endMargin = extraWide ? Dim.dp12 : sideMargin;

                // Search bar fills the toolbar, so it must not overlap the logo that stays visible.
                if (!extraWide && logoView != null) {
                    final int measuredWidth = logoView.getMeasuredWidth();
                    final int logoWidth = measuredWidth > 0 ? measuredWidth : DP115;
                    startMargin = logoWidth + Dim.dp16;
                }

                currentViewGroupParams.setMarginStart(startMargin);
                currentViewGroupParams.setMarginEnd(endMargin);

                if (toolbarViewGroup instanceof FrameLayout) {
                    FrameLayout.LayoutParams frameParams = new FrameLayout.LayoutParams(
                            currentViewGroupParams);
                    frameParams.gravity = Gravity.CENTER_VERTICAL;
                    currentViewGroupParams = frameParams;
                }
            }
            wideSearchBox.setLayoutParams(currentViewGroupParams);

            int targetIndex = toolbarViewGroup.getChildCount();
            if (logoView != null) {
                final int logoIndex = toolbarViewGroup.indexOfChild(logoView);
                if (logoIndex >= 0) {
                    targetIndex = logoIndex + 1;
                }

                if (extraWide) {
                    logoView.setVisibility(View.GONE);
                }
            }

            toolbarViewGroup.addView(wideSearchBox, targetIndex);
        } catch (Exception ex) {
            Logger.printException(() -> "initializeContainer failure", ex);
        }
    }

    private static TextView createSearchBox(Context context) {
        final int backgroundColor = ThemeUtils.getEditTextBackground();
        final int textColor = Utils.adjustColorBrightness(
                ThemeUtils.getAppForegroundColor(), 1.60f, 0.67f);

        TextView wideSearchBox = new TextView(context);
        wideSearchBox.setPadding(Dim.dp12, 0, Dim.dp12, 0);
        wideSearchBox.setText(SEARCH_HINT);
        wideSearchBox.setTextSize(16);
        wideSearchBox.setTextColor(textColor);
        wideSearchBox.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        wideSearchBox.setSingleLine(true);
        wideSearchBox.setEllipsize(TextUtils.TruncateAt.END);
        wideSearchBox.setFocusable(false);
        wideSearchBox.setClickable(true);

        GradientDrawable searchBackground = new GradientDrawable();
        searchBackground.setShape(GradientDrawable.RECTANGLE);
        searchBackground.setCornerRadius(Dim.dp24);
        searchBackground.setColor(backgroundColor);
        wideSearchBox.setBackground(searchBackground);

        Drawable searchIcon = context.getDrawable(ID_SEARCH_ICON);
        if (searchIcon != null) {
            searchIcon = searchIcon.mutate();
            searchIcon.setTint(textColor);

            wideSearchBox.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    null, null, searchIcon, null);
            wideSearchBox.setCompoundDrawablePadding(Dim.dp8);
        }

        wideSearchBox.setOnClickListener(view -> {
            View searchButtonView = searchButtonViewRef.get();
            if (searchButtonView != null) {
                searchButtonView.callOnClick();
            } else {
                Logger.printDebug(() -> "Using search intent");
                Intent intent = new Intent();
                intent.setAction("com.google.android.youtube.action.open.search");
                Context activity = Utils.getActivity();
                intent.setPackage(activity.getPackageName());
                activity.startActivity(intent);
            }
        });

        return wideSearchBox;
    }

    /**
     * Injection point.
     * <p>
     * Called after each view is added to the toolbar leading accessories container,
     * such as the "Accounts" button that replaces the header logo in the 'You' tab.
     */
    public static void addLeadingAccessoriesSearchbar(ViewGroup container) {
        try {
            if (!WIDE_SEARCHBAR_ENABLED) {
                return;
            }

            View searchBox = container.findViewWithTag(LEADING_ACCESSORIES_SEARCHBAR_TAG);
            if (searchBox != null) {
                // Keep the search bar after all accessories.
                container.removeView(searchBox);
            } else {
                searchBox = createSearchBox(container.getContext());
                searchBox.setTag(LEADING_ACCESSORIES_SEARCHBAR_TAG);

                LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                        0, Dim.dp32, LEADING_ACCESSORIES_SEARCHBAR_WEIGHT);
                searchParams.gravity = Gravity.CENTER_VERTICAL;
                searchParams.setMarginStart(Dim.dp10);
                searchParams.setMarginEnd(Dim.dp10);
                searchBox.setLayoutParams(searchParams);
            }

            // The container wraps its content, so stretch it to the toolbar menu buttons.
            ViewGroup.LayoutParams containerParams = container.getLayoutParams();
            if (containerParams != null && containerParams.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                containerParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                container.setLayoutParams(containerParams);
            }

            container.addView(searchBox);
        } catch (Exception ex) {
            Logger.printException(() -> "addLeadingAccessoriesSearchbar failure", ex);
        }
    }
}
