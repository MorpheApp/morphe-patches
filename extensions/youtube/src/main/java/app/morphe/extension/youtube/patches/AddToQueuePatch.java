/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1837
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.util.Pair;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.patches.utils.LocalQueueSheet;
import app.morphe.extension.youtube.patches.utils.PlaylistPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class AddToQueuePatch {

    public static final List<String> queueButtonOriginalNames = List.of(
            "QUEUE_PLAY_NEXT",
            "QUEUE_PLAY_LAST"
    );

    private static final Drawable queueButtonDrawable = Utils.getContext()
            .getDrawable(PlaylistPatch.QueueManager.OPEN_QUEUE.drawableId);
    private static final String queueButtonName = str("morphe_queue_flyout_title");

    private static final int SECONDARY_CONTAINER_ID =
            ResourceUtils.getIdentifier(ResourceType.ID, "list_item_secondary_container");

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    public static int addFlyoutButton(Object flyoutPanel, int index, String videoId) {
        if (!isPatchIncluded()) {
            return index;
        }

        if (LocalQueuePatch.isEnabled()) {
            return addLocalQueueButtons(flyoutPanel, index, videoId);
        }

        if (!Settings.QUEUE_ADD_FLYOUT_MENU.get() ||
                !FlyoutUtils.getFlyoutPlaylistId().isEmpty() ||
                videoId.isEmpty()) {
            return index;
        }

        return FlyoutUtils.addFlyoutButton(
                flyoutPanel,
                queueButtonDrawable,
                queueButtonName,
                v -> flyoutButtonClickLogic(
                        queueButtonOriginalNames.get(0),
                        videoId
                ),
                index
        );
    }

    public static void onListBound(ViewGroup itemList) {
        if (!isPatchIncluded() || !isFlyoutOverrideEnabled()
                || SECONDARY_CONTAINER_ID == 0) {
            return;
        }

        int itemIndex = -1;
        for (var button : FlyoutUtils.getVisibleFlyoutButtons()) {
            if (queueButtonOriginalNames.contains(button.first)) {
                itemIndex = button.second - 1;
                break;
            }
        }

        if (itemIndex < 0 || itemIndex >= itemList.getChildCount()) {
            return;
        }

        var badge = itemList.getChildAt(itemIndex)
                .findViewById(SECONDARY_CONTAINER_ID);

        if (badge != null && badge.getVisibility() != android.view.View.GONE) {
            Logger.printDebug(() -> "Hiding the menu item secondary icon");
            badge.setVisibility(android.view.View.GONE);
        }
    }

    private static boolean isFlyoutOverrideEnabled() {
        return LocalQueuePatch.isEnabled() || Settings.QUEUE_OVERRIDE_FLYOUT_MENU.get();
    }

    private static boolean hasFlyoutButton(String buttonName) {
        for (Pair<String, Integer> button : FlyoutUtils.getVisibleFlyoutButtons()) {
            if (button.first.equals(buttonName)) {
                return true;
            }
        }
        return false;
    }

    private static int addLocalQueueButtons(Object flyoutPanel, int index, String videoId) {
        final boolean feedFlyout = !FlyoutUtils.getFlyoutVideoId().isEmpty();
        if (feedFlyout && FlyoutUtils.getFlyoutPlaylistId().isEmpty()
                && !hasFlyoutButton(queueButtonOriginalNames.get(0))) {
            index = FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    queueButtonDrawable,
                    str("morphe_local_queue_play_next"),
                    v -> flyoutButtonClickLogic(queueButtonOriginalNames.get(0), videoId),
                    index
            );
        }

        final int size = LocalQueuePatch.size();
        if (!feedFlyout || size > 0) {
            index = FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    queueButtonDrawable,
                    str("morphe_local_queue_open", size),
                    v -> {
                        FlyoutUtils.dismissFlyout();
                        Activity activity = Utils.getActivity();
                        if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                            LocalQueueSheet.show(activity);
                        } else {
                            Logger.printException(() -> "Could not open queue sheet, activity is not available");
                        }
                    },
                    index
            );
        }

        return index;
    }

    /**
     * Injection point.
     */
    public static Runnable replaceButtonRunnable(Runnable original) {
        if (!isFlyoutOverrideEnabled()) {
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
            if (!isFlyoutOverrideEnabled()) {
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
                    return flyoutButtonClickLogic(
                            FlyoutUtils.getVisibleFlyoutButtons().get(buttonIndex).first,
                            FlyoutUtils.getFlyoutVideoId()
                    );
                } else if (!buttonName.isEmpty()) {
                    return flyoutButtonClickLogic(buttonName, FlyoutUtils.getFlyoutVideoId());
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

                if (flyoutButtonClickLogic(buttonName, FlyoutUtils.getFlyoutVideoId())) {
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

    public static boolean flyoutButtonClickLogic(String buttonName, String videoId) {
        try {
            if (queueButtonOriginalNames.contains(buttonName)) {
                if (LocalQueuePatch.isEnabled()) {
                    LocalQueuePatch.add(videoId, queueButtonOriginalNames.get(0).equals(buttonName));
                    FlyoutUtils.dismissFlyout();
                    return true;
                }

                Logger.printDebug(() -> "Opening custom queue flyout with videoId: " + videoId);

                Activity activity = Utils.getActivity();
                if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                    PlaylistPatch.prepareDialogBuilder(activity, videoId);
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
