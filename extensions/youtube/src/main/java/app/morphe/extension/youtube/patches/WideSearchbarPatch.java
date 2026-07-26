/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
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

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class WideSearchbarPatch {

    public static void initializeContainer(final View rootToolbar) {
        if (!Settings.WIDE_SEARCHBAR.get()) {
            return;
        }

        if (!(rootToolbar instanceof final ViewGroup toolbarContainer)) {
            return;
        }

        final int backgroundColor;
        final int textColor;
        if (Utils.isDarkModeEnabled()) {
            backgroundColor = Color.parseColor("#1F1F1F");
            textColor = Color.parseColor("#808080");
        } else {
            backgroundColor = Color.parseColor("#F1F1F1");
            textColor = Color.parseColor("#606060");
        }

        final TextView wideSearchBox = new TextView(toolbarContainer.getContext());
        wideSearchBox.setText("Search on Morphe");
        wideSearchBox.setTextColor(textColor);
        wideSearchBox.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        wideSearchBox.setFocusable(false);
        wideSearchBox.setClickable(true);

        final GradientDrawable searchBackground = new GradientDrawable();
        searchBackground.setShape(GradientDrawable.RECTANGLE);
        searchBackground.setCornerRadius(Dim.dp(24));
        searchBackground.setColor(backgroundColor);
        wideSearchBox.setBackground(searchBackground);

        final int paddingHorizontal = Dim.dp(16);
        final int paddingVertical = Dim.dp(0);
        wideSearchBox.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical);

        final int drawableId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "quantum_ic_search_grey600_24");
        if (drawableId != 0) {
            wideSearchBox.setCompoundDrawablesWithIntrinsicBounds(drawableId, 0, 0, 0);
            wideSearchBox.setCompoundDrawablePadding(Dim.dp(8));
        }

        final ViewGroup.MarginLayoutParams currentViewGroupParams;
        final int sideMargin = Dim.dp(16);
        final int searchBarHeight = Dim.dp(36);
        final int logoId = ResourceUtils.getIdentifier(ResourceType.ID, "youtube_logo");
        final View logoView = logoId != 0 ? toolbarContainer.findViewById(logoId) : null;

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
                currentViewGroupParams.setMargins(logoWidth + Dim.dp(6), Dim.dp(8), sideMargin, 0);
            } else {
                currentViewGroupParams.setMargins(sideMargin, Dim.dp(8), sideMargin, 0);
            }
        }

        wideSearchBox.setLayoutParams(currentViewGroupParams);

        wideSearchBox.setOnClickListener(view -> {
            Log.d("ELVIS IS HERE!", "OK");

            Context context = Utils.getContext();
            Intent intent = new Intent();
            intent.setAction("com.google.android.youtube.action.open.search");
            intent.setPackage(context.getPackageName());
            context.startActivity(intent);
        });

        try {
            int targetIndex = 1;
            if (logoView != null) {
                final int logoIndex = toolbarContainer.indexOfChild(logoView);
                if (logoIndex != -1) {
                    targetIndex = logoIndex + 1;
                }
            }
            toolbarContainer.addView(wideSearchBox, targetIndex);
        } catch (final Exception e) {
            try {
                toolbarContainer.addView(wideSearchBox);
            } catch (final Exception ignored) {}
        }
    }
}
