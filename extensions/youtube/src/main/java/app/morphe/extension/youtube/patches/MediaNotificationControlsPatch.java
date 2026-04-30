package app.morphe.extension.youtube.patches;

import android.media.session.PlaybackState;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class MediaNotificationControlsPatch {

    public static final Boolean HIDE_NOTIFICATIONS_MEDIA_SEEKBAR = Settings.DISABLE_NOTIFICATION_MEDIA_SEEKBAR.get();
    public static final Boolean HIDE_NOTIFICATION_MEDIA_PREV_NEXT = Settings.HIDE_NOTIFICATION_MEDIA_PREV_NEXT.get();

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
