/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1837
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.youtube.patches.utils.PlaylistPatch.QueueManager.OPEN_QUEUE;

import android.app.Activity;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.patches.utils.PlaylistPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class AddToQueuePatch {

    public static final List<String> queueButtonNames = List.of(
            "QUEUE_PLAY_NEXT",
            "QUEUE_PLAY_LAST"
    );
    public static final Drawable queueButtonDrawable = Utils.getContext()
            .getDrawable(OPEN_QUEUE.drawableId);

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

        try {
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
            // Reset index logic goes here if needed between UI clicks
            FlyoutUtils.resetCurrentButtonIndex();

            if (flyoutButtonClickLogic(buttonName)) {
                return;
            }

            if (original != null) {
                original.run();
            }
        };
    }

    public static boolean flyoutButtonClickLogic(String buttonName) {
        if (queueButtonNames.contains(buttonName)) {
            Logger.printDebug(() -> "Opening custom queue flyout with videoId: " + FlyoutUtils.getFlyoutVideoId());

            Activity activity = Utils.getActivity();
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                PlaylistPatch.prepareDialogBuilder(activity, FlyoutUtils.getFlyoutVideoId());
            }

            FlyoutUtils.dismissBottomSheetFlyout(); // Must dismiss after showing dialog.
            FlyoutUtils.dismissPopupWindowFlyout();
            return true;
        }

        return false;
    }
}
