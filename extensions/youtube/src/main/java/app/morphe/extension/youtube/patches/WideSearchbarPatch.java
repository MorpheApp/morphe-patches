/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2221
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.ResourceUtils.getIdentifier;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class WideSearchbarPatch {

    private static final Boolean WIDE_SEARCHBAR_ENABLED = Settings.WIDE_SEARCHBAR.get();
    private static final int ID_YOUTUBE_LOGO = getIdentifier(ResourceType.ID, "youtube_logo");
    private static final int ID_MENU_ITEM = getIdentifier(ResourceType.ID, "menu_item_view");
    private static final int ID_SEARCH_ICON = getIdentifier(ResourceType.DRAWABLE, "quantum_ic_search_grey600_24");
    private static final String SEARCH_HINT = ResourceUtils.getString("search_hint");
    private static final int DP115 = Dim.dp(115);

    public static WeakReference<ImageView> searchImageViewRef = new WeakReference<>(null);

    /**
     * Injection point.
     */
    public static void setSearchImageView(View parentView) {
        if (WIDE_SEARCHBAR_ENABLED && parentView instanceof ViewGroup parentGroup) {
            View view = parentGroup.findViewById(ID_MENU_ITEM);
            if (view instanceof ImageView searchImageView) {
                searchImageViewRef = new WeakReference<>(searchImageView);
                searchImageView.setVisibility(View.GONE);
            }
        }
    }

    /**
     * Injection point.
     */
    public static void initializeContainer(View rootToolbar) {
        try {
            if (!WIDE_SEARCHBAR_ENABLED) {
                return;
            }

            if (!(rootToolbar instanceof ViewGroup toolbarContainer)) {
                return;
            }

            final boolean isDarkModeEnabled = Utils.isDarkModeEnabled();
            final int backgroundColor = Color.parseColor(isDarkModeEnabled
                    ? "#1F1F1F"
                    : "#F1F1F1");
            final int textColor = Color.parseColor(isDarkModeEnabled
                    ? "#808080"
                    : "#606060");

            TextView wideSearchBox = new TextView(toolbarContainer.getContext());
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

            View logoView = toolbarContainer.findViewById(ID_YOUTUBE_LOGO);
            ViewGroup.MarginLayoutParams currentViewGroupParams;
            final int sideMargin = Dim.dp16;
            final int searchBarHeight = Dim.dp36;

            if (toolbarContainer instanceof LinearLayout) {
                LinearLayout.LayoutParams linearParams = new LinearLayout.LayoutParams(
                        0, searchBarHeight);
                linearParams.weight = 1.0f;
                linearParams.gravity = Gravity.CENTER_VERTICAL;
                currentViewGroupParams = linearParams;
                currentViewGroupParams.setMargins(sideMargin, 0, sideMargin, 0);
            } else {
                currentViewGroupParams = new ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, searchBarHeight);
                if (logoView != null) {
                    final int measuredWidth = logoView.getMeasuredWidth();
                    final int logoWidth = measuredWidth > 0 ? measuredWidth : DP115;
                    currentViewGroupParams.setMargins(logoWidth + Dim.dp6, Dim.dp8, sideMargin, 0);
                } else {
                    currentViewGroupParams.setMargins(sideMargin, Dim.dp8, sideMargin, 0);
                }
            }
            wideSearchBox.setLayoutParams(currentViewGroupParams);

            wideSearchBox.setOnClickListener(view -> {
                ImageView searchView = searchImageViewRef.get();
                if (searchView != null) {
                    searchView.callOnClick();
                    return;
                }
                // Fallback to using an intent. Only used with 20.40 and older.
                Logger.printDebug(() -> "Falling back to search intent");
                Context context = Utils.getActivity();
                Intent intent = new Intent();
                intent.setAction("com.google.android.youtube.action.open.search");
                intent.setPackage(context.getPackageName());
                context.startActivity(intent);
            });

            int targetIndex = toolbarContainer.getChildCount();
            if (logoView != null) {
                final int logoIndex = toolbarContainer.indexOfChild(logoView);
                if (logoIndex >= 0) {
                    targetIndex = logoIndex + 1;
                }
            }
            toolbarContainer.addView(wideSearchBox, targetIndex);
        } catch (Exception ex) {
            Logger.printException(() -> "initializeContainer failure", ex);
        }
    }
}
