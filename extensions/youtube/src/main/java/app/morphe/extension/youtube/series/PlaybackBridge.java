package app.morphe.extension.youtube.series;

import java.lang.ref.WeakReference;

/** Both observations come from the same current non-casting player controller. */
public final class PlaybackBridge {
    public interface Source {
        String seriesTrackerVideoId();

        long seriesTrackerPosition();
    }

    private static volatile WeakReference<Source> current = new WeakReference<>(null);

    public static void attach(Source source) {
        current = new WeakReference<>(source);
    }

    public static String videoId() {
        Source source = current.get();
        return source == null ? "" : source.seriesTrackerVideoId();
    }

    public static long position() {
        Source source = current.get();
        return source == null ? -1 : source.seriesTrackerPosition();
    }

    public static Source source() {
        return current.get();
    }

    private PlaybackBridge() {}
}
