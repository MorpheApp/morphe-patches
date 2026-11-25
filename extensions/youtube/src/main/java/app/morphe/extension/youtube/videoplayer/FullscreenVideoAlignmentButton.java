/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5307
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.FullscreenVideoScalePatch;
import app.morphe.extension.youtube.patches.FullscreenVideoScalePatch.VideoVerticalAlignment;
import app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public class FullscreenVideoAlignmentButton {

    @Nullable
    private static LegacyPlayerControlButton legacy;

    private static WeakReference<ImageView> overlayButtonRef = new WeakReference<>(null);

    /**
     * Injection point.
     */
    public static void initializeButton(View controlsView) {
        try {
            if (LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS
                    || !Settings.FULLSCREEN_VIDEO_ALIGNMENT_BUTTON.get()) {
                return;
            }

            ImageView added = PlayerOverlayButton.addButton(
                    controlsView,
                    PlayerIcons.name(Settings.FULLSCREEN_VIDEO_ALIGNMENT.get().iconBaseName),
                    FullscreenVideoAlignmentButton::isOverlayButtonEnabled,
                    view -> cycleAlignment(),
                    null
            );
            overlayButtonRef = new WeakReference<>(added);
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
                    "fullscreen_video_alignment_button",
                    null,
                    Settings.FULLSCREEN_VIDEO_ALIGNMENT.get().iconBaseName,
                    () -> isOverlayButtonEnabled()
                            ? LegacyPlayerControlButton.ButtonVisibility.ENABLED
                            : LegacyPlayerControlButton.ButtonVisibility.DISABLED,
                    view -> cycleAlignment(),
                    null
            );
        } catch (Exception ex) {
            Logger.printException(() -> "initializeLegacyButton failure", ex);
        }
    }

    private static boolean isOverlayButtonEnabled() {
        if (!Settings.FULLSCREEN_VIDEO_ALIGNMENT_BUTTON.get()
                || !Settings.FULLSCREEN_VIDEO_ALIGNMENT.isAvailable()) {
            return false;
        }
        PlayerType type = PlayerType.getCurrent();
        return (type == PlayerType.WATCH_WHILE_FULLSCREEN
                || type == PlayerType.WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN)
                && FullscreenVideoScalePatch.hasVerticalAlignmentSpace();
    }

    private static void cycleAlignment() {
        try {
            VideoVerticalAlignment current = Settings.FULLSCREEN_VIDEO_ALIGNMENT.get();
            VideoVerticalAlignment next = switch (current) {
                case TOP -> VideoVerticalAlignment.CENTER;
                case CENTER -> VideoVerticalAlignment.BOTTOM;
                case BOTTOM -> VideoVerticalAlignment.TOP;
            };
            Settings.FULLSCREEN_VIDEO_ALIGNMENT.save(next);
            updateButtonIcon(next);
            FullscreenVideoScalePatch.applyScale();
        } catch (Exception ex) {
            Logger.printException(() -> "cycleAlignment failure", ex);
        }
    }

    private static void updateButtonIcon(VideoVerticalAlignment alignment) {
        Utils.verifyOnMainThread();

        final int icon = PlayerIcons.id(alignment.iconBaseName);

        ImageView overlayButton = overlayButtonRef.get();
        if (overlayButton != null) {
            overlayButton.setImageResource(icon);
        }

        if (legacy != null) {
            legacy.setIcon(icon);
        }
    }
}
