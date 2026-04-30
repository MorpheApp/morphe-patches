package app.morphe.extension.youtube.patches;

import android.media.session.PlaybackState;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class MediaNotificationControlsPatch {

    /** Injection point. */
    public static PlaybackState filterPlaybackState(PlaybackState state) {
        long actions = state.getActions();
        long filtered = actions;
        if (Settings.HIDE_NOTIFICATION_MEDIA_SEEKBAR.get()) {
            filtered &= ~PlaybackState.ACTION_SEEK_TO;
        }
        if (Settings.HIDE_NOTIFICATION_MEDIA_PREV_NEXT.get()) {
            filtered &= ~PlaybackState.ACTION_SKIP_TO_NEXT;
            filtered &= ~PlaybackState.ACTION_SKIP_TO_PREVIOUS;
        }
        if (filtered == actions) return state;
        return new PlaybackState.Builder(state).setActions(filtered).build();
    }
}
