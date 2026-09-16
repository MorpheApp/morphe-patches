package app.morphe.extension.music.jam;

import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;

/** Native operations run only on YouTube Music's own queue executor. */
public final class YtmBridge {
    public interface QueueAccess {
        void patch_jamEnqueue(byte[] command);
        Executor patch_jamExecutor();
        default Object[] patch_jamItems() { throw new UnsupportedOperationException(); }
        default String patch_jamVideoId(Object item) { throw new UnsupportedOperationException(); }
        default String patch_jamTitle(Object item) { throw new UnsupportedOperationException(); }
        default long patch_jamItemId(Object item) { throw new UnsupportedOperationException(); }
        default int patch_jamCurrent() { throw new UnsupportedOperationException(); }
        default boolean patch_jamLocal() { throw new UnsupportedOperationException(); }
        default void patch_jamRemove(int index) { throw new UnsupportedOperationException(); }
        default void patch_jamMove(int from, int to) { throw new UnsupportedOperationException(); }
        default String patch_jamArtist(Object item) { return ""; }
        default String patch_jamThumbnail(Object item) { return ""; }
        default byte[] patch_jamWatchItem(Object item) { throw new UnsupportedOperationException(); }
        default java.util.concurrent.Future<?> patch_jamRequestMenu(byte[] command) { throw new UnsupportedOperationException(); }
        default Object[] patch_jamMenuItems(Object response) { throw new UnsupportedOperationException(); }
        default Object patch_jamCreateItem(byte[] data,long id) { throw new UnsupportedOperationException(); }
        default Object patch_jamDisplayedList() { throw new UnsupportedOperationException(); }
        default void patch_jamDisplayedList(Object list) { throw new UnsupportedOperationException(); }
        default void patch_jamRefreshDisplay() { throw new UnsupportedOperationException(); }
        default void patch_jamViewThread(Runnable task) { throw new UnsupportedOperationException(); }
        default Object[] patch_jamAutoplayItems() { throw new UnsupportedOperationException(); }
        default Object patch_jamDisplayedAutoplay() { throw new UnsupportedOperationException(); }
        default void patch_jamDisplayedAutoplay(Object list) { throw new UnsupportedOperationException(); }
        default void patch_jamRefreshAutoplay() { throw new UnsupportedOperationException(); }
        default void patch_jamRemoveFrom(int lane,int index) { throw new UnsupportedOperationException(); }
        default void patch_jamMoveFrom(int lane,int from,int to) { throw new UnsupportedOperationException(); }
    }

    public interface DispatchCallback {
        void complete(String error);
    }

    private static volatile WeakReference<QueueAccess> queue = new WeakReference<>(null);

    private YtmBridge() {}

    static QueueAccess access() {
        QueueAccess access=queue.get();
        if(access==null) throw new IllegalStateException("Start a song in YouTube Music first");
        return access;
    }

    // Called only after the native operations-manager constructor finishes.
    public static void capture(QueueAccess access) {
        queue = new WeakReference<>(access);
    }

    /** Completion means dispatch, NOT server resolution or a confirmed queue mutation. */
    public static void enqueue(String videoId, boolean playNext, DispatchCallback callback) {
        byte[] command = QueueCommand.encode(videoId, playNext);
        QueueAccess access = queue.get();
        if (access == null) {
            callback.complete("Queue bridge not ready. Start playing a song first.");
            return;
        }
        try {
            access.patch_jamExecutor().execute(() -> {
                if (queue.get() != access) {
                    callback.complete("Player changed. Retry from the current player.");
                    return;
                }
                try {
                    access.patch_jamEnqueue(command);
                    callback.complete(null);
                } catch (RuntimeException error) {
                    callback.complete("Native queue dispatch failed: " + error.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException error) {
            callback.complete("Player executor unavailable: " + error.getClass().getSimpleName());
        }
    }
}
