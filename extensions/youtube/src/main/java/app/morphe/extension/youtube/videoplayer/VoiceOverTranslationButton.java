/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.videoplayer;

import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.ToggleButton;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch;
import app.morphe.extension.youtube.patches.voiceovertranslation.VoiceOverTranslationPatch;
import app.morphe.extension.youtube.patches.voiceovertranslation.VotBottomSheet;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class VoiceOverTranslationButton {

    @Nullable
    private static LegacyPlayerControlButton legacy;

    @Nullable
    private static WeakReference<ImageView> overlayButtonRef;

    /** Injection point. */
    public static void initializeButton(View controlsView) {
        try {
            if (LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS || !Settings.VOT_ENABLED.get()) return;

            VoiceOverTranslationPatch.addOnTranslationStateChangeCallback(
                    VoiceOverTranslationButton::refreshActivatedState);

            ImageView button = PlayerOverlayButton.addButton(
                    controlsView,
                    "morphe_yt_vot_bold",
                    view -> {
                        VoiceOverTranslationPatch.toggleTranslation();
                        refreshActivatedState();
                    },
                    view -> {
                        VotBottomSheet.show(view.getContext());
                        return true;
                    });
            overlayButtonRef = button != null ? new WeakReference<>(button) : null;
            if (button != null) {
                button.setContentDescription(ResourceUtils.getString("morphe_vot_enabled_title"));
                setToggleAccessibilityDelegate(button);
            }
            refreshActivatedState();
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    /** Injection point. */
    public static void initializeLegacyButton(View controlsView) {
        try {
            if (!LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS) return;

            VoiceOverTranslationPatch.addOnTranslationStateChangeCallback(
                    VoiceOverTranslationButton::refreshActivatedState);

            legacy = new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_vot_button",
                    null,
                    "morphe_yt_vot",
                    Settings.VOT_ENABLED,
                    view -> {
                        VoiceOverTranslationPatch.toggleTranslation();
                        refreshActivatedState();
                    },
                    view -> {
                        VotBottomSheet.show(view.getContext());
                        return true;
                    });
            View legacyButton = Utils.getChildViewByResourceName(controlsView, "morphe_vot_button");
            if (legacyButton != null) {
                setToggleAccessibilityDelegate(legacyButton);
            }
            refreshActivatedState();
        } catch (Exception ex) {
            Logger.printException(() -> "initializeLegacyButton failure", ex);
        }
    }

    /**
     * Exposes the button to accessibility services as a toggle,
     * so screen readers announce whether translation is on or off.
     */
    private static void setToggleAccessibilityDelegate(View button) {
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(ToggleButton.class.getName());
                info.setCheckable(true);
                info.setChecked(VoiceOverTranslationPatch.isSessionEnabled());
            }
        });
    }

    private static void refreshActivatedState() {
        Utils.verifyOnMainThread();
        try {
            final int alpha = VoiceOverTranslationPatch.isSessionEnabled() ? 255 : 128;
            WeakReference<ImageView> ref = overlayButtonRef;
            ImageView overlay = ref != null ? ref.get() : null;
            if (overlay != null) {
                overlay.setImageAlpha(alpha);
            }
            LegacyPlayerControlButton leg = legacy;
            if (leg != null) {
                leg.setImageAlpha(alpha);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "refreshActivatedState failure", ex);
        }
    }
}
