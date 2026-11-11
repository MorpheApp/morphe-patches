/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.ShortsPlayerState;
import app.morphe.extension.youtube.videoplayer.LoopCountFlyoutButton;

/**
 * Limits how many times the app's own loop video plays a video, then lets the app play the next video.
 * In a playlist, the loop count is saved for each video of the playlist.
 */
@SuppressWarnings("unused")
public class LoopCountPatch {

    /**
     * Navigator of the app, which can change the repeat mode.
     */
    public interface Navigator {
        void patch_setNavigatorRepeatMode(int repeatMode);
    }

    /**
     * Bottom sheet menu of the app.
     */
    public interface BottomSheetMenu {
        void patch_closeMenu();
    }

    /** Repeat modes of the app. */
    private static final int REPEAT_MODE_OFF = 0;
    private static final int REPEAT_MODE_VIDEO = 2;

    /** Lowest loop count. A video that plays once does not loop. */
    public static final int MIN_LOOP_COUNT = 2;
    public static final int MAX_LOOP_COUNT = 99;

    /** Maximum number of saved loop counts. The oldest saved count is removed first. */
    private static final int MAX_SAVED_LOOP_COUNTS = 500;

    /** Ads also report the ended state, so the video only ended if it played to this close to its end. */
    private static final long END_OF_VIDEO_TOLERANCE_MILLISECONDS = 5000;

    private static volatile int repeatMode = REPEAT_MODE_OFF;

    /** If the menu that is being created has the loop video item of the app. */
    private static volatile boolean loopVideoMenuItemCreated;

    /** How long after the loop video changed the menu that shows the loop count stays open when the app closes it. */
    private static final long KEEP_MENU_OPEN_MILLISECONDS = 1000;
    /** How long the menu that shows the loop count waits to close, in case the loop video changes after it was closed. */
    private static final long CLOSE_MENU_DELAY_MILLISECONDS = 400;

    /** When the loop video was last turned on or off, in uptime milliseconds. */
    private static volatile long loopVideoChangeTime;
    /** If the menu is being closed after it waited. */
    private static boolean closingMenu;

    private static WeakReference<Navigator> navigatorRef = new WeakReference<>(null);

    /** Video whose plays are counted, or an empty string if no video is counted. */
    private static String countedVideoId = "";
    /** How many times the counted video has played to the end. */
    private static int timesPlayed;
    /** Repeat mode to set once the counted video has played as many times as its loop count. */
    private static int repeatModeAfterLoopCount = REPEAT_MODE_OFF;

    /** If the last player status was the end of the video. */
    private static boolean videoEnded;
    /** If the video that ended has played as many times as its loop count, so it must not play again. */
    private static volatile boolean loopCountReached;

    /**
     * The video that the saved loop count already turned on loop video for, so it is turned on only once
     * when the video starts and the user can still turn it off.
     */
    private static String loopVideoTurnedOnVideoId = "";

    /**
     * If loop video must be turned on for the saved loop count, but the app did not give the navigator yet.
     */
    private static volatile boolean loopVideoTurnOnPending;

    /**
     * Loop counts of the videos of playlists, by playlist and video, so the same video can have
     * a different loop count in each playlist. Iteration order is oldest first.
     */
    @Nullable
    private static Map<String, Integer> savedLoopCounts;

    /** Video that is not in a playlist, and its loop count. This loop count is not saved. */
    private static String unsavedLoopCountVideoId = "";
    private static int unsavedLoopCount;

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static void setRepeatMode(int mode) {
        // The app updates its navigation state when the playback or the repeat mode changes.
        final boolean loopVideoChanged = (repeatMode == REPEAT_MODE_VIDEO) != (mode == REPEAT_MODE_VIDEO);
        repeatMode = mode;
        if (loopVideoChanged && Settings.LOOP_COUNT.get()) {
            loopVideoChangeTime = SystemClock.uptimeMillis();
            // The menu stays open when the loop video item is tapped, and shows the change.
            Utils.runOnMainThreadNowOrLater(LoopCountFlyoutButton::onLoopVideoChanged);
        }
    }

