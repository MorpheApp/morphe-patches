package android.support.v4.media;

// Used only while compiling; YouTube Music provides the real class at runtime.
public class MediaBrowserCompat {
    public static class MediaItem {
        public static final int FLAG_PLAYABLE = 2;

        public MediaItem(MediaDescriptionCompat description, int flags) {
            throw new UnsupportedOperationException("Stub");
        }
    }
}
