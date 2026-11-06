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
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.patches.queue.NativePlaylistQueuePatch;
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

    public enum QueueType {
        /** No queue features are added. */
        DEFAULT,
        /** YouTube's own playlist interface, provided by the Native playlist queue patch. */
        NATIVE,
        /** The Morphe queue, stored as a playlist on the YouTube account. */
        MORPHE,
        /** The Premium-style queue, stored on the device. */
        LOCAL
    }

    public static final class MorpheQueueAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.QUEUE_TYPE.get() == QueueType.MORPHE;
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.QUEUE_TYPE);
        }
    }

    public static final class NotLocalQueueAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.QUEUE_TYPE.get() != QueueType.LOCAL;
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.QUEUE_TYPE);
        }
    }

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * @return If the native queue is selected. It handles the stock queue buttons.
     */
    public static boolean isNativeQueueEnabled() {
        return Settings.QUEUE_TYPE.get() == QueueType.NATIVE;
    }

    /**
     * @return If the Morphe queue or the Premium-style queue is selected.
     */
    public static boolean isCustomQueueEnabled() {
        if (!isPatchIncluded()) {
            return false;
        }

        QueueType type = Settings.QUEUE_TYPE.get();
        return type == QueueType.MORPHE || type == QueueType.LOCAL;
    }

    public static int addFlyoutButton(Object flyoutPanel, int index, String videoId) {
        if (!isCustomQueueEnabled()) {
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
        final boolean nativeQueue = isNativeQueueEnabled();
        if (!isCustomQueueEnabled() && !nativeQueue) {
            return;
        }

        final String videoId = FlyoutUtils.getFlyoutVideoId();
        for (var button : FlyoutUtils.getVisibleFlyoutButtons()) {
            final String buttonName = button.first;
            if (!queueButtonOriginalNames.contains(buttonName)) {
                continue;
            }

            // The row is found by its text, as the index of the button does not always match the rows,
            // such as when a row of a closed menu was counted. The queue click would then be set on
            // another row, such as Save to playlist.
            final String buttonText = FlyoutUtils.getVisibleFlyoutButtonText(buttonName);
            View item = null;
            if (buttonText != null) {
                for (int i = 0, count = itemList.getChildCount(); i < count; i++) {
                    if (itemList.getChildAt(i) instanceof ViewGroup row && Utils.getChildView(row, true, view ->
                            view instanceof TextView rowText && buttonText.contentEquals(rowText.getText())) != null) {
                        item = row;
                        break;
                    }
                }
            } else {
                final int itemIndex = button.second - 1;
                if (itemIndex >= 0 && itemIndex < itemList.getChildCount()) {
                    item = itemList.getChildAt(itemIndex);
                }
            }
            if (item == null) {
                continue;
            }

            if (nativeQueue && queueButtonOriginalNames.get(1).equals(buttonName)
                    && !NativePlaylistQueuePatch.canShowFlyoutLast()) {
                // The queue may have been cleared since the native model was prepared.
                item.setVisibility(View.GONE);
                continue;
            }
            var badge = SECONDARY_CONTAINER_ID == 0 ? null : item.findViewById(SECONDARY_CONTAINER_ID);
            if (badge != null && badge.getVisibility() != View.GONE) {
                Logger.printDebug(() -> "Hiding the menu item secondary icon");
                badge.setVisibility(View.GONE);
            }

            if (LocalQueuePatch.isEnabled()) {
                Logger.printDebug(() -> "Queue row " + buttonName + " video id: '" + videoId + "'");
                item.setOnClickListener(v -> {
                    if (videoId.isEmpty()) {
                        Utils.showSnackbarShort(str(FlyoutUtils.getFlyoutPlaylistId().isEmpty()
                                ? "morphe_local_queue_unavailable"
                                : "morphe_local_queue_playlist_unsupported"));
                        FlyoutUtils.dismissFlyout();
                    } else {
                        flyoutButtonClickLogic(buttonName, videoId);
                    }
                });
            }
        }
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

        if (feedFlyout && FlyoutUtils.getFlyoutPlaylistId().isEmpty()
                && !hasFlyoutButton(queueButtonOriginalNames.get(1))) {
            index = FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    queueButtonDrawable,
                    str("morphe_local_queue_play_last"),
                    v -> flyoutButtonClickLogic(queueButtonOriginalNames.get(1), videoId),
                    index
            );
        }

        final int size = LocalQueuePatch.size();
        if (feedFlyout && size > 0) {
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
        if (isNativeQueueEnabled()) {
            Runnable action = getNativeQueueAction(FlyoutUtils.getCurrentButtonName(),
                    FlyoutUtils.getFlyoutVideoId());
            return action == null ? original : action;
        }
        if (!isCustomQueueEnabled()) {
            return original;
        }

        if (FlyoutUtils.getFlyoutVideoId().isEmpty()) {
            Logger.printDebug(() -> "Cannot replace on item click, flyoutVideoId is empty");
            if (LocalQueuePatch.isEnabled() && queueButtonOriginalNames.contains(FlyoutUtils.getCurrentButtonName())) {
                return () -> {
                    Utils.showSnackbarShort(str("morphe_local_queue_playlist_unsupported"));
                    FlyoutUtils.dismissFlyout();
                };
            }
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
            if (!isCustomQueueEnabled() && !isNativeQueueEnabled()) {
                return false;
            }

            if (isNativeQueueEnabled() && object instanceof String name) {
                return flyoutButtonClickLogic(name, FlyoutUtils.getFlyoutVideoId());
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
        final boolean nativeAction = isNativeQueueEnabled()
                && queueButtonOriginalNames.contains(buttonName);
        try {
            if (nativeAction) {
                getNativeQueueAction(buttonName, videoId).run();
                return true;
            }
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

        // An error in a Native-owned action must not dispatch the original
        // Premium command through the caller's fallback path.
        return nativeAction;
    }

    @Nullable
    private static Runnable getNativeQueueAction(String buttonName, String videoId) {
        if (!queueButtonOriginalNames.contains(buttonName)) {
            return null;
        }
        Runnable action = NativePlaylistQueuePatch.getFlyoutQueueAction(videoId,
                queueButtonOriginalNames.get(1).equals(buttonName));
        // An unsupported/stale menu context cannot silently invoke a Premium or
        // account-backed queue when the user explicitly selected Native queue.
        return action != null ? action : () -> {
            Utils.showSnackbarShort(str("morphe_native_playlist_queue_not_ready"));
            FlyoutUtils.dismissFlyout();
        };
    }
}