    /**
     * Injection point.
     * Called when the player menu that has the loop video item of the app is created.
     */
    public static void loopVideoMenuItemCreated() {
        loopVideoMenuItemCreated = true;
    }

    /**
     * @return If the menu that is shown has the loop video item of the app.
     *         Can only be read once for each menu, so it does not apply to the next menu.
     */
    public static boolean readLoopVideoMenuItemCreated() {
        final boolean created = loopVideoMenuItemCreated;
        loopVideoMenuItemCreated = false;
        return created;
    }

    /**
     * Injection point.
     * Called when the app closes a bottom sheet menu, which it does after an item of the menu is tapped.
     * The menu that shows the loop count stays open after the loop video item is tapped,
     * so the loop count can be chosen right after.
     * The loop video item can turn the loop video on or off before or after the app closes the menu,
     * so the menu closes a moment later, unless the loop video changed.
     *
     * @param menu Menu that the app closes.
     * @return If the menu closes now.
     */
    public static boolean closeMenu(BottomSheetMenu menu) {
        try {
            if (closingMenu || !Settings.LOOP_COUNT.get() || !LoopCountFlyoutButton.isShown()) {
                return true;
            }

            final long closeTime = SystemClock.uptimeMillis();
            if (closeTime - loopVideoChangeTime < KEEP_MENU_OPEN_MILLISECONDS) {
                Logger.printDebug(() -> "Keeping the menu open, the loop video changed "
                        + (closeTime - loopVideoChangeTime) + " ms before the menu was closed");
                return false;
            }

            Utils.runOnMainThreadDelayed(() -> {
                final long changeTime = loopVideoChangeTime;
                if (changeTime >= closeTime) {
                    Logger.printDebug(() -> "Keeping the menu open, the loop video changed "
                            + (changeTime - closeTime) + " ms after the menu was closed");
                    return;
                }

                closingMenu = true;
                try {
                    menu.patch_closeMenu();
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not close the menu", ex);
                } finally {
                    closingMenu = false;
                }
            }, CLOSE_MENU_DELAY_MILLISECONDS);
            return false;
        } catch (Exception ex) {
            Logger.printException(() -> "closeMenu failure", ex);
            return true;
        }
    }

    /**
     * @return If the app loops the current video.
     */
    public static boolean isLoopVideoEnabled() {
        return repeatMode == REPEAT_MODE_VIDEO;
    }

    /**
     * Injection point.
     */
    public static void setNavigator(Navigator navigator) {
        if (!Settings.LOOP_COUNT.get()) {
            return;
        }

        if (navigatorRef.get() != navigator) {
            navigatorRef = new WeakReference<>(navigator);
            Logger.printDebug(() -> "Navigator: " + navigator.getClass().getName());
        }
        if (loopVideoTurnOnPending) {
            loopVideoTurnOnPending = false;
            // After the app is done with the navigator.
            Utils.runOnMainThread(LoopCountPatch::turnOnPendingLoopVideo);
        }
    }

    private static synchronized void turnOnPendingLoopVideo() {
        try {
            onVideoPlaying();
        } catch (Exception ex) {
            Logger.printException(() -> "turnOnPendingLoopVideo failure", ex);
        }
    }

    private static boolean setAppRepeatMode(int mode) {
        Navigator navigator = navigatorRef.get();
        if (navigator == null) {
            Logger.printDebug(() -> "No navigator to set the repeat mode");
            return false;
        }

        Logger.printDebug(() -> "Setting repeat mode: " + mode);
        navigator.patch_setNavigatorRepeatMode(mode);
        setRepeatMode(mode);
        // That patch reads the repeat mode to keep the loop when it stops the playlist from playing the next video,
        // and the app updates it only after this navigation.
        DisablePlaylistAutoplayPatch.setRepeatMode(mode);
        return true;
    }

