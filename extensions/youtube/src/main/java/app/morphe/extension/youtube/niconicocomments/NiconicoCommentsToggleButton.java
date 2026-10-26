/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4080
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 * 
 * The Niconico-style live/replay comments implementation is adapted from
 * david419kr/niconico-yt-morphe-patches, bundle 1.3.3 (GPLv3):
 * https://github.com/david419kr/niconico-yt-morphe-patches
 * The feature design is based on david419kr/niconico-yt (MIT License).
 */

package app.morphe.extension.youtube.niconicocomments;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PipDismissHelper;
import app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton;
import app.morphe.extension.youtube.videoplayer.PlayerOverlayButton;

/**
 * Player button that shows or hides the comments. Tap and hold opens the quick settings.
 */
@SuppressWarnings("unused")
public final class NiconicoCommentsToggleButton {
    /**
     * Danmaku, the name of comments that scroll across the video.
     */
    private static final String BUTTON_TEXT = "弾";
    private static final int SHOWN_TEXT_ALPHA = 255;
    private static final int HIDDEN_TEXT_ALPHA = 102;

    @Nullable
    private static LegacyPlayerControlButton legacy;

    private static WeakReference<TextView> overlayTextRef = new WeakReference<>(null);

    private NiconicoCommentsToggleButton() {
    }

    /**
     * Injection point.
     */
    public static void initializeButton(View controlsView) {
        try {
            if (LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS || !Settings.NICONICO_COMMENTS.get()) {
                return;
            }

            TextView overlay = PlayerOverlayButton.addButtonWithTextOverlay(
                    controlsView,
                    getOnClickListener(),
                    getOnLongClickListener()
            );
            if (overlay != null) {
                overlay.setText(BUTTON_TEXT);
                overlay.setContentDescription(str("morphe_niconico_comments_screen_title"));
                PlayerOverlayButton.setToggleAccessibilityDelegate(
                        overlay, Settings.NICONICO_COMMENTS_SHOWN::get);
            }
            overlayTextRef = new WeakReference<>(overlay);

            updateButtonAppearance();
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            if (!LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS) {
                return;
            }

            legacy = new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_niconico_comments_button_container",
                    "morphe_niconico_comments_button",
                    "morphe_niconico_comments_button_text",
                    null,
                    Settings.NICONICO_COMMENTS,
                    getOnClickListener(),
                    getOnLongClickListener()
            );
            legacy.setTextOverlay(BUTTON_TEXT);
            View legacyButton = Utils.getChildViewByResourceName(controlsView, "morphe_niconico_comments_button");
            PlayerOverlayButton.setToggleAccessibilityDelegate(
                    legacyButton, Settings.NICONICO_COMMENTS_SHOWN::get);

            updateButtonAppearance();
        } catch (Exception ex) {
            Logger.printException(() -> "initializeLegacyButton failure", ex);
        }
    }

    private static View.OnClickListener getOnClickListener() {
        return view -> {
            NiconicoCommentsController.toggleCommentsShown();
            updateButtonAppearance();
        };
    }

    private static View.OnLongClickListener getOnLongClickListener() {
        return view -> {
            showQuickSettings(view.getContext());
            return true;
        };
    }

    /**
     * Dims the button text while the comments are hidden.
     */
    private static void updateButtonAppearance() {
        Utils.verifyOnMainThread();

        final int alpha = Settings.NICONICO_COMMENTS_SHOWN.get() ? SHOWN_TEXT_ALPHA : HIDDEN_TEXT_ALPHA;
        if (legacy != null) {
            legacy.setTextOverlayAlpha(alpha);
        }

        TextView overlay = overlayTextRef.get();
        if (overlay != null) {
            overlay.setTextColor(overlay.getTextColors().withAlpha(alpha));
        }
    }

    /**
     * Shows the font size and opacity sliders. Changes apply immediately.
     */
    private static void showQuickSettings(Context context) {
        try {
            final int foregroundColor = ThemeUtils.getAppForegroundColor();

            SheetBottomDialog.DraggableLinearLayout mainLayout = SheetBottomDialog.createMainLayout(
                    context, LegacyPlayerControlButton.getDialogBackgroundColor());

            LinearLayout contentLayout = new LinearLayout(context);
            contentLayout.setOrientation(LinearLayout.VERTICAL);
            contentLayout.setPadding(Dim.dp16, 0, Dim.dp16, Dim.dp16);
            contentLayout.addView(SheetBottomDialog.createTitle(context,
                    str("morphe_niconico_comments_screen_title"), foregroundColor));
            contentLayout.addView(SheetBottomDialog.createSliderRow(context,
                    str("morphe_niconico_comments_font_size_title"),
                    Settings.NICONICO_COMMENTS_FONT_SIZE,
                    foregroundColor,
                    value -> {
                        Settings.NICONICO_COMMENTS_FONT_SIZE.save(value);
                        NiconicoCommentsController.updateViewState();
                    }));
            contentLayout.addView(SheetBottomDialog.createSliderRow(context,
                    str("morphe_niconico_comments_opacity_title"),
                    Settings.NICONICO_COMMENTS_OPACITY,
                    foregroundColor,
                    value -> {
                        Settings.NICONICO_COMMENTS_OPACITY.save(value);
                        NiconicoCommentsController.updateViewState();
                    }));
            mainLayout.addView(contentLayout);

            SheetBottomDialog.SlideDialog dialog = SheetBottomDialog.createSlideDialog(
                    context, mainLayout, LegacyPlayerControlButton.fadeInDuration);
            PipDismissHelper.dismissOnPip(dialog);
            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showQuickSettings failure", ex);
        }
    }
}
