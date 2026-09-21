package app.morphe.extension.youtube.series;

import android.media.session.MediaSession;
import android.media.session.PlaybackState;

import java.lang.ref.WeakReference;

/** Only YouTube's own in-process media session; never a global media key. */
public final class PlaybackSession {
    private static volatile WeakReference<MediaSession> current = new WeakReference<>(null);

    public static void attach(MediaSession session) {
        current = new WeakReference<>(session);
    }

    static boolean active() {
        MediaSession s = current.get();
        return s != null && s.isActive();
    }

    static boolean play() {
        MediaSession session = current.get();
        if (session == null || !session.isActive()) return false;
        PlaybackState state = session.getController().getPlaybackState();
        if (state == null) return false;
        int value = state.getState();
        if (value == PlaybackState.STATE_PLAYING || value == PlaybackState.STATE_BUFFERING)
            return true;
        if (value != PlaybackState.STATE_PAUSED && value != PlaybackState.STATE_STOPPED)
            return false;
        session.getController().getTransportControls().play();
        return true;
    }

    private PlaybackSession() {}
}
