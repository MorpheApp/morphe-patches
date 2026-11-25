/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5197
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.videoplayer;

import android.annotation.SuppressLint;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch;
import app.morphe.extension.youtube.patches.ghostplay.GhostHistoryDialog;
import app.morphe.extension.youtube.patches.ghostplay.GhostPlayPatch;
import app.morphe.extension.youtube.patches.ghostplay.GhostTheme;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Player overlay button for Ghost mode (supports both modern and legacy player controls).
 * Short tap: toggles Ghost mode.
 * Long tap: opens the Ghost mode quick menu (timer, channel rules, local history).
 */
@SuppressWarnings("unused")
public final class GhostPlayButton {

    @Nullable
    private static LegacyPlayerControlButton legacy;

    private static WeakReference<ImageView> overlayButtonRef = new WeakReference<>(null);

    private GhostPlayButton() {
    }

    /**
     * Injection point (modern player overlay button).
     */
    @SuppressLint("ClickableViewAccessibility")
    public static void initializeButton(View controlsView) {
        try {
            if (LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS
                    || !GhostPlayPatch.isEnabled()
                    || !Settings.GHOST_PLAY_PLAYER_BUTTON.get()) {
                return;
            }

            ImageView button = PlayerOverlayButton.addButton(
                    controlsView,
                    getIconName(),
                    view -> handleShortClick(),
                    view -> {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        GhostHistoryDialog.showQuickMenu(view.getContext());
                        return true;
                    }
            );
            if (button != null) {
                button.setLongClickable(true);
                // Prevent YouTube's player gesture detector (e.g., 2x hold speed) from intercepting long-press.
                button.setOnTouchListener((v, event) -> {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        ViewParent parent = v.getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                    }
                    return false;
                });
            }
            overlayButtonRef = new WeakReference<>(button);
            updateButtonIcon(false);
        } catch (Exception ex) {
            Logger.printException(() -> "GhostPlayButton.initializeButton failure", ex);
        }
    }

    /**
     * Injection point (legacy player overlay button).
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            if (!LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS) {
                return;
            }
            if (!GhostPlayPatch.isEnabled()) {
                // The button is part of the layout of the legacy controls, so it shows unless it's hidden.
                Utils.getChildViewByResourceName(controlsView, "morphe_ghost_play_button").setVisibility(View.GONE);
                return;
            }

            legacy = new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_ghost_play_button",
                    null,
                    getIconName(),
                    Settings.GHOST_PLAY_PLAYER_BUTTON,
                    view -> handleShortClick(),
                    view -> {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        GhostHistoryDialog.showQuickMenu(view.getContext());
                        return true;
                    }
            );
            updateButtonIcon(false);
        } catch (Exception ex) {
            Logger.printException(() -> "GhostPlayButton.initializeLegacyButton failure", ex);
        }
    }

    public static void refreshButtonState() {
        Utils.runOnMainThread(() -> updateButtonIcon(false));
    }

    private static void handleShortClick() {
        try {
            GhostPlayPatch.toggleFromPlayerButton();
            updateButtonIcon(true);
        } catch (Exception ex) {
            Logger.printException(() -> "GhostPlayButton.handleShortClick failure", ex);
        }
    }

    private static void updateButtonIcon(boolean animate) {
        try {
            final int icon = PlayerIcons.id(getIconBaseName());

            ImageView overlayButton = overlayButtonRef.get();
            if (overlayButton != null) {
                overlayButton.setImageResource(icon);
                overlayButton.invalidate();
                if (animate) {
                    GhostTheme.playCuteGhostBounce(overlayButton, GhostPlayPatch.isCurrentVideoGhost());
                    Drawable drawable = overlayButton.getDrawable();
                    if (drawable instanceof DrawableWrapper wrapper) {
                        drawable = wrapper.getDrawable();
                    }
                    if (drawable instanceof Animatable animatable) {
                        animatable.start();
                    }
                }
            }

            if (legacy != null) {
                legacy.setIcon(icon);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "GhostPlayButton.updateButtonIcon failure", ex);
        }
    }

    private static String getIconName() {
        return PlayerIcons.name(getIconBaseName());
    }

    private static String getIconBaseName() {
        return GhostPlayPatch.isCurrentVideoGhost()
                ? "morphe_ghost_play_button_on"
                : "morphe_ghost_play_button_off";
    }
}
