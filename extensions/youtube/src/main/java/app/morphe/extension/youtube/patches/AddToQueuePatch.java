/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1837
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.youtube.patches.utils.FlyoutUtils.getFlyoutVideoId;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
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

    private static final Drawable queueButtonDrawable = Utils.getContext()
            .getDrawable(PlaylistPatch.QueueManager.OPEN_QUEUE.drawableId);
    private static final String queueButtonName = str("morphe_queue_flyout_title");

    private static final int SECONDARY_CONTAINER_ID =
            ResourceUtils.getIdentifier(ResourceType.ID, "list_item_secondary_container");

    private static final FlyoutUtils.FlyoutButtonProvider FLYOUT_BUTTON_PROVIDER =
            new FlyoutUtils.FlyoutButtonProvider() {
                @Override
                public int addQueueButton(Object flyoutPanel, int index, String videoId) {
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

                @Override
                public void onListBound(ViewGroup itemList) {
                    if (!Settings.QUEUE_OVERRIDE_FLYOUT_MENU.get()
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
            };

    public static void registerFlyoutProvider() {
        FlyoutUtils.setFlyoutButtonProvider(FLYOUT_BUTTON_PROVIDER);
    }

    /**
     * Injection point.
     */
    public static Runnable replaceButtonRunnable(Runnable original) {
        if (!Settings.QUEUE_OVERRIDE_FLYOUT_MENU.get()) {
            return original;
        }

        if (getFlyoutVideoId().isEmpty()) {
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

            if (getFlyoutVideoId().isEmpty()) {
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
                            getFlyoutVideoId()
                    );
                } else if (!buttonName.isEmpty()) {
                    return flyoutButtonClickLogic(buttonName, getFlyoutVideoId());
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

                if (flyoutButtonClickLogic(buttonName, getFlyoutVideoId())) {
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
