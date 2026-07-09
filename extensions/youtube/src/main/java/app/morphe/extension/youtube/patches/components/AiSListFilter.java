/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1972
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.youtube.shared.NavigationBar.NavigationButton;

import androidx.annotation.NonNull;
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
import app.morphe.extension.youtube.patches.utils.requests.AiSListRequester;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings({"unused", "unchecked"})
public final class AiSListFilter extends BufferPhraseFilter {

    /** Refresh the cached list from GitHub raw after this long since last successful fetch. */
    private static final long REFRESH_INTERVAL_MS = 4 * 60 * 60 * 1000L;

    /** Throttle for how often the isFiltered-path checks whether a refresh is due. */
    private static final long REFRESH_CHECK_INTERVAL_MS = 30 * 60 * 1000L;

    /** Sliding-window horizon for the "last 24 hours" stat. */
    private static final long HIDES_24H_WINDOW_MS = 24 * 60 * 60 * 1000L;

    /** Safety cap on the 24h map (prevents unbounded growth if extraction misfires). */
    private static final int MAX_TRACKED_VIDEOS = 2000;

    /** YouTube video IDs are 11 chars from base64-url alphabet. */
    private static final int VIDEO_ID_LENGTH = 11;

    /** Prefix that precedes the 11-char video ID inside the buffer. */
    private static final byte[] THUMBNAIL_URL_PREFIX =
            "https://i.ytimg.com/vi/".getBytes(StandardCharsets.UTF_8);

    private volatile ByteTrieSearch blocklistSearch;
    private volatile ByteTrieSearch warnlistSearch;
    private volatile String lastBlocklistParsed;
    private volatile String lastWarnlistParsed;

    private final AtomicLong lastRefreshCheckMs = new AtomicLong(0);

    public AiSListFilter() {
        super(); // No extra path callbacks - AiSList only filters feed/search cards.
        Utils.runOnBackgroundThread(this::maybeRefreshList);
    }

    private void maybeRefreshList() {
        try {
            long last = Settings.AISLIST_LAST_FETCH_MS.get();
            if (System.currentTimeMillis() - last >= REFRESH_INTERVAL_MS) {
                AiSListRequester.fetchAndStore();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "AiSList refresh check failed", ex);
        }
    }

