package android.support.v4.media;

// Used only while compiling; YouTube Music provides the real class at runtime.
public class MediaBrowserCompat {
    public static class MediaItem {
        public static final int FLAG_BROWSABLE = 1;
        public static final int FLAG_PLAYABLE = 2;

        public MediaItem(MediaDescriptionCompat description, int flags) {
            throw new UnsupportedOperationException("Stub");
        }

        // Returns the media ID.
        public String a() {
            throw new UnsupportedOperationException("Stub");
        }

        // Whether the item can be opened to browse its contents.
        public boolean b() {
            throw new UnsupportedOperationException("Stub");
        }
    }
}
