/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4080
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 * 
 * Niconico-style live/replay comments implementation is adapted from
 * https://github.com/david419kr/niconico-yt-morphe-patches (GPLv3)
 * Feature design is based on david419kr/niconico-yt (MIT License)
 */

package app.morphe.extension.youtube.niconicocomments;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

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
import app.morphe.extension.youtube.videoplayer.PlayerIcons;
import app.morphe.extension.youtube.videoplayer.PlayerOverlayButton;

/**
 * Player button that shows or hides the comments. Tap and hold opens the quick settings.
 */
@SuppressWarnings("unused")
public final class NiconicoCommentsToggleButton {

    @Nullable
    private static LegacyPlayerControlButton legacy;

    private static WeakReference<ImageView> overlayButtonRef = new WeakReference<>(null);

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

            ImageView button = PlayerOverlayButton.addButton(
                    controlsView,
                    PlayerIcons.name(getIconBaseName()),
                    getOnClickListener(),
                    getOnLongClickListener()
            );
            if (button != null) {
                button.setContentDescription(str("morphe_niconico_comments_screen_title"));
                PlayerOverlayButton.setToggleAccessibilityDelegate(
                        button, Settings.NICONICO_COMMENTS_SHOWN::get);
            }
            overlayButtonRef = new WeakReference<>(button);
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
                    "morphe_niconico_comments_button",
                    null,
                    PlayerIcons.name(getIconBaseName()),
                    Settings.NICONICO_COMMENTS,
                    getOnClickListener(),
                    getOnLongClickListener()
            );
            View legacyButton = Utils.getChildViewByResourceName(controlsView, "morphe_niconico_comments_button");
            PlayerOverlayButton.setToggleAccessibilityDelegate(
                    legacyButton, Settings.NICONICO_COMMENTS_SHOWN::get);
        } catch (Exception ex) {
            Logger.printException(() -> "initializeLegacyButton failure", ex);
        }
    }

    private static View.OnClickListener getOnClickListener() {
        return view -> {
            NiconicoCommentsController.toggleCommentsShown();
            updateButtonIcon();
        };
    }

    private static View.OnLongClickListener getOnLongClickListener() {
        return view -> {
            showQuickSettings(view.getContext());
            return true;
        };
    }

    private static void updateButtonIcon() {
        Utils.verifyOnMainThread();

        final int icon = PlayerIcons.id(getIconBaseName());

        ImageView overlayButton = overlayButtonRef.get();
        if (overlayButton != null) {
            overlayButton.setImageResource(icon);
        }

        if (legacy != null) {
            legacy.setIcon(icon);
        }
    }

    private static String getIconBaseName() {
        return Settings.NICONICO_COMMENTS_SHOWN.get()
                ? "morphe_niconico_comments_button_on"
                : "morphe_niconico_comments_button_off";
    }

    /**
     * Shows the font size, opacity, travel duration and max length sliders. Changes apply immediately.
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
            contentLayout.addView(SheetBottomDialog.createSliderRow(context,
                    str("morphe_niconico_comments_travel_duration_title"),
                    Settings.NICONICO_COMMENTS_TRAVEL_DURATION,
                    foregroundColor,
                    value -> {
                        Settings.NICONICO_COMMENTS_TRAVEL_DURATION.save(value);
                        NiconicoCommentsController.updateViewState();
                    }));
            contentLayout.addView(SheetBottomDialog.createSliderRow(context,
                    str("morphe_niconico_comments_max_length_title"),
                    Settings.NICONICO_COMMENTS_MAX_LENGTH,
                    foregroundColor,
                    Settings.NICONICO_COMMENTS_MAX_LENGTH::save));
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
