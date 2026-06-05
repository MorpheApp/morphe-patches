/*
 * Copyright (C) 2026 anddea
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Original author(s):
 * - Jav1x (https://github.com/Jav1x)
 *
 * Ported to morphe-patches: https://github.com/MorpheApp/morphe-patches
 * Modified by: Jav1x (https://github.com/Jav1x)
 *
 * Licensed under the GNU General Public License v3.0.
 *
 * ------------------------------------------------------------------------
 * GPLv3 Section 7 – Attribution Notice
 * ------------------------------------------------------------------------
 *
 * This file contains substantial original work by the author(s) listed above.
 *
 * In accordance with Section 7 of the GNU General Public License v3.0,
 * the following additional terms apply to this file:
 *
 * 1. Attribution (Section 7(b)): This specific copyright notice and the
 *    list of original authors above must be preserved in any copy or
 *    derivative work. You may add your own copyright notice below it,
 *    but you may not remove the original one.
 *
 * 2. Origin (Section 7(c)): Modified versions must be clearly marked as
 *    such (e.g., by adding a "Modified by" line or a new copyright notice).
 *    They must not be misrepresented as the original work.
 *
 * ------------------------------------------------------------------------
 * Version Control Acknowledgement (Non-binding Request)
 * ------------------------------------------------------------------------
 *
 * While not a legal requirement of the GPLv3, the original author(s)
 * respectfully request that ports or substantial modifications retain
 * historical authorship credit in version control systems (e.g., Git),
 * listing original author(s) appropriately and modifiers as committers
 * or co-authors.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS;

import android.view.View;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.voiceovertranslation.VoiceOverTranslationBottomSheet;
import app.morphe.extension.youtube.patches.voiceovertranslation.VoiceOverTranslationPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class VoiceOverTranslationButton {
    @Nullable
    private static LegacyPlayerControlButton legacy;

    public static void initializeButton(View controlsView) {
        try {
            if (RESTORE_OLD_PLAYER_BUTTONS || !Settings.VOT_ENABLED.get()) return;
            VoiceOverTranslationPatch.setOnTranslationStateChangeCallback(
                    VoiceOverTranslationButton::refreshActivatedState);
            PlayerOverlayButton.addButton(controlsView, "morphe_yt_vot",
                    view -> {
                        VoiceOverTranslationPatch.toggleTranslation();
                        refreshActivatedState();
                    },
                    view -> {
                        VoiceOverTranslationBottomSheet.show(view.getContext());
                        return true;
                    });
        } catch (Exception ex) {
            Logger.printException(() -> "VoiceOverTranslationButton initializeButton failure", ex);
        }
    }

    public static void initializeLegacyButton(View controlsView) {
        try {
            if (!RESTORE_OLD_PLAYER_BUTTONS) return;
            VoiceOverTranslationPatch.setOnTranslationStateChangeCallback(
                    VoiceOverTranslationButton::refreshActivatedState);
            legacy = new LegacyPlayerControlButton(controlsView, "morphe_vot_button", "VOT",
                    "morphe_yt_vot", Settings.VOT_ENABLED::get,
                    view -> {
                        VoiceOverTranslationPatch.toggleTranslation();
                        refreshActivatedState();
                    },
                    view -> {
                        VoiceOverTranslationBottomSheet.show(view.getContext());
                        return true;
                    });
        } catch (Exception ex) {
            Logger.printException(() -> "VoiceOverTranslationButton initializeLegacyButton failure", ex);
        }
    }

    private static void refreshActivatedState() {
        Utils.verifyOnMainThread();
        try {
            if (legacy != null) {
                boolean active = VoiceOverTranslationPatch.isTranslationActive();
                int drawableId = ResourceUtils.getIdentifierOrThrow(ResourceType.DRAWABLE,
                        active ? "morphe_yt_vot_activated" : "morphe_yt_vot");
                legacy.setIcon(drawableId);
            }
            // Note: PlayerOverlayButton does not currently support runtime icon updates.
            // The overlay button shows the default VOT icon and the bottom sheet shows
            // the current translation status.
        } catch (Exception ex) {
            Logger.printException(() -> "refreshActivatedState failure", ex);
        }
    }

    public static void setVisibilityNegatedImmediate() { if (legacy != null) legacy.setVisibilityNegatedImmediate(); }
    public static void setVisibilityImmediate(boolean visible) { if (legacy != null) legacy.setVisibilityImmediate(visible); }
    public static void setVisibility(boolean visible, boolean animated) { if (legacy != null) legacy.setVisibility(visible, animated); }
}
