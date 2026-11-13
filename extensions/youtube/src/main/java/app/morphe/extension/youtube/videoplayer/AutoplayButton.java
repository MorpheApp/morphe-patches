/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.shared.StringRef.str;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.AutoplayControlPatch;
import app.morphe.extension.youtube.patches.AutoplayRule;
import app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Autoplay control button: a text button in the player, as the speed and quality buttons.
 * Text: the mode (Auto, Single, Off); in a Mix in mode Auto the position, such as 3/10.
 * Tap = next mode. Tap and hold = a list of the modes and the Mix length.
 */
@SuppressWarnings("unused")
public final class AutoplayButton {
    private AutoplayButton() {
    }

    /** Mix lengths offered in the list. 0 = no limit. */
    private static final int[] MIX_LENGTHS = {5, 10, 20, 0};

    private static WeakReference<TextView> textRef = new WeakReference<>(null);

    /**
     * Injection point.
     */
    public static void initializeButton(View controlsView) {
        try {
            if (LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS ||
                    !Settings.AUTOPLAY_CONTROL.get()) {
                return;
            }

            textRef = new WeakReference<>(PlayerOverlayButton.addButtonWithTextOverlay(
                    controlsView,
                    view -> cycleMode(),
                    view -> {
                        showList(view.getContext());
                        return true;
                    }
            ));
            update();
        } catch (Exception ex) {
            Logger.printException(() -> "Autoplay button initializeButton failure", ex);
        }
    }

    private static void cycleMode() {
        AutoplayRule.Mode[] modes = AutoplayRule.Mode.values();
        AutoplayRule.Mode next = modes[(Settings.AUTOPLAY_MODE.get().ordinal() + 1) % modes.length];
        Logger.printDebug(() -> "Autoplay button: " + next);
        AutoplayControlPatch.setMode(next);
    }

    private static void showList(Context context) {
        try {
            AutoplayRule.Mode[] modes = AutoplayRule.Mode.values();
            final int modeCount = modes.length;
            String[] items = new String[modeCount + MIX_LENGTHS.length];
            for (int i = 0; i < modeCount; i++) {
                items[i] = modeLabel(modes[i]);
            }
            for (int i = 0; i < MIX_LENGTHS.length; i++) {
                items[modeCount + i] = MIX_LENGTHS[i] == 0
                        ? str("morphe_autoplay_mix_no_limit")
                        : str("morphe_autoplay_mix_length", MIX_LENGTHS[i]);
            }
            new AlertDialog.Builder(context)
                    .setTitle(str("morphe_autoplay_control_title"))
                    .setItems(items, (dialog, which) -> {
                        if (which < modeCount) {
                            AutoplayControlPatch.setMode(modes[which]);
                        } else {
                            Settings.AUTOPLAY_MIX_STOP_AFTER.save(MIX_LENGTHS[which - modeCount]);
                            AutoplayControlPatch.setMode(AutoplayRule.Mode.AUTO);
                        }
                    })
                    .show();
        } catch (Exception ex) {
            Logger.printException(() -> "Autoplay button list failure", ex);
        }
    }

    private static String modeLabel(AutoplayRule.Mode mode) {
        return switch (mode) {
            case AUTO -> str("morphe_autoplay_mode_entry_1");
            case SINGLE -> str("morphe_autoplay_mode_entry_2");
            case OFF -> str("morphe_autoplay_mode_entry_3");
        };
    }

    /**
     * @return The button text: the mode, or the Mix position in mode Auto.
     */
    private static String text() {
        AutoplayRule.Mode mode = Settings.AUTOPLAY_MODE.get();
        final int count = AutoplayControlPatch.mixCount();
        if (mode == AutoplayRule.Mode.AUTO && count > 0
                && AutoplayRule.isMix(VideoInformation.getPlaylistId())) {
            final int limit = AutoplayControlPatch.mixStopAfter();
            return limit > 0 ? count + "/" + limit : String.valueOf(count);
        }
        return modeLabel(mode);
    }

    /**
     * Updates the button text. Must be called on the main thread.
     */
    public static void update() {
        try {
            Utils.verifyOnMainThread();
            TextView view = textRef.get();
            if (view != null) {
                view.setText(text());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Autoplay button update failure", ex);
        }
    }
}
