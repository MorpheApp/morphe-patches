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
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.settings.YouTubeActivityHook;
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
    private static WeakReference<SheetBottomDialog.SlideDialog> quickSettingsRef = new WeakReference<>(null);

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
            legacyButton.setContentDescription(str("morphe_niconico_comments_screen_title"));
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

    static void dismissQuickSettings() {
        SheetBottomDialog.SlideDialog dialog = quickSettingsRef.get();
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private static String getIconBaseName() {
        return Settings.NICONICO_COMMENTS_SHOWN.get()
                ? "morphe_niconico_comments_button_on"
                : "morphe_niconico_comments_button_off";
    }

    /**
     * Shows the font size, opacity, travel duration, display area and max length sliders.
     * Changes apply immediately. In portrait with separate settings, the font size, travel duration
     * and display area sliders change the portrait values.
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
            final boolean portrait = NiconicoCommentsController.usesPortraitSettings();
            IntegerSetting fontSize = NiconicoCommentsController.getFontSizeSetting();
            IntegerSetting travelDuration = NiconicoCommentsController.getTravelDurationSetting();
            IntegerSetting displayArea = NiconicoCommentsController.getDisplayAreaSetting();
            contentLayout.addView(SheetBottomDialog.createSliderRow(context,
                    str(portrait
                            ? "morphe_niconico_comments_portrait_font_size_title"
                            : "morphe_niconico_comments_font_size_title"),
                    fontSize,
                    foregroundColor,
                    value -> {
                        fontSize.save(value);
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
                    str(portrait
                            ? "morphe_niconico_comments_portrait_travel_duration_title"
                            : "morphe_niconico_comments_travel_duration_title"),
                    travelDuration,
                    foregroundColor,
                    value -> {
                        travelDuration.save(value);
                        NiconicoCommentsController.updateViewState();
                    }));
            contentLayout.addView(SheetBottomDialog.createSliderRow(context,
                    str(portrait
                            ? "morphe_niconico_comments_portrait_display_area_title"
                            : "morphe_niconico_comments_display_area_title"),
                    displayArea,
                    foregroundColor,
                    value -> {
                        displayArea.save(value);
                        NiconicoCommentsController.updateViewState();
                    }));
            contentLayout.addView(SheetBottomDialog.createSliderRow(context,
                    str("morphe_niconico_comments_max_length_title"),
                    Settings.NICONICO_COMMENTS_MAX_LENGTH,
                    foregroundColor,
                    Settings.NICONICO_COMMENTS_MAX_LENGTH::save));

            // Capped so every slider stays reachable in landscape, where height is limited.
            ScrollView scrollView = SheetBottomDialog.createCappedScrollView(context);
            scrollView.addView(contentLayout);
            mainLayout.addView(scrollView);

            SheetBottomDialog.SlideDialog dialog = SheetBottomDialog.createSlideDialog(
                    context, mainLayout, LegacyPlayerControlButton.fadeInDuration);
            PipDismissHelper.dismissOnPip(dialog);

            Button settingsButton = CustomDialog.createButton(context, dialog,
                    str("morphe_niconico_comments_all_settings"),
                    () -> {
                        // A second tap while the sheet slides away would open the settings twice.
                        if (quickSettingsRef.get() == dialog) {
                            quickSettingsRef = new WeakReference<>(null);
                            YouTubeActivityHook.openSettings(context, "morphe_niconico_comments_screen");
                        }
                    },
                    false, true);
            Button resetButton = CustomDialog.createButton(context, null,
                    str("morphe_settings_reset"),
                    () -> CustomDialog.create(context,
                            str("morphe_niconico_comments_reset_title"),
                            null, null, null,
                            () -> {
                                fontSize.resetToDefault();
                                Settings.NICONICO_COMMENTS_OPACITY.resetToDefault();
                                travelDuration.resetToDefault();
                                displayArea.resetToDefault();
                                Settings.NICONICO_COMMENTS_MAX_LENGTH.resetToDefault();
                                NiconicoCommentsController.updateViewState();
                                // Reopen so the sliders show the default values.
                                showQuickSettings(context);
                                dialog.dismiss();
                            },
                            () -> {}, null, null, true).first.show(),
                    false, false);
            contentLayout.addView(CustomDialog.createButtonRow(context, settingsButton, resetButton));

            quickSettingsRef = new WeakReference<>(dialog);
            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showQuickSettings failure", ex);
        }
    }
}
