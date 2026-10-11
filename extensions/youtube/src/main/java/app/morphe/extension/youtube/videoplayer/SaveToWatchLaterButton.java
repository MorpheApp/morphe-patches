/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.function.Function;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.spoof.SpoofAppVersionPatch;
import app.morphe.extension.youtube.patches.SaveToWatchLaterPatch;
import app.morphe.extension.youtube.patches.VersionCheckPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.patches.utils.PlaylistPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class SaveToWatchLaterButton {

    public static final int addToQueueResourceId =
            ResourceUtils.getIdentifier(ResourceType.DRAWABLE, PlayerIcons.name(
                    "morphe_add_to_queue_button",
                    "yt_outline_list_add_black_24",
                    "yt_outline_experimental_playlist_add_vd_theme_24"
            ));

    private static final boolean USE_EXPERIMENTAL_ICONS = VersionCheckPatch.IS_20_31_OR_GREATER
            && !SpoofAppVersionPatch.isSpoofingToLessThan("20.31.00");

    private static final Drawable saveToWatchLaterDrawable = ResourceUtils.getDrawable(
            USE_EXPERIMENTAL_ICONS
                    ? "yt_outline_experimental_clock_vd_theme_24"
                    : "yt_outline_clock_black_24"
    );

    private static final String saveToWatchLaterButtonName = str("morphe_save_to_watch_later_flyout_title");

    static {
        if (Settings.SAVE_TO_WATCH_LATER_OVERLAY_BUTTON.get()) {
            LegacyPlayerControlButton.incrementUpperButtonCount();
        }
    }

    /**
     * injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            // Start syncing queue playlist items in the background so that by the time
            // the user opens the queue menu, lastVideoIds is already populated.
            PlaylistPatch.syncIfNeeded();

            final boolean swapSaveAndQueue = Settings.SWAP_SAVE_AND_QUEUE_ACTIONS.get();
            //noinspection ExtractMethodRecommender
            WeakReference<View> controlsRef = new WeakReference<>(controlsView);

            Function<Boolean, Void> clickAction = openQueue -> {
                if (openQueue) {
                    View controls = controlsRef.get();
                    if (controls == null) {
                        Logger.printException(() -> "Context is null");
                        return null;
                    }
                    PlaylistPatch.prepareDialogBuilder(controls.getContext(), VideoInformation.getVideoId());
                } else {
                    SaveToWatchLaterPatch.saveVideo(VideoInformation.getVideoId());
                }
                return null;
            };

            LegacyPlayerControlButton instance = new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_save_to_watch_later_button",
                    null,
                    swapSaveAndQueue ? null : "morphe_save_to_watch_later_button",
                    Settings.SAVE_TO_WATCH_LATER_OVERLAY_BUTTON,
                    v -> clickAction.apply(swapSaveAndQueue),
                    v -> {
                        clickAction.apply(!swapSaveAndQueue);
                        return true;
                    }
            );

            if (swapSaveAndQueue) {
                instance.setIcon(addToQueueResourceId);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    public static int addFlyoutButton(Object flyoutPanel, int nextButtonIndex, String flyoutVideoId,
                                      boolean videoMarkedAsForKids, boolean isShortFlyout, boolean isTopFlyout) {
        final String saveToWatchLaterButtonVideoId;
        if (!flyoutVideoId.isEmpty()) {
            if (Settings.KIDS_SAVE_TO_WATCH_LATER_FLYOUT_BUTTON.get() &&
                    videoMarkedAsForKids) {
                saveToWatchLaterButtonVideoId = flyoutVideoId;
            } else if (Settings.SHORTS_SAVE_TO_WATCH_LATER_FLYOUT_BUTTON.get() &&
                    isShortFlyout) {
                saveToWatchLaterButtonVideoId = flyoutVideoId;
            } else {
                saveToWatchLaterButtonVideoId = "";
            }
        } else if (Settings.SAVE_TO_WATCH_LATER_FLYOUT_BUTTON.get() &&
                isTopFlyout) {
            saveToWatchLaterButtonVideoId = VideoInformation.getVideoId();
        } else {
            saveToWatchLaterButtonVideoId = "";
        }

        if (!saveToWatchLaterButtonVideoId.isEmpty()) {
            nextButtonIndex = FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    saveToWatchLaterDrawable,
                    saveToWatchLaterButtonName,
                    v -> {
                        SaveToWatchLaterPatch.saveVideo(saveToWatchLaterButtonVideoId);
                        FlyoutUtils.dismissFlyout();
                    },
                    nextButtonIndex
            );
        }
        return nextButtonIndex;
    }
}
