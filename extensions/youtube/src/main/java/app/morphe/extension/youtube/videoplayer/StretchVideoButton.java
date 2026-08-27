/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.shared.StringRef.str;

import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch;
import app.morphe.extension.youtube.patches.StretchVideoPatch;
import app.morphe.extension.youtube.patches.StretchVideoPatch.VideoScaleMode;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class StretchVideoButton {

    @Nullable
    private static LegacyPlayerControlButton legacy;

    private static WeakReference<ImageView> overlayButtonRef = new WeakReference<>(null);

    /**
     * Injection point.
     */
    public static void initializeButton(View controlsView) {
        try {
            if (LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS
                    || !Settings.STRETCH_VIDEO_BUTTON.get()) {
                return;
            }

            overlayButtonRef = new WeakReference<>(PlayerOverlayButton.addButton(
                    controlsView,
                    getIconName(Settings.FULLSCREEN_VIDEO_SCALE.get()),
                    view -> cycleScaleMode(),
                    null
            ));
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
                    "morphe_stretch_video_button",
                    null,
                    getIconBaseName(Settings.FULLSCREEN_VIDEO_SCALE.get()),
                    Settings.STRETCH_VIDEO_BUTTON,
                    view -> cycleScaleMode(),
                    null
            );
        } catch (Exception ex) {
            Logger.printException(() -> "initializeLegacyButton failure", ex);
        }
    }

    private static void cycleScaleMode() {
        try {
            VideoScaleMode current = Settings.FULLSCREEN_VIDEO_SCALE.get();
            VideoScaleMode next = switch (current) {
                case DEFAULT -> VideoScaleMode.STRETCH;
                case STRETCH -> VideoScaleMode.ZOOM;
                case ZOOM -> VideoScaleMode.DEFAULT;
            };
            Settings.FULLSCREEN_VIDEO_SCALE.save(next);
            updateButtonIcon(next);
            StretchVideoPatch.applyScale();
            Utils.showToastShort(str(getToastKey(next)));
        } catch (Exception ex) {
            Logger.printException(() -> "cycleScaleMode failure", ex);
        }
    }

    private static void updateButtonIcon(VideoScaleMode mode) {
        Utils.verifyOnMainThread();

        ImageView overlayButton = overlayButtonRef.get();
        if (overlayButton != null) {
            overlayButton.setImageResource(ResourceUtils.getIdentifierOrThrow(
                    ResourceType.DRAWABLE,
                    getIconName(mode)
            ));
        }

        if (legacy != null) {
            legacy.setIcon(ResourceUtils.getIdentifierOrThrow(
                    ResourceType.DRAWABLE,
                    getIconName(mode)
            ));
        }
    }

    private static String getIconBaseName(VideoScaleMode mode) {
        return switch (mode) {
            case STRETCH -> "morphe_stretch_video_stretch";
            case ZOOM -> "morphe_stretch_video_zoom";
            case DEFAULT -> "morphe_stretch_video_fit";
        };
    }

    private static String getIconName(VideoScaleMode mode) {
        String base = getIconBaseName(mode);
        return LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS
                ? base
                : base + "_bold";
    }

    private static String getToastKey(VideoScaleMode mode) {
        return switch (mode) {
            case STRETCH -> "morphe_fullscreen_video_scale_toast_stretch";
            case ZOOM -> "morphe_fullscreen_video_scale_toast_zoom";
            case DEFAULT -> "morphe_fullscreen_video_scale_toast_default";
        };
    }
}
