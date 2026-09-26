/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1322
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import android.media.session.PlaybackState;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public class MediaNotificationControlsPatch {

    public static final Boolean HIDE_NOTIFICATIONS_MEDIA_SEEKBAR = SharedYouTubeSettings.DISABLE_NOTIFICATION_MEDIA_SEEKBAR.get();
    public static final Boolean HIDE_NOTIFICATION_MEDIA_PREV_NEXT = SharedYouTubeSettings.HIDE_NOTIFICATION_MEDIA_PREV_NEXT.get();

    /**
     * Injection point.
     */
    public static PlaybackState changePlaybackState(PlaybackState state) {
        try {
            if (HIDE_NOTIFICATIONS_MEDIA_SEEKBAR || HIDE_NOTIFICATION_MEDIA_PREV_NEXT) {
                long filtered = state.getActions();

                if (HIDE_NOTIFICATIONS_MEDIA_SEEKBAR) {
                    filtered &= ~PlaybackState.ACTION_SEEK_TO;
                }
                if (HIDE_NOTIFICATION_MEDIA_PREV_NEXT) {
                    filtered &= ~PlaybackState.ACTION_SKIP_TO_NEXT;
                    filtered &= ~PlaybackState.ACTION_SKIP_TO_PREVIOUS;
                }

                return new PlaybackState.Builder(state).setActions(filtered).build();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "changePlaybackState failure", ex);
        }

        return state;
    }
}
