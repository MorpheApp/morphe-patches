/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1972
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.youtube.shared.NavigationBar.NavigationButton;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.shared.ByteTrieSearch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.TrieSearch;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferPhraseFilter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.LongSetting;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.requests.AiSListRequester;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings({"unused", "unchecked"})
public final class AiSListFilter extends BufferPhraseFilter {

    /** Refresh the cached list from GitHub raw after this long since last successful fetch. */
    private static final long REFRESH_CHECK_INTERVAL_MS = 4 * 60 * 1000L;

    /** Sliding-window horizon for the "last 24 hours" stat. */
    private static final long HIDES_24H_WINDOW_MS = 24 * 60 * 60 * 1000L;

    /** Safety cap on the 24h map (prevents unbounded growth if extraction misfires). */
    private static final int MAX_TRACKED_VIDEOS = 2000;

    private volatile ByteTrieSearch blocklistSearch;
    private volatile ByteTrieSearch warnlistSearch;
    private volatile String lastBlocklistParsed;
    private volatile String lastWarnlistParsed;

    private final AtomicLong lastRefreshCheckMs = new AtomicLong(0);

    /**
     * Path-match callback for comment threads. Gate is null so the callback fires whether
     * the blocklist or warnlist comment toggle is on; matchBuffer performs the per-list gate.
     */
    private final StringFilterGroup commentsFilter = new StringFilterGroup(
            null,
            "comment_thread.eml"
    );

    public AiSListFilter() {
        super(); // commentsFilter is added below because we need to reference it in matchBuffer.
        addPathCallbacks(commentsFilter);
        reparseIfNeeded();
    }

    @Override
    protected void reparseIfNeeded() {
        final long now = System.currentTimeMillis();
        final long lastCheck = lastRefreshCheckMs.get();
        if (now - lastCheck > REFRESH_CHECK_INTERVAL_MS
                && lastRefreshCheckMs.compareAndSet(lastCheck, now)) {
            Utils.runOnBackgroundThread(AiSListRequester::fetchAndStore);
        }

        String currentBlocklist = Settings.AISLIST_BLOCKLIST_CACHE.get();
        //noinspection StringEquality
        if (currentBlocklist != lastBlocklistParsed) {
            parseBlocklist(currentBlocklist);
        }

        String currentWarnlist = Settings.AISLIST_WARNLIST_CACHE.get();
        //noinspection StringEquality
        if (currentWarnlist != lastWarnlistParsed) {
            parseWarnlist(currentWarnlist);
        }
    }

    private synchronized void parseBlocklist(String raw) {
        //noinspection StringEquality
        if (raw == lastBlocklistParsed) return;
        blocklistSearch = parseList(raw, "blocklist");
        lastBlocklistParsed = raw;
    }

    private synchronized void parseWarnlist(String raw) {
        //noinspection StringEquality
        if (raw == lastWarnlistParsed) return;
        warnlistSearch = parseList(raw, "warnlist");
        lastWarnlistParsed = raw;
    }

    @Nullable
    private static ByteTrieSearch parseList(String raw, String tag) {
        if (raw == null || raw.isBlank()) return null;

        ByteTrieSearch search = new ByteTrieSearch();
        int count = 0;
        for (String line : raw.split("\n")) {
            line = line.stripTrailing();
            if (line.isEmpty() || line.charAt(0) == '!') continue;
            // Only @handles are matched. UC channel IDs are dropped because they also
            // appear in unrelated URLs in the buffer (thumbnails, related-video endpoints)
            // and cannot be reliably distinguished without a URL-prefix match.
            if (line.charAt(0) != '@') continue;

            final String handle = line;
            TrieSearch.TriePatternMatchedCallback<byte[]> callback =
                    (text, startIndex, matchLength, callbackParam) -> {
                        ((MutableReference<String>) callbackParam).value = handle;
                        return true;
                    };
            search.addPattern(handle.getBytes(StandardCharsets.UTF_8), callback);
            count++;
        }

        final int total = count;
        Logger.printDebug(() -> "AiSList " + tag + ": parsed: " + total
                + " handles: " + search.getEstimatedMemorySize() + " KB");
        return count == 0 ? null : search;
    }

