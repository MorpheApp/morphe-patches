/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2221
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class WideSearchbarPatch {

    /**
     * Injection point.
     */
    public static void initializeContainer(final View rootToolbar) {
        try {
        if (!Settings.WIDE_SEARCHBAR.get()) {
            return;
        }

        if (!(rootToolbar instanceof final ViewGroup toolbarContainer)) {
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
        wideSearchBox.setText(ResourceUtils.getString("search_hint"));
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

        final int drawableId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "quantum_ic_search_grey600_24");
        if (drawableId != 0) {
            wideSearchBox.setCompoundDrawablesWithIntrinsicBounds(drawableId, 0, 0, 0);
            wideSearchBox.setCompoundDrawablePadding(Dim.dp8);
        }

        ViewGroup.MarginLayoutParams currentViewGroupParams;
        final int sideMargin = Dim.dp16;
        final int searchBarHeight = Dim.dp36;
        final int logoId = ResourceUtils.getIdentifier(ResourceType.ID, "youtube_logo");
        View logoView = logoId != 0 ? toolbarContainer.findViewById(logoId) : null;

        if (toolbarContainer instanceof LinearLayout) {
            final LinearLayout.LayoutParams linearParams = new LinearLayout.LayoutParams(0, searchBarHeight);
            linearParams.weight = 1.0f;
            linearParams.gravity = Gravity.CENTER_VERTICAL;
            currentViewGroupParams = linearParams;
            currentViewGroupParams.setMargins(sideMargin, 0, sideMargin, 0);
        } else {
            currentViewGroupParams = new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, searchBarHeight);
            if (logoView != null) {
                final int logoWidth = logoView.getMeasuredWidth() > 0 ? logoView.getMeasuredWidth() : Dim.dp(115);
                currentViewGroupParams.setMargins(logoWidth + Dim.dp6, Dim.dp8, sideMargin, 0);
            } else {
                currentViewGroupParams.setMargins(sideMargin, Dim.dp8, sideMargin, 0);
            }
        }

        wideSearchBox.setLayoutParams(currentViewGroupParams);

        wideSearchBox.setOnClickListener(view -> {
            Context context = Utils.getContext();
            Intent intent = new Intent();
            intent.setAction("com.google.android.youtube.action.open.search");
            intent.setPackage(context.getPackageName());
            context.startActivity(intent);
        });

        int targetIndex = 1;
        if (logoView != null) {
            final int logoIndex = toolbarContainer.indexOfChild(logoView);
            if (logoIndex != -1) {
                targetIndex = logoIndex + 1;
            }
        }
        try {
            toolbarContainer.addView(wideSearchBox, targetIndex);
        } catch (Exception ex1) {
            final int targetIndexFinal = targetIndex;
            Logger.printDebug(() -> "Could not add search box at index: " + targetIndexFinal, ex1);
            try {
                toolbarContainer.addView(wideSearchBox);
            } catch (Exception ex2) {
                Logger.printDebug(() -> "Could not add search box view", ex2);
            }
        }
        } catch (Exception ex) {
            Logger.printException(() -> "initializeContainer failure", ex);
        }
    }
}
