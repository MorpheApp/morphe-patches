/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2763
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.util.Pair;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.youtube.patches.utils.requests.AiSListSubmitRequest;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Confirms and sends an AiSList submission for the channel that published a video.
 */
public final class AiSListSubmitDialog {

    private AiSListSubmitDialog() {
    }

    /**
     * Resolves the channel of the video, then asks for confirmation before submitting.
     */
    public static void show(String videoId) {
        try {
            String username = Settings.AISLIST_SUBMIT_USERNAME.get().trim();
            if (username.isEmpty()) {
                // The name appears on the public submitter leaderboard, so it is never
                // filled in silently and the user sets it in the settings instead.
                Utils.showToastLong(str("morphe_aislist_submit_no_username"));
                return;
            }

            Utils.showToastShort(str("morphe_aislist_submit_resolving"));

            Utils.runOnBackgroundThread(() -> {
                String handle = AiSListSubmitRequest.fetchChannelHandle(videoId);

                Utils.runOnMainThread(() -> {
                    if (handle == null) {
                        Utils.showToastShort(str("morphe_aislist_submit_failed_channel"));
                        return;
                    }
                    showConfirmDialog(videoId, handle, username);
                });
            });
        } catch (Exception ex) {
            Logger.printException(() -> "show failure", ex);
        }
    }

    private static void showConfirmDialog(String videoId, String handle, String username) {
        Activity activity = Utils.getActivity();
        if (activity == null) {
            return;
        }

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                activity,
                str("morphe_aislist_submit_title"),
                str("morphe_aislist_submit_dialog_message", handle),
                null,                                            // No EditText.
                str("morphe_aislist_submit_dialog_confirm"),     // OK button text.
                () -> submit(videoId, handle, username),         // OK button action.
                () -> {},                                        // Cancel button action (dismiss only).
                null,                                            // No Neutral button text.
                null,                                            // No Neutral button action.
                false                                            // Do not dismiss dialog when onNeutralClick.
        );

        Utils.showDialog(activity, dialogPair.first, false, null);
    }

    private static void submit(String videoId, String handle, String username) {
        Utils.runOnBackgroundThread(() -> {
            String messageKey = AiSListSubmitRequest.submit(
                    handle, "https://youtu.be/" + videoId, username);

            Utils.showToastShort(str(messageKey));
        });
    }
}
