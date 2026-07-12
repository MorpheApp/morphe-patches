/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1972
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.youtube.shared.NavigationBar.NavigationButton;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.shared.ByteTrieSearch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.TrieSearch;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferHideStatsTracker;
import app.morphe.extension.shared.patches.components.BufferPhraseFilter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.LongSetting;
import app.morphe.extension.youtube.patches.utils.requests.AiSListRequester;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings({"unused", "unchecked"})
public final class AiSListFilter extends BufferPhraseFilter {

    /** Refresh the cached list from GitHub raw after this long since last successful fetch. */
    private static final long REFRESH_CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000L; // 4 hours.

    private volatile ByteTrieSearch blocklistSearch;
    private volatile ByteTrieSearch warnlistSearch;
    private volatile String lastBlocklistParsed;
    private volatile String lastWarnlistParsed;

    private final AtomicLong lastRefreshCheckMs = new AtomicLong(0);

    public AiSListFilter() {
        super();
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
        ByteTrieSearch bl = blocklistSearch;
        final boolean blActive = blocklistActiveForFeedContext();
        if (bl != null && blActive) {
            MutableReference<String> ref = new MutableReference<>();
            if (bl.matches(buffer, ref)) {
                recordHide(matchedGroup, buffer);
                return ref.value;
            }
        }

        ByteTrieSearch wl = warnlistSearch;
        final boolean wlActive = warnlistActiveForFeedContext();
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
        String videoId = extractVideoIdFromBuffer(buffer);
        // If ID extraction fails the hide still happens; only stats are skipped
        // to avoid double-counting the same card as it re-enters the viewport.
        if (videoId == null) return;

        if (sharedTracker.recordHide(videoId, source, System.currentTimeMillis())) {
            LongSetting counter = allTimeCounterFor(source);
            if (counter != null) counter.save(counter.get() + 1);
        }
    }

    private Source detectSource(StringFilterGroup matchedGroup) {
        // Player fullscreen: treat under-video results as home (mirrors activeFor).
        if (PlayerType.getCurrent().isMaximizedOrFullscreen()) return Source.HOME;
        if (NavigationBar.isSearchBarActive()) return Source.SEARCH;
        NavigationButton nav = NavigationButton.getSelectedNavigationButton();
        return nav == NavigationButton.SUBSCRIPTIONS ? Source.SUBSCRIPTIONS : Source.HOME;
    }

    @Nullable
    private static LongSetting allTimeCounterFor(Source source) {
        return switch (source) {
            case HOME -> Settings.AISLIST_HIDE_COUNT_HOME;
            case SEARCH -> Settings.AISLIST_HIDE_COUNT_SEARCH;
            // Subscription feed and comments are not filtered by AiSList, so no counter exists.
            case SUBSCRIPTIONS, COMMENTS -> null;
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
    private static final BufferHideStatsTracker sharedTracker =
            new BufferHideStatsTracker(Settings.AISLIST_HIDES_24H);

    @Override
    protected void onHideConfirmed(String matched) {
        // Stats already recorded inside matchBuffer where the buffer and matched group are available.
    }
}
