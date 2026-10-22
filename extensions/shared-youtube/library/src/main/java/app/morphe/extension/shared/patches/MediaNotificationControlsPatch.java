/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1322
 * https://github.com/MorpheApp/morphe-patches/pull/3955
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.media.session.PlaybackState;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public class MediaNotificationControlsPatch {

    public static final Boolean HIDE_NOTIFICATIONS_MEDIA_SEEKBAR = SharedYouTubeSettings.DISABLE_NOTIFICATION_MEDIA_SEEKBAR.get();
    public static final Boolean HIDE_NOTIFICATION_MEDIA_PREV_NEXT = SharedYouTubeSettings.HIDE_NOTIFICATION_MEDIA_PREV_NEXT.get();
    public static final Boolean SEEK_NOTIFICATION_MEDIA_PREV_NEXT = !HIDE_NOTIFICATION_MEDIA_PREV_NEXT
            && SharedYouTubeSettings.SEEK_NOTIFICATION_MEDIA_PREV_NEXT.get();

    /**
     * Maximum seek length, in seconds.
     */
    private static final int SEEK_LENGTH_MAXIMUM = 3600;

    private static int previousTitleId;
    private static int nextTitleId;

    /**
     * Injection point.
     *
     * Android 12 and lower build the media notification buttons from the notification actions,
     * not from the playback state actions. Skipping the previous/next actions removes the buttons
     * instead of leaving non-functional ones on the notification and lock screen.
     *
     * @param titleId String resource id of the action title.
     * @return If the notification action should not be added.
     */
    public static boolean hideNotificationAction(int titleId) {
        if (!HIDE_NOTIFICATION_MEDIA_PREV_NEXT) {
            return false;
        }

        try {
            if (previousTitleId == 0) {
                previousTitleId = ResourceUtils.getIdentifier(ResourceType.STRING, "playback_control_previous");
                nextTitleId = ResourceUtils.getIdentifier(ResourceType.STRING, "playback_control_next");
            }

            return titleId != 0 && (titleId == previousTitleId || titleId == nextTitleId);
        } catch (Exception ex) {
            Logger.printException(() -> "hideNotificationAction failure", ex);
        }

        return false;
    }

    /**
     * Injection point.
     *
     * @return If Previous and Next should rewind and fast forward the current video
     *         instead of changing videos.
     */
    public static boolean seekInsteadOfSkip() {
        return SEEK_NOTIFICATION_MEDIA_PREV_NEXT;
    }

    /**
     * Injection point.
     *
     * @param seekLength Relative seek of the media session fast forward (positive)
     *                   or rewind (negative) action, in milliseconds.
     * @return The seek length to use, in milliseconds.
     */
    public static long getSeekLength(long seekLength) {
        if (!SEEK_NOTIFICATION_MEDIA_PREV_NEXT) {
            return seekLength;
        }

        try {
            final boolean fastForward = seekLength > 0;
            IntegerSetting setting = fastForward
                    ? SharedYouTubeSettings.SEEK_NOTIFICATION_MEDIA_FAST_FORWARD_LENGTH
                    : SharedYouTubeSettings.SEEK_NOTIFICATION_MEDIA_REWIND_LENGTH;

            int seconds = setting.get();
            if (seconds < 1 || seconds > SEEK_LENGTH_MAXIMUM) {
                Utils.showToastLong(str("morphe_seek_notification_media_length_invalid", SEEK_LENGTH_MAXIMUM));
                seconds = setting.resetToDefault();
            }

            final long millis = seconds * 1000L;
            return fastForward ? millis : -millis;
        } catch (Exception ex) {
            Logger.printException(() -> "getSeekLength failure", ex);
        }

        return seekLength;
    }

    /**
     * Injection point.
     */
    public static PlaybackState changePlaybackState(PlaybackState state) {
        try {
            if (HIDE_NOTIFICATIONS_MEDIA_SEEKBAR || HIDE_NOTIFICATION_MEDIA_PREV_NEXT
                    || SEEK_NOTIFICATION_MEDIA_PREV_NEXT) {
                long filtered = state.getActions();

                if (HIDE_NOTIFICATIONS_MEDIA_SEEKBAR) {
                    filtered &= ~PlaybackState.ACTION_SEEK_TO;
                }
                if (HIDE_NOTIFICATION_MEDIA_PREV_NEXT) {
                    filtered &= ~PlaybackState.ACTION_SKIP_TO_NEXT;
                    filtered &= ~PlaybackState.ACTION_SKIP_TO_PREVIOUS;
                }
                if (SEEK_NOTIFICATION_MEDIA_PREV_NEXT) {
                    // Media buttons are only delivered if the action is available,
                    // and there may be no previous or next video to skip to.
                    filtered |= PlaybackState.ACTION_SKIP_TO_NEXT;
                    filtered |= PlaybackState.ACTION_SKIP_TO_PREVIOUS;
                }

                return new PlaybackState.Builder(state).setActions(filtered).build();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "changePlaybackState failure", ex);
        }

        return state;
    }
}