    @Override
    protected boolean isActiveForFeedContext() {
        // Any feed-scope toggle enables the base's guard; matchBuffer performs the per-list check.
        return blocklistActiveForFeedContext() || warnlistActiveForFeedContext();
    }

    private static boolean blocklistActiveForFeedContext() {
        return activeFor(Settings.HIDE_AISLIST_BLOCKLIST_HOME, Settings.HIDE_AISLIST_BLOCKLIST_SEARCH);
    }

    private static boolean warnlistActiveForFeedContext() {
        return activeFor(Settings.HIDE_AISLIST_WARNLIST_HOME, Settings.HIDE_AISLIST_WARNLIST_SEARCH);
    }

    private static boolean activeFor(BooleanSetting homeSetting, BooleanSetting searchSetting) {
        // Player fullscreen: treat under-video results as home.
        if (PlayerType.getCurrent().isMaximizedOrFullscreen()) {
            return homeSetting.get();
        }
        if (NavigationBar.isSearchBarActive()) {
            return searchSetting.get();
        }
        NavigationButton nav = NavigationButton.getSelectedNavigationButton();
        // Unknown tab defaults to home; other tabs (Subscriptions, Library, Notifications) are skipped.
        if (nav == null || nav == NavigationButton.HOME) return homeSetting.get();
        return false;
    }

    @Override
    @Nullable
    protected String matchBuffer(byte[] buffer, StringFilterGroup matchedGroup) {
        final boolean isComment = matchedGroup == commentsFilter;

        ByteTrieSearch bl = blocklistSearch;
        boolean blActive = isComment
                ? Settings.HIDE_AISLIST_BLOCKLIST_COMMENTS.get()
                : blocklistActiveForFeedContext();
        if (bl != null && blActive) {
            MutableReference<String> ref = new MutableReference<>();
            if (bl.matches(buffer, ref)) {
                recordHide(matchedGroup, buffer);
                return ref.value;
            }
        }

        ByteTrieSearch wl = warnlistSearch;
        boolean wlActive = isComment
                ? Settings.HIDE_AISLIST_WARNLIST_COMMENTS.get()
                : warnlistActiveForFeedContext();
        if (wl != null && wlActive) {
            MutableReference<String> ref = new MutableReference<>();
            if (wl.matches(buffer, ref)) {
                recordHide(matchedGroup, buffer);
                return ref.value;
            }
        }

        return null;
    }

    private void recordHide(StringFilterGroup matchedGroup, byte[] buffer) {
        Source source = detectSource(matchedGroup);
        String videoId = getVideoIdForSource(source, buffer);
        // If ID extraction fails the hide still happens; only stats are skipped
        // to avoid double-counting the same card as it re-enters the viewport.
        if (videoId == null) return;

        if (sharedTracker.recordHide(videoId, source, System.currentTimeMillis())) {
            LongSetting counter = allTimeCounterFor(source);
            if (counter != null) counter.save(counter.get() + 1);
        }
    }

    private Source detectSource(StringFilterGroup matchedGroup) {
        if (matchedGroup == commentsFilter) return Source.COMMENTS;
        // Player fullscreen: treat under-video results as home (mirrors activeFor).
        if (PlayerType.getCurrent().isMaximizedOrFullscreen()) return Source.HOME;
        if (NavigationBar.isSearchBarActive()) return Source.SEARCH;
        NavigationButton nav = NavigationButton.getSelectedNavigationButton();
        return nav == NavigationButton.SUBSCRIPTIONS ? Source.SUBSCRIPTIONS : Source.HOME;
    }

    @Nullable
    private static String getVideoIdForSource(Source source, byte[] buffer) {
        if (source == Source.COMMENTS) {
            // Comment threads carry no thumbnail URL for the parent video; the currently
            // open player's video ID is authoritative.
            String id = VideoInformation.getVideoId();
            return id.isEmpty() ? null : id;
        }
        return extractVideoIdFromBuffer(buffer);
    }

    @Nullable
    private static LongSetting allTimeCounterFor(Source source) {
        return switch (source) {
            case HOME -> Settings.AISLIST_HIDE_COUNT_HOME;
            case SEARCH -> Settings.AISLIST_HIDE_COUNT_SEARCH;
            case COMMENTS -> Settings.AISLIST_HIDE_COUNT_COMMENTS;
            // Subscription feed is not filtered by AiSList, so no counter exists.
            case SUBSCRIPTIONS -> null;
        };
    }