    /**
     * Injection point.
     *
     * @return Always false, as the navigation is never cancelled.
     */
    public static synchronized boolean onNavigation(Enum<?> navigationIntent) {
        // The app navigates with the autoplay intent when a video ends, also to play the same video again
        // if the app loops the video. Any other navigation is chosen by the user,
        // so the plays of the video are counted again even if it's the same video.
        if (!"AUTOPLAY".equals(navigationIntent.name())) {
            countedVideoId = "";
            loopVideoTurnedOnVideoId = "";
        }
        return false;
    }

    /**
     * Injection point.
     * Called before the other player status hooks, so it runs before a hook that loops the video can skip them.
     */
    public static synchronized void onPlayerStatus(Enum<?> playerStatus) {
        try {
            loopCountReached = false;
            if (!Settings.LOOP_COUNT.get()) {
                // Turned off, the loop video of the app plays the video without a limit.
                videoEnded = false;
                countedVideoId = "";
                return;
            }

            final String statusName = playerStatus == null ? "" : playerStatus.name();
            final boolean ended = "ENDED".equals(statusName);
            final boolean wasEnded = videoEnded;
            videoEnded = ended;
            if (ended && !wasEnded) {
                onVideoEnded();
            } else if ("PLAYBACK_PENDING".equals(statusName) && !ShortsPlayerState.isOpen()) {
                // The app loads a video, even the same video again, so a saved loop count can turn on loop video.
                loopVideoTurnedOnVideoId = "";
                loopVideoTurnOnPending = false;
            } else if ("VIDEO_PLAYING".equals(statusName)) {
                onVideoPlaying();
            }

            // The menu that shows the loop count can stay open while the app plays the next video.
            Utils.runOnMainThreadNowOrLater(LoopCountFlyoutButton::onPlayerStatusChanged);
        } catch (Exception ex) {
            Logger.printException(() -> "onPlayerStatus failure", ex);
        }
    }

    /**
     * The app turns off loop video when a playlist plays another video,
     * so a saved loop count turns it on again when the video starts to play.
     */
    private static void onVideoPlaying() {
        if (Settings.LOOP_VIDEO.get() || ShortsPlayerState.isOpen()) {
            return;
        }

        final String videoId = VideoInformation.getVideoId();
        final String playlistId = VideoInformation.getPlaylistId();
        if (videoId.isEmpty() || videoId.equals(loopVideoTurnedOnVideoId)
                || !isLoopCountSaved(playlistId) || getLoopCount(playlistId, videoId) <= 0) {
            return;
        }

        if (repeatMode == REPEAT_MODE_VIDEO || setAppRepeatMode(REPEAT_MODE_VIDEO)) {
            Logger.printDebug(() -> "Loop video is on for the saved loop count of video: " + videoId);
            loopVideoTurnedOnVideoId = videoId;
        } else {
            // The app gives the navigator when the player checks if it can play another video.
            loopVideoTurnOnPending = true;
        }
    }

    /**
     * Counts the play of the video that ended, before the app or another patch plays it again.
     */
    private static void onVideoEnded() {
        if (ShortsPlayerState.isOpen()) {
            // The loop count does not apply to Shorts.
            countedVideoId = "";
            return;
        }
        final long videoLength = VideoInformation.getVideoLength();
        if (videoLength > 0 && VideoInformation.getVideoTime() + END_OF_VIDEO_TOLERANCE_MILLISECONDS < videoLength) {
            return;
        }

        final String videoId = VideoInformation.getVideoId();
        final String playlistId = VideoInformation.getPlaylistId();
        final int loopCount = getLoopCount(playlistId, videoId);
        if (videoId.isEmpty() || loopCount <= 0) {
            countedVideoId = "";
            return;
        }

        final int mode = repeatMode;
        if (!videoId.equals(countedVideoId)) {
            countedVideoId = videoId;
            timesPlayed = 0;
            repeatModeAfterLoopCount = mode == REPEAT_MODE_VIDEO ? REPEAT_MODE_OFF : mode;
        }

        timesPlayed++;
        Logger.printDebug(() -> "Video " + videoId + " played " + timesPlayed + " of " + loopCount + " times");

        if (timesPlayed >= loopCount) {
            countedVideoId = "";
            loopCountReached = true;
            if (repeatMode == REPEAT_MODE_VIDEO) {
                // Before the app handles the end of the video, so it plays the next video if there is one.
                setAppRepeatMode(repeatModeAfterLoopCount);
            }
        }
    }

