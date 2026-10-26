/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.shared.StringRef.str;

import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.playback.livestream.DanmakuSettingsDialog;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Player top-control button for danmaku, following the Reload video
 * button pattern. Because it is a legacy player control button, it
 * fades in and out together with the other player controls: it is
 * only visible while the controls are shown after touching the
 * player. A tap opens the danmaku settings dialog; a long press
 * toggles danmaku on/off with a toast.
 */
@SuppressWarnings("unused")
public class DanmakuButton {

    static {
        if (Settings.LIVE_CHAT_DANMAKU_BUTTON.get()) {
            LegacyPlayerControlButton.incrementUpperButtonCount();
        }
    }

    /**
     * Injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_live_chat_danmaku_button",
                    null,
                    "morphe_live_chat_danmaku_button",
                    Settings.LIVE_CHAT_DANMAKU_BUTTON,
                    v -> DanmakuSettingsDialog.show(v.getContext()),
                    v -> {
                        boolean newState = !Settings.LIVE_CHAT_DANMAKU.get();
                        Settings.LIVE_CHAT_DANMAKU.save(newState);
                        Utils.showToastShort(str(newState
                                ? "morphe_live_chat_danmaku_button_toast_on"
                                : "morphe_live_chat_danmaku_button_toast_off"));
                        return true;
                    }
            );
        } catch (Exception ex) {
            Logger.printException(() -> "Danmaku button initialize failure", ex);
        }
    }
}