    /** Returns the total 24h hide count across all sources. */
    public static int hidesInLast24Hours() {
        return sharedTracker.totalSize(System.currentTimeMillis());
    }

    /** Returns the 24h hide count for a given source. */
    public static int hidesInLast24Hours(Source source) {
        return sharedTracker.sourceSize(source, System.currentTimeMillis());
    }

    /** Clears the 24h tracker. Called from the reset dialogs. */
    public static void resetHidesTracker() {
        sharedTracker.reset();
    }

    /** Shared static reference so the UI can query without holding a filter instance. */
    private static final HidesTracker sharedTracker = new HidesTracker();

    @Override
    protected void onHideConfirmed(String matched) {
        // Stats already recorded inside matchBuffer where the buffer and matched group are available.
    }

    /**
     * Persists a JSON dict {"videoId":{"t":timestamp,"s":sourceOrdinal}} to AISLIST_HIDES_24H.
     * Loads lazily on first use, purges entries older than the 24h horizon on each recorded hide,
     * and caps map size.
     */
    private static final class HidesTracker {
        @GuardedBy("this")
        private final HashMap<String, Entry> data = new HashMap<>();
        @GuardedBy("this")
        private boolean loaded;

        private record Entry(long timestamp, int sourceOrdinal) {}

        synchronized int totalSize(long now) {
            loadIfNeeded();
            purgeOlderThan(now - HIDES_24H_WINDOW_MS);
            return data.size();
        }

        synchronized int sourceSize(Source source, long now) {
            loadIfNeeded();
            purgeOlderThan(now - HIDES_24H_WINDOW_MS);
            final int wanted = source.ordinal();
            int count = 0;
            for (Entry e : data.values()) {
                if (e.sourceOrdinal() == wanted) count++;
            }
            return count;
        }

        /** @return true if the video was newly recorded, false if already present. */
        synchronized boolean recordHide(String videoId, Source source, long now) {
            loadIfNeeded();
            purgeOlderThan(now - HIDES_24H_WINDOW_MS);

            if (data.containsKey(videoId)) {
                return false;
            }

            data.put(videoId, new Entry(now, source.ordinal()));
            if (data.size() > MAX_TRACKED_VIDEOS) {
                evictEldest();
            }
            save();
            return true;
        }

        synchronized void reset() {
            data.clear();
            loaded = true;
            Settings.AISLIST_HIDES_24H.resetToDefault();
        }

        private void loadIfNeeded() {
            if (loaded) return;
            loaded = true;
            String raw = Settings.AISLIST_HIDES_24H.get();
            if (raw.isEmpty()) return;
            try {
                JSONObject obj = new JSONObject(raw);
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    JSONObject e = obj.getJSONObject(k);
                    data.put(k, new Entry(e.getLong("t"), e.getInt("s")));
                }
            } catch (Exception ex) {
                Logger.printException(() -> "AiSList 24h store is corrupt, resetting", ex);
                data.clear();
                Settings.AISLIST_HIDES_24H.resetToDefault();
            }
        }

        private void purgeOlderThan(long threshold) {
            data.entrySet().removeIf(e -> e.getValue().timestamp() < threshold);
        }

        private void evictEldest() {
            String eldestKey = null;
            long eldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, Entry> e : data.entrySet()) {
                if (e.getValue().timestamp() < eldestTime) {
                    eldestTime = e.getValue().timestamp();
                    eldestKey = e.getKey();
                }
            }
            if (eldestKey != null) data.remove(eldestKey);
        }

        private void save() {
            try {
                JSONObject obj = new JSONObject();
                for (Map.Entry<String, Entry> e : data.entrySet()) {
                    JSONObject entryObj = new JSONObject();
                    entryObj.put("t", e.getValue().timestamp());
                    entryObj.put("s", e.getValue().sourceOrdinal());
                    obj.put(e.getKey(), entryObj);
                }
                Settings.AISLIST_HIDES_24H.save(obj.toString());
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to save AiSList 24h store", ex);
            }
        }
    }
}
