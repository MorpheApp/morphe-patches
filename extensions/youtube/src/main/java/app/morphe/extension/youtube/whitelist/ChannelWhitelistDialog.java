/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2334
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.whitelist;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.VideoInformation;

/**
 * Shows and edits the channels of a {@link WhitelistType}.
 */
public final class ChannelWhitelistDialog {

    private static final float LABEL_ALPHA = 0.7f;
    private static final float EMPTY_LABEL_ALPHA = 0.5f;

    public static void show(Context context, WhitelistType type) {
        try {
            Utils.verifyOnMainThread();

            SheetBottomDialog.DraggableLinearLayout mainLayout =
                    SheetBottomDialog.createMainLayout(context, null);
            mainLayout.setPadding(Dim.dp16, 0, Dim.dp16, Dim.dp16);

            TextView titleView = createLabel(context, type.getTitle(), 18, 1f, Dim.dp8, Dim.dp16);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setGravity(Gravity.CENTER_HORIZONTAL);
            mainLayout.addView(titleView);

            LinearLayout dynamicContainer = new LinearLayout(context);
            dynamicContainer.setOrientation(LinearLayout.VERTICAL);
            mainLayout.addView(dynamicContainer);

            buildContent(context, type, dynamicContainer);

            SheetBottomDialog.SlideDialog dialog =
                    SheetBottomDialog.createSlideDialog(context, mainLayout, 300);
            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "show failure", ex);
        }
    }

    private static void buildContent(Context context, WhitelistType type, LinearLayout container) {
        container.removeAllViews();

        String currentChannelId = VideoInformation.getChannelId();
        if (!currentChannelId.isEmpty()) {
            container.addView(createLabel(context, str("morphe_channel_whitelist_current_channel"),
                    13, LABEL_ALPHA, 0, Dim.dp4));

            String currentChannelName = VideoInformation.getChannelName();
            final boolean isWhitelisted = ChannelWhitelist.isChannelWhitelisted(type, currentChannelId);

            LinearLayout currentRow = createChannelRow(
                    context,
                    currentChannelName.isEmpty() ? currentChannelId : currentChannelName,
                    isWhitelisted
                            ? str("morphe_channel_whitelist_remove")
                            : str("morphe_channel_whitelist_add"),
                    !isWhitelisted,
                    () -> {
                        ChannelWhitelist.toggleChannel(type, currentChannelId, currentChannelName);
                        buildContent(context, type, container);
                    }
            );
            ((LinearLayout.LayoutParams) currentRow.getLayoutParams()).bottomMargin = Dim.dp12;
            container.addView(currentRow);

            container.addView(createDivider(context));
        }

        container.addView(createLabel(context, str("morphe_channel_whitelist_channels_header"),
                13, LABEL_ALPHA, 0, Dim.dp8));

        Map<String, String> channels = ChannelWhitelist.getWhitelistedChannels(type);

        if (channels.isEmpty()) {
            TextView emptyView = createLabel(context, str("morphe_channel_whitelist_empty"),
                    14, EMPTY_LABEL_ALPHA, Dim.dp8, Dim.dp8);
            emptyView.setGravity(Gravity.CENTER_HORIZONTAL);
            container.addView(emptyView);
        } else {
            container.addView(createChannelList(context, type, container, channels));
        }
    }

    private static ScrollView createChannelList(Context context, WhitelistType type,
                                                LinearLayout container, Map<String, String> channels) {
        LinearLayout listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);

        for (Map.Entry<String, String> entry : channels.entrySet()) {
            String channelId = entry.getKey();
            String channelName = entry.getValue();
            listContainer.addView(createChannelRow(
                    context,
                    channelName.isEmpty() ? channelId : channelName,
                    str("morphe_channel_whitelist_remove"),
                    false,
                    () -> {
                        ChannelWhitelist.removeChannel(type, channelId);
                        Utils.showToastShort(str("morphe_channel_whitelist_channel_removed"));
                        buildContent(context, type, container);
                    }
            ));
        }

        ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(Dim.pctHeight(40), channels.size() * Dim.dp(44))));
        scrollView.addView(listContainer);

        return scrollView;
    }

    private static LinearLayout createChannelRow(Context context, String displayName,
                                                 String buttonText, boolean isButtonPrimary,
                                                 Runnable onButtonClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = Dim.dp8;
        row.setLayoutParams(rowParams);

        TextView nameText = new TextView(context);
        nameText.setText(displayName);
        nameText.setTextSize(14);
        nameText.setTextColor(ThemeUtils.getAppForegroundColor());
        nameText.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        nameText.setEllipsize(TextUtils.TruncateAt.END);
        nameText.setSingleLine(true);
        row.addView(nameText);

        Button button = CustomDialog.createButton(
                context, null, buttonText, onButtonClick, isButtonPrimary, false);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Dim.dp36);
        buttonParams.leftMargin = Dim.dp8;
        button.setLayoutParams(buttonParams);
        row.addView(button);

        return row;
    }

    private static TextView createLabel(Context context, String text, int textSize,
                                        float alpha, int topMargin, int bottomMargin) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(textSize);
        label.setTextColor(ThemeUtils.getAppForegroundColor());
        label.setAlpha(alpha);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        params.bottomMargin = bottomMargin;
        label.setLayoutParams(params);
        return label;
    }

    private static View createDivider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(Utils.isDarkModeEnabled() ? 0x26FFFFFF : 0x26000000);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp1);
        params.bottomMargin = Dim.dp12;
        divider.setLayoutParams(params);
        return divider;
    }
}
