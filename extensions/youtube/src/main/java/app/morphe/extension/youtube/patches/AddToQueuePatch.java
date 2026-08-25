/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1837
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.patches.utils.PlaylistPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class AddToQueuePatch {

    public static final List<String> queueButtonOriginalNames = List.of(
            "QUEUE_PLAY_NEXT",
            "QUEUE_PLAY_LAST"
    );

    /**
     * Injection point.
     */
    public static Runnable replaceButtonRunnable(Runnable original) {
        if (!Settings.QUEUE_OVERRIDE_FLYOUT_MENU.get()) {
            return original;
        }

        if (FlyoutUtils.getFlyoutVideoId().isEmpty()) {
            Logger.printDebug(() -> "Cannot replace on item click, flyoutVideoId is empty");
            return original;
        }

        return getNewRunnable(original, FlyoutUtils.getCurrentButtonName());
    }

    /**
     * Injection point.
     * 21.04 and older.
     */
    public static boolean replaceOnItemClick(Object object) {
        try {
            if (!Settings.QUEUE_OVERRIDE_FLYOUT_MENU.get()) {
                return false;
            }

            if (FlyoutUtils.getFlyoutVideoId().isEmpty()) {
                Logger.printDebug(() -> "Cannot replace on item click, flyoutVideoId is empty");
                return false;
            }

            int buttonIndex = -1;
            String buttonName = "";

            if (object instanceof Integer index) {
                buttonIndex = index;
            } else if (object instanceof String name) {
                buttonName = name;
            }

            if (!FlyoutUtils.getVisibleFlyoutButtons().isEmpty()) {
                if (buttonIndex >= 0) {
                    return flyoutButtonClickLogic(FlyoutUtils.getVisibleFlyoutButtons().get(buttonIndex).first);
                } else if (!buttonName.isEmpty()) {
                    return flyoutButtonClickLogic(buttonName);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "replaceOnItemClick failure", ex);
        }
        return false;
    }

    private static Runnable getNewRunnable(@Nullable Runnable original, String buttonName) {
        return () -> {
            try {
                // Reset index logic goes here if needed between UI clicks
                FlyoutUtils.resetCurrentButtonIndex();

                if (flyoutButtonClickLogic(buttonName)) {
                    return;
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Add to queue getNewRunnable failure", ex);
            }
            if (original != null) {
                original.run();
            }
        };
    }

    public static boolean flyoutButtonClickLogic(String buttonName) {
        try {
            if (queueButtonOriginalNames.contains(buttonName)) {
                String flyoutVideoId = FlyoutUtils.getFlyoutVideoId();
                Logger.printDebug(() -> "Opening custom queue flyout with videoId: " + flyoutVideoId);

                Activity activity = Utils.getActivity();
                if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                    PlaylistPatch.prepareDialogBuilder(activity, flyoutVideoId);
                } else {
                    Logger.printException(() -> "Could not open queue flyout, activity is not available");
                }

                FlyoutUtils.dismissFlyout(); // Must dismiss after showing dialog.
                return true;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "flyoutButtonClickLogic failure: " + buttonName, ex);
        }

        return false;
    }
}
