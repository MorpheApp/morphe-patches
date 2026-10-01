/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.content.Intent;
import android.text.TextUtils;
import android.util.Pair;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.components.ChannelPageFlyoutFilter;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

@SuppressWarnings("unused")
public final class OpenSystemShareSheetPatch {

    public interface ActionSheetControllerInterface {
        // Method is added during patching.
        void patch_dismissActionSheet();
    }

    private static WeakReference<ActionSheetControllerInterface> actionSheetControllerRef = new WeakReference<>(null);

    /**
     * Injection point.
     */
    public static void setActionSheetController(ActionSheetControllerInterface actionSheetController) {
        actionSheetControllerRef = new WeakReference<>(actionSheetController);
    }

    /**
     * Dismisses the empty action sheet that is opened before the share endpoint is resolved.
     */
    private static void dismissActionSheet() {
        // Post to the main thread, so the action sheet opened in the same call is already shown.
        Utils.runOnMainThread(() -> {
            ActionSheetControllerInterface actionSheetController = actionSheetControllerRef.get();
            if (actionSheetController != null) {
                actionSheetController.patch_dismissActionSheet();
            }
        });
    }

    /**
     * Injection point.
     *
     * @return If the in-app share sheet must not be opened.
     */
    public static boolean openSystemShareSheet() {
        if (!Settings.OPEN_SYSTEM_SHARE_SHEET.get()) {
            return false;
        }

        final String longURLPrefix = "https://www.youtube.com";
        final Pair<String, String> videoURLPrefix = !Settings.REPLACE_LINKS_WITH_SHORTENER.get()
                ? new Pair<>(longURLPrefix + "/watch?v=", "&")
                : new Pair<>("https://youtu.be/", "?");

        final String intentUrl;
        // Make sure to check channelId at the end, since it is never reset.
        if (!FlyoutUtils.getFlyoutPlaylistId().isEmpty()) {
            intentUrl = longURLPrefix + "/playlist?list=" + FlyoutUtils.getFlyoutPlaylistId();
        } else if (!FlyoutUtils.getFlyoutVideoId().isEmpty()) {
            intentUrl = videoURLPrefix.first + FlyoutUtils.getFlyoutVideoId();
        } else if (!FlyoutUtils.getFlyoutCommentId().isEmpty()) {
            intentUrl =
                    videoURLPrefix.first +
                    VideoInformation.getVideoId() +
                    videoURLPrefix.second +
                    "lc=" +
                    FlyoutUtils.getFlyoutCommentId();

            FlyoutUtils.resetFlyoutCommentId();
        } else if (PlayerType.getCurrent().isMaximizedOrFullscreen() ||
                ShortsPlayerState.isOpen()) {
            intentUrl = videoURLPrefix.first + VideoInformation.getVideoId();
        } else if (!ChannelPageFlyoutFilter.getFlyoutChannelId().isEmpty()) {
            intentUrl = longURLPrefix + "/channel/" + ChannelPageFlyoutFilter.getFlyoutChannelId();
        } else {
            intentUrl = "";
        }

        if (!TextUtils.isEmpty(intentUrl)) {
            final Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, intentUrl);
            final Intent chooserIntent = Intent.createChooser(shareIntent, "");
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);

            try {
                Utils.getContext().startActivity(chooserIntent);
            } catch (Exception ex) {
                Logger.printException(() -> "Can not open System Share panel: " + intentUrl, ex);
            }
        } else {
            Logger.printDebug(() -> "Can not open System Share panel: no URL found");
        }

        // The in-app share sheet is never opened.
        dismissActionSheet();
        return true;
    }
}