    /**
     * @return If the video that ended has played as many times as its loop count, so it must not be looped again.
     */
    public static boolean isLoopCountReached() {
        return loopCountReached;
    }

    private static Map<String, Integer> getSavedLoopCounts() {
        if (savedLoopCounts == null) {
            savedLoopCounts = loadSavedLoopCounts();
        }
        return savedLoopCounts;
    }

    private static Map<String, Integer> loadSavedLoopCounts() {
        Map<String, Integer> map = new LinkedHashMap<>();
        String raw = Settings.LOOP_COUNT_PLAYLIST_COUNTS.get();
        if (raw.isEmpty()) {
            return map;
        }

        try {
            JSONObject json = new JSONObject(raw);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                final int count = json.optInt(key, 0);
                if (count > 0) {
                    map.put(key, count);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to load saved loop counts", ex);
            Settings.LOOP_COUNT_PLAYLIST_COUNTS.resetToDefault();
            map.clear();
        }
        return map;
    }

    /**
     * @return How many videos of playlists have a saved loop count.
     */
    public static synchronized int getSavedLoopCountsSize() {
        return getSavedLoopCounts().size();
    }

    /**
     * Removes the saved loop count of every video of every playlist.
     */
    public static synchronized void clearSavedLoopCounts() {
        getSavedLoopCounts().clear();
        Settings.LOOP_COUNT_PLAYLIST_COUNTS.resetToDefault();
        Logger.printDebug(() -> "Saved loop counts cleared");
    }

    /**
     * @return If the loop count of a video is saved, so it applies again every time the playlist plays the video.
     */
    private static boolean isLoopCountSaved(String playlistId) {
        return !playlistId.isEmpty() && Settings.LOOP_COUNT_PLAYLISTS.get();
    }

    private static String getSavedLoopCountKey(String playlistId, String videoId) {
        return playlistId + '/' + videoId;
    }

    /**
     * @param playlistId The playlist the video plays in, or an empty string if it is not in a playlist.
     * @return How many times the video plays, or 0 if no loop count is set for the video.
     */
    public static synchronized int getLoopCount(String playlistId, String videoId) {
        if (!isLoopCountSaved(playlistId)) {
            return videoId.equals(unsavedLoopCountVideoId) ? unsavedLoopCount : 0;
        }

        Integer count = getSavedLoopCounts().get(getSavedLoopCountKey(playlistId, videoId));
        return count == null ? 0 : count;
    }

    /**
     * Sets how many times a video plays. In a playlist, the loop count is saved for the video
     * in that playlist if that setting is on, so it applies again every time the playlist plays the video.
     *
     * @param playlistId The playlist the video plays in, or an empty string if it is not in a playlist.
     * @param count      How many times the video plays. 0 or less removes the loop count.
     */
    public static synchronized void setLoopCount(String playlistId, String videoId, int count) {
        if (videoId.isEmpty()) {
            return;
        }

        if (!isLoopCountSaved(playlistId)) {
            unsavedLoopCountVideoId = videoId;
            unsavedLoopCount = Math.max(count, 0);
            return;
        }

        final String key = getSavedLoopCountKey(playlistId, videoId);
        Map<String, Integer> map = getSavedLoopCounts();
        // Remove first so the loop count is moved to the newest position.
        map.remove(key);
        if (count > 0) {
            map.put(key, count);

            Iterator<String> oldest = map.keySet().iterator();
            while (map.size() > MAX_SAVED_LOOP_COUNTS) {
                oldest.next();
                oldest.remove();
            }
        }

        Settings.LOOP_COUNT_PLAYLIST_COUNTS.save(new JSONObject(map).toString());
    }
}
