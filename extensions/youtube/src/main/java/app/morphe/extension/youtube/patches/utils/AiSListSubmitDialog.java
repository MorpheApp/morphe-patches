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
import android.graphics.drawable.Drawable;
import android.util.Pair;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.BulletPointPreference;
import app.morphe.extension.shared.spoof.SpoofAppVersionPatch;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.youtube.patches.VersionCheckPatch;
import app.morphe.extension.youtube.patches.components.AiSListFilter;
import app.morphe.extension.youtube.patches.utils.requests.AiSListRequester;
import app.morphe.extension.youtube.patches.utils.requests.AiSListSubmitRequest;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Confirms and sends an AiSList submission for the channel that published a video.
 */
public final class AiSListSubmitDialog {

    private static final boolean USE_EXPERIMENTAL_ICONS = VersionCheckPatch.IS_20_31_OR_GREATER
            && !SpoofAppVersionPatch.isSpoofingToLessThan("20.31.00");

    private static final Drawable aiSListSubmitDrawable = ResourceUtils.getDrawable(
            USE_EXPERIMENTAL_ICONS
                    ? "yt_outline_experimental_flag_vd_theme_24"
                    : "yt_outline_flag_black_24"
    );

    private static final String aiSListSubmitButtonName = str("morphe_aislist_submit_title");

    private AiSListSubmitDialog() {
    }

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    public static int addFlyoutButton(Object flyoutPanel, int index, String videoId) {
        if (!isPatchIncluded() || !Settings.AISLIST_SUBMIT_FLYOUT_MENU.get() || videoId.isEmpty()) {
            return index;
        }

        return FlyoutUtils.addFlyoutButton(
                flyoutPanel,
                aiSListSubmitDrawable,
                aiSListSubmitButtonName,
                v -> {
                    show(videoId);
                    FlyoutUtils.dismissFlyout();
                },
                index
        );
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
                if (handle == null) {
                    Utils.showToastShort(str("morphe_aislist_submit_failed_channel"));
                    return;
                }

                AiSListRequester.fetchIfStale();
                AiSListFilter.ListedIn listedIn = AiSListFilter.listContainingHandle(handle);
                if (listedIn != null) {
                    // Each list names itself in a whole sentence, because a language that
                    // inflects the list name cannot build one from a substituted noun.
                    Utils.showToastShort(str(listedIn == AiSListFilter.ListedIn.BLOCKLIST
                            ? "morphe_aislist_submit_already_blocklisted"
                            : "morphe_aislist_submit_already_warnlisted"));
                    return;
                }

                Utils.runOnMainThread(() -> showConfirmDialog(videoId, handle, username));
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
                BulletPointPreference.formatIntoBulletPoints(
                        str("morphe_aislist_submit_dialog_message", handle)),
                null,                                    // No EditText.
                str("morphe_aislist_submit_dialog_confirm"), // OK button text.
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