    @Override
    protected void reparseIfNeeded() {
        long now = System.currentTimeMillis();
        long lastCheck = lastRefreshCheckMs.get();
        if (now - lastCheck > REFRESH_CHECK_INTERVAL_MS
                && lastRefreshCheckMs.compareAndSet(lastCheck, now)) {
            Utils.runOnBackgroundThread(this::maybeRefreshList);
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
        Logger.printDebug(() -> "AiSList " + tag + ": parsed " + total + " handles (" + search.getEstimatedMemorySize() + " KB)");
        return count == 0 ? null : search;
    }

    @Override
    protected boolean isActiveForFeedContext() {
        return activeFor(Settings.HIDE_AISLIST_BLOCKLIST_HOME, Settings.HIDE_AISLIST_BLOCKLIST_SEARCH)
                || activeFor(Settings.HIDE_AISLIST_WARNLIST_HOME, Settings.HIDE_AISLIST_WARNLIST_SEARCH);
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
        ByteTrieSearch bl = blocklistSearch;
        if (bl != null && activeFor(Settings.HIDE_AISLIST_BLOCKLIST_HOME, Settings.HIDE_AISLIST_BLOCKLIST_SEARCH)) {
            MutableReference<String> ref = new MutableReference<>();
            if (bl.matches(buffer, ref)) {
                recordHide(buffer);
                return ref.value;
            }
        }

        ByteTrieSearch wl = warnlistSearch;
        if (wl != null && activeFor(Settings.HIDE_AISLIST_WARNLIST_HOME, Settings.HIDE_AISLIST_WARNLIST_SEARCH)) {
            MutableReference<String> ref = new MutableReference<>();
            if (wl.matches(buffer, ref)) {
                recordHide(buffer);
                return ref.value;
            }
        }

        return null;
    }

    private void recordHide(byte[] buffer) {
        String videoId = extractVideoId(buffer);
        // If ID extraction fails the hide still happens; only stats are skipped
        // to avoid double-counting the same card as it re-enters the viewport.
        if (videoId == null) return;

        if (sharedTracker.recordHide(videoId, System.currentTimeMillis())) {
            Settings.AISLIST_HIDE_COUNT.save(Settings.AISLIST_HIDE_COUNT.get() + 1);
        }
    }

    @Nullable
    private static String extractVideoId(byte[] buffer) {
        final byte[] prefix = THUMBNAIL_URL_PREFIX;
        final int prefixLen = prefix.length;
        outer:
        for (int i = 0, max = buffer.length - prefixLen - VIDEO_ID_LENGTH; i <= max; i++) {
            for (int j = 0; j < prefixLen; j++) {
                if (buffer[i + j] != prefix[j]) continue outer;
            }
            int start = i + prefixLen;
            for (int k = 0; k < VIDEO_ID_LENGTH; k++) {
                if (!isVideoIdChar(buffer[start + k])) continue outer;
            }
            return new String(buffer, start, VIDEO_ID_LENGTH, StandardCharsets.US_ASCII);
        }
        return null;
    }

    private static boolean isVideoIdChar(byte b) {
        return (b >= 'A' && b <= 'Z')
                || (b >= 'a' && b <= 'z')
                || (b >= '0' && b <= '9')
                || b == '-' || b == '_';
    }

    /**
     * Persists a JSON dict {videoId: hideTimestampMs} to AISLIST_HIDES_24H. Loads lazily on first
     * use, purges entries older than the 24h horizon on each recorded hide, and caps map size.
     */
    private static final class HidesTracker {
        private final HashMap<String, Long> data = new HashMap<>();
        private boolean loaded;

        synchronized int size(long now) {
            loadIfNeeded();
            purgeOlderThan(now - HIDES_24H_WINDOW_MS);
            return data.size();
        }

        /** @return true if the video was newly recorded, false if already present. */
        synchronized boolean recordHide(String videoId, long now) {
            loadIfNeeded();
            purgeOlderThan(now - HIDES_24H_WINDOW_MS);

            if (data.containsKey(videoId)) {
                return false;
            }

            data.put(videoId, now);
            if (data.size() > MAX_TRACKED_VIDEOS) {
                evictEldest();
            }
            save();
            return true;
        }

        synchronized void reset() {
            data.clear();
            loaded = true;
            Settings.AISLIST_HIDES_24H.save("");
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
                    data.put(k, obj.getLong(k));
                }
            } catch (Exception ex) {
                Logger.printException(() -> "AiSList 24h store is corrupt, resetting", ex);
                data.clear();
                Settings.AISLIST_HIDES_24H.save("");
            }
        }

        private void purgeOlderThan(long threshold) {
            data.entrySet().removeIf(e -> e.getValue() < threshold);
        }

        private void evictEldest() {
            String eldestKey = null;
            long eldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, Long> e : data.entrySet()) {
                if (e.getValue() < eldestTime) {
                    eldestTime = e.getValue();
                    eldestKey = e.getKey();
                }
            }
            if (eldestKey != null) data.remove(eldestKey);
        }

        private void save() {
            try {
                JSONObject obj = new JSONObject();
                for (Map.Entry<String, Long> e : data.entrySet()) {
                    obj.put(e.getKey(), e.getValue());
                }
                Settings.AISLIST_HIDES_24H.save(obj.toString());
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to save AiSList 24h store", ex);
            }
        }
    }

    /**
     * Returns the size of the 24h tracker after purging expired entries.
     * Used by the stats preference UI.
     */
    public static int hidesInLast24Hours() {
        return sharedTracker.size(System.currentTimeMillis());
    }

    /** Clears the 24h tracker. Called from the reset dialog. */
    public static void resetHidesTracker() {
        sharedTracker.reset();
    }

    /** Shared static reference so the UI can query without holding a filter instance. */
    private static final HidesTracker sharedTracker = new HidesTracker();

    @Override
    protected void onHideConfirmed(@NonNull String matched) {
        // Stats already recorded inside matchBuffer where the buffer is available.
    }
}
