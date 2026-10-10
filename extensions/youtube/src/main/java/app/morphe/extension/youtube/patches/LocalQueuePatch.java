/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3582
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.view.View;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

@SuppressWarnings("unused")
public final class LocalQueuePatch {

    public static final class Item {
        public final String videoId;
        @Nullable
        public volatile String title;
        @Nullable
        public volatile String author;

        Item(String videoId, @Nullable String title, @Nullable String author) {
            this.videoId = videoId;
            this.title = title;
            this.author = author;
        }
    }

    private static final int MAX_ITEMS = 200;

    /**
     * End of a video is signaled by more than one hook, only the first one may start a video.
     */
    private static final long ADVANCE_GUARD_MILLISECONDS = 5000;

    private static final long END_OF_VIDEO_TOLERANCE_MILLISECONDS = 5000;

    public static final int CONNECTION_TIMEOUT_MILLISECONDS = 5000;

    private static final Object LOCK = new Object();
    @GuardedBy("LOCK")
    private static final List<Item> items = new ArrayList<>();
    @GuardedBy("LOCK")
    private static boolean loaded;
    /**
     * Video ids with a metadata request in progress.
     */
    @GuardedBy("LOCK")
    private static final Set<String> metadataLoading = new HashSet<>();
    private static volatile long lastAdvanceTime;

    @Nullable
    private static volatile Runnable changeListener;
    @Nullable
    private static volatile Consumer<String> metadataListener;

    public static boolean isEnabled() {
        return Settings.LOCAL_QUEUE.get();
    }

    public static void setListeners(Runnable onChange, Consumer<String> onMetadata) {
        changeListener = onChange;
        metadataListener = onMetadata;
    }

    /**
     * Clears the listeners if they were not replaced by a newer queue sheet.
     */
    public static void clearListeners(Runnable onChange, Consumer<String> onMetadata) {
        if (changeListener == onChange) {
            changeListener = null;
        }
        if (metadataListener == onMetadata) {
            metadataListener = null;
        }
    }

    public static List<Item> getItems() {
        synchronized (LOCK) {
            load();
            return new ArrayList<>(items);
        }
    }

    public static int size() {
        synchronized (LOCK) {
            load();
            return items.size();
        }
    }

    /**
     * @return If the queue is used and has a video to play next.
     */
    public static boolean hasQueuedVideos() {
        return isEnabled() && size() > 0;
    }

    /**
     * Puts a video at the start of the queue so it plays next, or at the end of the queue.
     * A video already in the queue is moved. If nothing is playing and the queue is empty,
     * the video starts playing.
     */
    public static void add(String videoId, boolean playNext) {
        try {
            if (videoId.isEmpty()) {
                return;
            }

            final boolean playNow = PlayerType.getCurrent().isNoneOrHidden() && size() == 0;
            if (playNow) {
                if (!startPlayback(videoId)) {
                    Utils.showToastShort(str("morphe_local_queue_play_failed"));
                }
                return;
            }

            synchronized (LOCK) {
                load();
                final int existing = indexOf(videoId);
                final Item item;
                if (existing >= 0) {
                    item = items.remove(existing);
                } else {
                    if (items.size() >= MAX_ITEMS) {
                        Utils.showToastShort(str("morphe_local_queue_full"));
                        return;
                    }
                    item = new Item(videoId, null, null);
                }
                items.add(playNext ? 0 : items.size(), item);
                save();
                fetchMetadataIfNeeded(item);
            }

            Utils.showToastShort(str("morphe_local_queue_added"));
            notifyChanged();
        } catch (Exception ex) {
            Logger.printException(() -> "add failure", ex);
        }
    }

    public static void remove(String videoId) {
        synchronized (LOCK) {
            load();
            if (!items.removeIf(item -> item.videoId.equals(videoId))) return;
            save();
        }
        notifyChanged();
    }

    /**
     * Moves a video to the position of another video.
     * The change is saved but listeners are not notified, the caller already shows it.
     */
    public static void moveTo(String videoId, String targetVideoId) {
        synchronized (LOCK) {
            load();
            final int from = indexOf(videoId);
            final int to = indexOf(targetVideoId);
            if (from < 0 || to < 0 || from == to) return;
            items.add(to, items.remove(from));
            save();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            load();
            if (items.isEmpty()) return;
            items.clear();
            save();
        }
        notifyChanged();
    }

    /**
     * Plays a queued video now and removes it from the queue.
     */
    public static void play(String videoId) {
        if (startPlayback(videoId)) {
            lastAdvanceTime = System.currentTimeMillis();
            remove(videoId);
        } else {
            Utils.showToastShort(str("morphe_local_queue_play_failed"));
        }
    }

    /**
     * Injection point.
     *
     * @return If the navigation to another video was handled and must be canceled.
     */
    public static boolean shouldCancelNavigation(Enum<?> navigationIntent) {
        try {
            if (!isEnabled()) {
                return false;
            }

            final String name = navigationIntent.name();
            final boolean next = "NEXT".equals(name);
            if (!next && !"AUTOPLAY".equals(name) && !"AUTONAV".equals(name)) {
                return false;
            }

            if (!next && isAdvanceGuardActive()) {
                return true;
            }

            return advance();
        } catch (Exception ex) {
            Logger.printException(() -> "shouldCancelNavigation failure", ex);
            return false;
        }
    }

    /**
     * Injection point.
     *
     * @return If the end of the video was handled by starting the next queued video.
     */
    public static boolean shouldCancelEndOfVideo(Enum<?> playerStatus) {
        try {
            if (!isEnabled() || playerStatus == null || !"ENDED".equals(playerStatus.name())) {
                return false;
            }

            if (Settings.LOOP_VIDEO.get() || LoopVideoPatch.isSleepTimerEndingVideo()
                    || ShortsPlayerState.isOpen()) {
                return false;
            }

            // Ads and Shorts also report the ended state, only the end of the video itself counts.
            final long videoLength = VideoInformation.getVideoLength();
            if (videoLength > 0 && VideoInformation.getVideoTime() + END_OF_VIDEO_TOLERANCE_MILLISECONDS < videoLength) {
                return false;
            }

            if (isAdvanceGuardActive()) {
                return true;
            }

            return advance();
        } catch (Exception ex) {
            Logger.printException(() -> "shouldCancelEndOfVideo failure", ex);
            return false;
        }
    }

    private static boolean isAdvanceGuardActive() {
        return System.currentTimeMillis() - lastAdvanceTime < ADVANCE_GUARD_MILLISECONDS;
    }

    private static boolean advance() {
        final Item next;
        synchronized (LOCK) {
            load();
            if (items.isEmpty()) {
                return false;
            }

            if (!canStartPlayback()) {
                return false;
            }

            next = items.remove(0);
            save();
        }

        lastAdvanceTime = System.currentTimeMillis();
        if (Utils.isCurrentlyOnMainThread()) {
            return startNext(next);
        }

        // The result is not known yet. The video is put back in the queue if it does not start.
        Utils.runOnMainThread(() -> startNext(next));
        return true;
    }

    private static boolean startNext(Item next) {
        final boolean started = startPlayback(next.videoId);
        if (!started) {
            synchronized (LOCK) {
                items.add(0, next);
                save();
            }
            lastAdvanceTime = 0;
        }
        notifyChanged();
        return started;
    }

    private static boolean canStartPlayback() {
        Activity activity = Utils.getActivity();
        // Android blocks starting an activity from the background, so a video can only be started
        // while the app is on screen.
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && activity.getWindow().getDecorView().getWindowVisibility() == View.VISIBLE
                && LoadVideoPatch.isPlayerInterfaceAvailable();
    }

    private static boolean startPlayback(String videoId) {
        try {
            if (!canStartPlayback()) {
                Logger.printDebug(() -> "Cannot start queued video, no active player interface");
                return false;
            }

            Logger.printDebug(() -> "Starting queued video: " + videoId);
            LoadVideoPatch.openVideoIntentWithInternalContext(videoId);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "startPlayback failure", ex);
            return false;
        }
    }

    private static int indexOf(String videoId) {
        for (int i = 0, size = items.size(); i < size; i++) {
            if (items.get(i).videoId.equals(videoId)) {
                return i;
            }
        }
        return -1;
    }

    private static void notifyChanged() {
        Runnable listener = changeListener;
        if (listener != null) {
            Utils.runOnMainThread(listener);
        }
    }

    private static void load() {
        if (loaded) return;
        loaded = true;

        try {
            String saved = Settings.LOCAL_QUEUE_ITEMS.get();
            if (saved.isEmpty()) return;

            JSONArray array = new JSONArray(saved);
            for (int i = 0, length = array.length(); i < length; i++) {
                JSONObject object = array.getJSONObject(i);
                String videoId = object.optString("id");
                if (videoId.isEmpty()) continue;

                String title = object.optString("title");
                String author = object.optString("author");
                items.add(new Item(videoId, title.isEmpty() ? null : title, author.isEmpty() ? null : author));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "load failure", ex);
            items.clear();
        }
    }

    private static void save() {
        try {
            JSONArray array = new JSONArray();
            for (Item item : items) {
                JSONObject object = new JSONObject();
                object.put("id", item.videoId);
                String title = item.title;
                if (title != null) {
                    object.put("title", title);
                }
                String author = item.author;
                if (author != null) {
                    object.put("author", author);
                }
                array.put(object);
            }
            Settings.LOCAL_QUEUE_ITEMS.save(array.toString());
        } catch (Exception ex) {
            Logger.printException(() -> "save failure", ex);
        }
    }

    /**
     * Fetches the title and channel name of a video, if they are not known and not already being fetched.
     */
    public static void fetchMetadataIfNeeded(Item item) {
        if (item.title != null && item.author != null) return;

        synchronized (LOCK) {
            if (!metadataLoading.add(item.videoId)) return;
        }

        Utils.runOnBackgroundThread(() -> {
            try {
                String watchUrl = URLEncoder.encode("https://www.youtube.com/watch?v=" + item.videoId, "UTF-8");
                HttpURLConnection connection = Requester.openConnection(
                        "https://www.youtube.com/oembed?format=json&url=" + watchUrl);
                connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
                connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
                JSONObject json = Requester.parseJSONObjectAndDisconnect(connection);
                String title = json.optString("title");
                String author = json.optString("author_name");
                if (title.isEmpty() && author.isEmpty()) return;

                if (!title.isEmpty()) item.title = title;
                if (!author.isEmpty()) item.author = author;
                synchronized (LOCK) {
                    save();
                }

                Consumer<String> listener = metadataListener;
                if (listener != null) {
                    Utils.runOnMainThread(() -> listener.accept(item.videoId));
                }
            } catch (IOException ex) {
                Logger.printInfo(() -> "Could not fetch metadata of: " + item.videoId, ex);
            } catch (Exception ex) {
                Logger.printException(() -> "fetchMetadataIfNeeded failure", ex);
            } finally {
                synchronized (LOCK) {
                    metadataLoading.remove(item.videoId);
                }
            }
        });
    }
}
