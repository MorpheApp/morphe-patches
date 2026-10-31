/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import app.morphe.extension.shared.settings.BaseActivityHook;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.Log;

import androidx.annotation.Nullable;

import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.LithoRelayoutPatch;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Translates live chat and live chat replay messages.
 *
 * <p>On YouTube 21.20+, live chat rows are rendered via Litho ({@code live_chat_attributed_text.eml}).
 * Each row's {@link Spanned} combines the author name (`sans-serif-medium`), badges/spacer icons
 * ({@link ReplacementSpan}), and the message body (`sans-serif-regular`).
 *
 * <p>{@link #onLithoTextLoaded(ContextInterface, CharSequence)} locates the message body range after
 * the author/badge prefix, preserves all prefix and inline spans, queues async translation off the
 * main thread, and triggers {@link LithoRelayoutPatch#relayoutOutdatedTexts()} when the translation
 * arrives.
 *
 * <p><b>Busy chats.</b> A crowded stream can mount hundreds of rows in a single burst, and a replay
 * keeps mounting rows while the user scrolls. Translating every mounted row saturates the main thread
 * and the network, which makes the chat stutter. By default a row is only queued for translation once
 * it is actually shown on screen ({@link Settings#LIVE_CHAT_TRANSLATE_ONLY_VISIBLE}), the worker queue
 * is bounded, and requests are rate limited. Rows that are not translated yet keep a pending span and
 * are retried on the next scan, so nothing is lost.
 */
@SuppressWarnings("unused")
public final class LiveChatTranslatePatch {

    private static final String TAG = "LCTranslate";

    private static final String TRANSLATE_ENDPOINT =
            "https://translate.googleapis.com/translate_a/single"
                    + "?client=gtx&dt=t&sl=%s&tl=%s&q=%s";

    private static final int MAX_CACHE_ENTRIES = 2000;
    private static final int DEFAULT_TRANSLATE_COLOR = 0xFF38BDF8;

    /** Queued translations. Kept small so a burst of chat rows cannot build a deep backlog. */
    private static final int WORK_QUEUE_CAPACITY = 24;
    /**
     * Two workers are enough for the endpoint to answer before the next row arrives, and fewer
     * threads means less contention with the chat's own layout and drawing threads.
     */
    private static final int WORKER_THREADS = 2;
    /**
     * Upper bound of requests per second. A busy chat shows far fewer new rows per second than this,
     * and going higher mostly risks throttling from the endpoint, which shows up as a stalling chat.
     */
    private static final int MAX_REQUESTS_PER_SECOND = 8;

    /** How often on-screen untranslated rows are looked for. */
    private static final long VISIBLE_SCAN_DELAY_MS = 350L;
    /** Delay used when rows are waiting on the rate limit instead of on a request already in flight. */
    private static final long VISIBLE_SCAN_SLOW_DELAY_MS = 1_200L;

    /** Letters a row needs before it counts as a system notice instead of a bare author name. */
    private static final int MIN_NOTICE_LETTERS = 16;

    /** Sentinel of {@code morphe_live_chat_translate_max_length}: translate any length. */
    public static final int MAX_LENGTH_UNLIMITED = 0;

    /** Marks already-translated text so relayouts are idempotent. */
    private static final char TRANSLATED_MARK = '\u200B';

    private static final String LIVE_CHAT_ATTRIBUTED_TEXT = "live_chat_attributed_text.eml";
    private static final String LIVE_CHAT_PAID_MESSAGE = "live_chat_paid_message.eml";
    /**
     * Text components that hold the words of a chat row. Regular messages, gift/membership rows and
     * Super Chat rows all end up in {@code live_chat_attributed_text.eml} (the Super Chat card is
     * {@code live_chat_text_message_banner.eml|...|live_chat_banner.eml|...} above it), so the
     * banner must not be excluded by name or the whole Super Chat row is skipped.
     */
    private static final String TICKER_PATH = "ticker";
    /** Every live chat row path starts with this component. */
    private static final String LIVE_CHAT_PREFIX = "live_chat";
    /** How deep into the path {@link #LIVE_CHAT_PREFIX} is searched for. */
    private static final int FAST_PATH_LIMIT = 160;
    /**
     * Views laid out per relayout pass. Laying out every freshly translated row in one frame is what
     * makes a busy chat stutter, so the translations that finish close together are released to the
     * relayout a few at a time instead of all at once.
     */
    private static final int MAX_RELAYOUT_VIEWS_PER_PASS = 4;
    /** Spacing between two release groups. */
    private static final long RELAYOUT_SPREAD_MS = 150L;

    /** Earliest time each finished translation may be written into its row. */
    private static final ConcurrentHashMap<String, Long> applyAt = new ConcurrentHashMap<>();
    private static final AtomicInteger spreadSlot = new AtomicInteger();
    private static volatile long spreadWindowStart;

    /**
     * Schedules a finished translation for display. Translations that finish in the same second are
     * released in small groups so the chat does not have to lay out a dozen rows in a single frame,
     * which is what makes a busy chat stutter.
     */
    private static void markReady(String key) {
        final long now = System.currentTimeMillis();
        if (now - spreadWindowStart > 1_000L) {
            spreadWindowStart = now;
            spreadSlot.set(0);
        }
        pruneApplyAt(now);
        final long delay = (spreadSlot.getAndIncrement() / MAX_RELAYOUT_VIEWS_PER_PASS) * RELAYOUT_SPREAD_MS;
        applyAt.put(key, now + delay);
    }

    private static boolean isApplyDue(String key) {
        final Long at = applyAt.get(key);
        return at == null || System.currentTimeMillis() >= at;
    }

    /** @return If a finished translation is waiting for its turn to be written into its row. */
    /**
     * Drops the entries whose turn already came, so a row that is gone before its turn (scrolled out
     * of the list) cannot leave an entry behind.
     */
    private static void pruneApplyAt(long now) {
        if (applyAt.isEmpty()) {
            return;
        }
        applyAt.values().removeIf(at -> at == null || at <= now);
    }

    private static boolean hasRowWaitingToApply() {
        final long now = System.currentTimeMillis();
        for (Long at : applyAt.values()) {
            if (at != null && at > now) {
                return true;
            }
        }
        return false;
    }
    /**
     * Chat components whose text must never be translated: the header, the ticker overlay, the
     * filter menu and the dialog buttons hold UI labels rather than messages.
     *
     * <p>A list of allowed components was used before, which silently dropped every row whose
     * component was not listed: Super Chat cards, paid message cards and membership rows all use
     * their own component and were never translated.
     */
    private static final String[] SKIPPED_CHAT_PATHS = {
            "ticker",
            // Only the chat header itself. "live_chat_item_header" is an ancestor of every Super Chat
            // card, so matching "header" by substring dropped all of them.
            "live_chat_header",
            "filter_mode",
            "filter_menu",
            "input_panel",
            "input_action",
            "context_menu",
            "dialog",
            "overlay",
            "button_inner",
            "avatar.eml",
            "emoji_picker",
            // Super Chat: the amount, the currency and the elapsed time are not messages.
            "super_chat_item_header_subtext",
    };

    /**
     * Paths that are skipped only when they appear inside a Super Chat card. A normal chat row keeps
     * its author and message in {@code live_chat_attributed_text}, while inside a Super Chat the same
     * component holds the author name and the message sits elsewhere.
     */
    private static final String[] SKIPPED_IN_SUPER_CHAT = {
            "live_chat_attributed_text",
    };

    /**
     * The Super Chat message itself. It is the text of the card outside its header, and it is the
     * only part of a Super Chat that should be translated.
     */
    private static final String SUPER_CHAT_ITEM = "super_chat_item.eml";
    private static final Pattern EMOTE_SHORTCODE = Pattern.compile(":[^:\\s]{1,20}:");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /** Translated text keyed by target language + original message text. */
    private static final ConcurrentHashMap<String, String> translationCache =
            new ConcurrentHashMap<>(MAX_CACHE_ENTRIES);

    /** Lookups currently in flight. */
    private static final ConcurrentHashMap<String, Boolean> pendingLookups =
            new ConcurrentHashMap<>();

    /** Messages that equal their translation or returned empty, so we never re-fetch them. */
    private static final ConcurrentHashMap<String, Boolean> skippedLookups =
            new ConcurrentHashMap<>(MAX_CACHE_ENTRIES);

    /**
     * Results of {@link #onLithoTextLoaded(ContextInterface, CharSequence)} for the exact same
     * CharSequence instance, so the repeated Litho lookups of an already handled row cost a hash
     * lookup instead of a full re-parse. Only final results are stored, never pending ones.
     */
    private static final Map<CharSequence, CachedResult> instanceResults =
            Collections.synchronizedMap(new IdentityHashMap<>());
    /**
     * Set once a chat row has been seen, so the common case of an app with no chat open returns
     * before any of the work below. Entering fullscreen on a normal video creates hundreds of texts
     * and must not pay for the chat checks.
     */
    private static final int MAX_INSTANCE_RESULTS = 1024;

    /** {@link Settings#LIVE_CHAT_TRANSLATE_MAX_LENGTH}, refreshed with the config hash. */
    private static volatile int cachedMaxLength = MAX_LENGTH_UNLIMITED;

    private static final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            WORKER_THREADS,
            WORKER_THREADS,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(WORK_QUEUE_CAPACITY),
            runnable -> {
                final Thread thread = new Thread(runnable, "LCTranslate");
                thread.setDaemon(true);
                return thread;
            },
            (runnable, pool) -> {
                throw new RejectedExecutionException("live chat translate queue full");
            });

    private static final AtomicInteger failures = new AtomicInteger();
    private static volatile long lastFailureTimestamp = 0L;
    private static final long FAILURE_BACKOFF_MS = 10_000L;

    private static final AtomicLong rateWindowStart = new AtomicLong();
    private static final AtomicInteger rateWindowCount = new AtomicInteger();

    private static volatile String targetLanguage = null;
    private static volatile boolean hookVerified = false;

    /** Cached {@link #currentConfigHash()}, so settings are not re-read for every chat row. */
    private static volatile int cachedConfigHash = 0;
    private static volatile long cachedConfigHashAt = 0L;
    private static final long CONFIG_HASH_TTL_MS = 500L;

    /** If a scan of the on-screen rows is already pending. */
    private static final AtomicInteger scanScheduled = new AtomicInteger();
    private static final AtomicInteger skipLogs = new AtomicInteger();
    private static final AtomicInteger entryLogs = new AtomicInteger();

    /** Marks a host that already has the copy listener, so it is not attached twice. */
    private static final int COPY_TAG_ID = 0x4C435452;
    /** Resource ids already resolved by {@link #isLiveChatId(View, int)}, so it runs once per id. */
    private static final Map<Integer, Boolean> liveChatIds = new HashMap<>();
    private static final AtomicInteger copyListenerScheduled = new AtomicInteger();
    private static volatile String lastSkipLog;
    /** Component paths of live chat texts that were not translated, so each one is logged once. */
    private static final Set<String> unknownPaths = ConcurrentHashMap.newKeySet();

    /**
     * Last time a live chat row was handed to this patch. The chat list stops creating rows once it
     * is closed, so a quiet period means nothing is on screen any more and all work must stop:
     * translating and relaying out rows of a closed chat only costs battery and frames.
     */
    private static volatile long lastChatRowAt;
    private static final long CHAT_IDLE_MS = 4_000L;


    /** If the Litho measurement listener was installed. */
    private static final AtomicInteger listenerInstalled = new AtomicInteger();

    /**
     * Minimum time between two relayout requests. Every relayout scans all mounted texts of the app,
     * so a busy chat that finishes a dozen translations per second must not trigger a dozen scans.
     */
    private static final long RELAYOUT_THROTTLE_MS = 120L;
    private static final AtomicLong lastRelayoutAt = new AtomicLong();
    private static final AtomicInteger relayoutPending = new AtomicInteger();
    private static final AtomicInteger relayoutRetry = new AtomicInteger();

    /** Parsed color of {@link Settings#LIVE_CHAT_TRANSLATE_COLOR}, so it is not parsed per row. */
    private static volatile String cachedColorHex = null;
    private static volatile int cachedColorValue = DEFAULT_TRANSLATE_COLOR;

    public enum TranslationStyle {
        /** Only the translation is shown. */
        TRANSLATED_ONLY,
        /** The original, then the translation in parentheses: {@code original (translated)}. */
        ORIGINAL_AND_TRANSLATED,
        /** The translation, then the original in parentheses: {@code (translated) original}. */
        TRANSLATED_AND_ORIGINAL,
    }

    private static final class CachedResult {
        final int configHash;
        final CharSequence result;

        CachedResult(int configHash, CharSequence result) {
            this.configHash = configHash;
            this.result = result;
        }
    }

    /**
     * Attached to a chat row's Spanned while its translation is being fetched in the background.
     * When the fetch completes, {@link #isOutdated()} becomes true and {@link LithoRelayoutPatch}
     * forces the row's LithoView to relayout.
     */
    /**
     * Base of every span this patch puts on a chat row, so a single {@code getSpans} lookup tells
     * whether the row was already handled.
     */
    private abstract static class ChatRowSpan implements LithoRelayoutPatch.RelayoutSpan {
        /**
         * A chat row may only be laid out again while it is still shown inside the live chat list.
         * The text drawable is registered globally, so a view that was recycled into another screen
         * still holds this span; laying it out there changed that screen, which showed as a gap on
         * the Home and Store pages.
         */
        @Override
        public boolean canRelayoutHost(View host) {
            return host.isShown() && isInLiveChat(host);
        }
    }

    private static final class PendingChatSpan extends ChatRowSpan {
        final CharSequence originalText;
        final int msgStart;
        final String messageBody;
        final String language;
        final String cacheKey;

        PendingChatSpan(CharSequence originalText, int msgStart, String messageBody,
                        String language, String cacheKey) {
            this.originalText = originalText;
            this.msgStart = msgStart;
            this.messageBody = messageBody;
            this.language = language;
            this.cacheKey = cacheKey;
        }

        @Override
        public boolean isOutdated() {
            // Only a finished translation asks for a relayout. A skipped lookup keeps the row as it
            // is, otherwise the row would report itself as outdated on every single relayout and
            // the chat would keep laying itself out while nothing changes.
            return translationCache.containsKey(cacheKey) && isApplyDue(cacheKey);
        }
    }

    /**
     * Attached to an already-translated chat row's Spanned so that if the user changes style,
     * bold, color, or target language in settings, cached rows automatically rebuild with the
     * updated appearance.
     */
    private static final class TranslatedChatSpan extends ChatRowSpan {
        final CharSequence originalText;
        final int msgStart;
        final String messageBody;
        final int configHash;

        TranslatedChatSpan(CharSequence originalText, int msgStart, String messageBody, int configHash) {
            this.originalText = originalText;
            this.msgStart = msgStart;
            this.messageBody = messageBody;
            this.configHash = configHash;
        }

        @Override
        public boolean isOutdated() {
            return configHash != currentConfigHash();
        }
    }

    private static int currentConfigHash() {
        final long now = System.currentTimeMillis();
        if (now - cachedConfigHashAt < CONFIG_HASH_TTL_MS) {
            return cachedConfigHash;
        }
        final int hash = Objects.hash(
                getTargetLanguage(),
                getSourceLanguage(),
                Settings.LIVE_CHAT_TRANSLATE_STYLE.get(),
                Settings.LIVE_CHAT_TRANSLATE_BOLD.get(),
                Settings.LIVE_CHAT_TRANSLATE_USE_COLOR.get(),
                Settings.LIVE_CHAT_TRANSLATE_COLOR.get()
        );
        cachedMaxLength = Settings.LIVE_CHAT_TRANSLATE_MAX_LENGTH.get();
        cachedConfigHash = hash;
        cachedConfigHashAt = now;
        return hash;
    }

    /**
     * @return If the Litho path belongs to a live chat row that may be translated.
     */
    /**
     * Allocation-free {@code CharSequence.contains(String)} limited to the first {@code limit} chars.
     */
    private static boolean containsWithin(@Nullable CharSequence haystack, String needle, int limit) {
        if (haystack == null) return false;
        final int needleLength = needle.length();
        final int searchLimit = Math.min(haystack.length(), limit + needleLength) - needleLength;
        if (searchLimit < 0) return false;
        for (int start = 0; start <= searchLimit; start++) {
            int i = 0;
            while (i < needleLength && haystack.charAt(start + i) == needle.charAt(i)) {
                i++;
            }
            if (i == needleLength) return true;
        }
        return false;
    }

    /**
     * @return If the text belongs to a chat message. Everything inside the live chat is a candidate
     *         except the components that render UI, so a row is never missed because its component
     *         name was not known when this patch was written.
     */
    private static boolean isChatRowPath(@Nullable CharSequence path) {
        if (path == null) return false;
        for (String skipped : SKIPPED_CHAT_PATHS) {
            if (containsAny(path, skipped)) {
                return false;
            }
        }
        if (containsAny(path, SUPER_CHAT_ITEM)) {
            // Inside a Super Chat card the message is the card's own text, not the attributed text
            // that holds the author name.
            for (String skipped : SKIPPED_IN_SUPER_CHAT) {
                if (containsAny(path, skipped)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * @return If the text is the message of a Super Chat card. The card holds several texts, and only
     *         this one is written by the viewer.
     */
    private static boolean isSuperChatMessage(@Nullable CharSequence path) {
        return path != null && containsAny(path, SUPER_CHAT_ITEM);
    }

    /**
     * Allocation-free {@code CharSequence.contains(String)}.
     */
    private static boolean containsAny(@Nullable CharSequence haystack, String needle) {
        if (haystack == null) return false;
        final int needleLength = needle.length();
        final int limit = haystack.length() - needleLength;
        if (limit < 0) return false;
        for (int start = 0; start <= limit; start++) {
            int i = 0;
            while (i < needleLength && haystack.charAt(start + i) == needle.charAt(i)) {
                i++;
            }
            if (i == needleLength) return true;
        }
        return false;
    }

    /**
     * Logs a callback that returned before any chat specific check, which is what shows whether the
     * hook is reached at all for a component. Only paths that look like live chat are logged.
     */
    private static void logEntry(String reason, @Nullable ContextInterface contextInterface, CharSequence text) {
        if (entryLogs.getAndIncrement() > 8) {
            return;
        }
        String path = "no path";
        try {
            final StringBuilder builder = contextInterface == null
                    ? null : contextInterface.patch_getPathBuilder();
            if (builder != null) {
                path = builder.toString();
            }
        } catch (Exception ignored) {
            path = "path failed";
        }
        if (!path.contains("live_chat") && !path.equals("no path")) {
            return;
        }
        log("entry " + reason + " path=" + abbreviate(path) + " len=" + text.length());
    }

    /** Logs why a chat row was left untranslated, a few times per session. */
    /**
     * Logs a live chat text whose component is not one of {@link #LIVE_CHAT_ROW_PATHS}, once per
     * distinct component. This is how a row that is never translated is identified.
     */
    private static void logUnknownChatPath(CharSequence path) {
        if (unknownPaths.size() > 12) {
            return;
        }
        // Only the component names matter, not the per instance ids that follow them.
        final StringBuilder components = new StringBuilder();
        final String value = path.toString();
        for (String part : value.split("\\|")) {
            if (!part.endsWith(".eml") && !part.equals("TextType") && !part.equals("CellType")
                    && !part.equals("ContainerType")) {
                continue;
            }
            if (components.length() > 0) components.append('|');
            components.append(part);
            if (components.length() > 120) break;
        }
        final String key = components.toString();
        if (key.isEmpty() || unknownPaths.contains(key)) {
            return;
        }
        unknownPaths.add(key);
        log("unknown chat path: " + key);
    }

    /**
     * @return The text with every non ASCII character written as its code point, so a row whose
     *         emoji cannot be displayed in the log can still be identified.
     */
    private static String escapeNonAscii(@Nullable CharSequence text) {
        if (text == null) return "null";
        final StringBuilder sb = new StringBuilder(Math.min(text.length() * 6, 400));
        for (int i = 0; i < text.length() && sb.length() < 360; i++) {
            final char c = text.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                sb.append(c);
            } else {
                sb.append(String.format(Locale.US, "\\u%04X", (int) c));
            }
        }
        if (text.length() > sb.length()) {
            sb.append("...");
        }
        return sb.toString();
    }

    /**
     * @return The span classes of the text with their ranges, which shows how a row is built and
     *         where the message body starts.
     */
    private static String describeSpans(@Nullable CharSequence text) {
        if (!(text instanceof Spanned spanned)) {
            return "not spanned";
        }
        final StringBuilder sb = new StringBuilder();
        for (Object span : spanned.getSpans(0, spanned.length(), Object.class)) {
            if (sb.length() > 300) {
                sb.append("...");
                break;
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append(span.getClass().getSimpleName())
                    .append('[').append(spanned.getSpanStart(span))
                    .append('-').append(spanned.getSpanEnd(span)).append(']');
        }
        return sb.length() == 0 ? "no spans" : sb.toString();
    }

    private static void logSkip(String reason, CharSequence text) {
        if (skipLogs.get() > 12) {
            return;
        }
        final String line = reason + " | " + escapeNonAscii(text);
        // The same row is loaded twice by Litho, so identical lines are logged once.
        if (line.equals(lastSkipLog)) {
            return;
        }
        lastSkipLog = line;
        skipLogs.incrementAndGet();
        log("skip (" + line + ")");
    }

    private static void log(String message) {
        Log.i(TAG, message);
        Logger.printDebug(() -> message);
    }

    /**
     * Injection point for Litho text creation and lookup (`hookLithoSpannableString`).
     */
    public static CharSequence onLithoTextLoaded(
            @Nullable ContextInterface contextInterface,
            @Nullable CharSequence text) {
        if (text == null || contextInterface == null) {
            if (text != null) {
                logEntry("null context", contextInterface, text);
            }
            return text;
        }
        if (!Settings.LIVE_CHAT_TRANSLATE.get()) {
            logEntry("setting off", contextInterface, text);
            return text;
        }

        try {
            // The path is checked before anything else. This hook runs for every text the app
            // creates, and a config hash read or a cache lookup here costs the whole app: entering
            // or leaving fullscreen creates hundreds of texts and was felt as stutter.
            final StringBuilder pathBuilder = contextInterface.patch_getPathBuilder();
            if (pathBuilder == null) {
                return text;
            }
            // A chat row always has live_chat in the head of its component path. Looking only there
            // keeps this hook cheap for the thousands of other texts the app creates; the full
            // needle scan below would walk the whole (sometimes ~1 kB) path for every one of them.
            if (!containsWithin(pathBuilder, LIVE_CHAT_PREFIX, FAST_PATH_LIMIT)) {
                return text;
            }

            final int len = text.length();
            if (len < 2) {
                return text;
            }

            final int cfgHash = currentConfigHash();

            final CachedResult cached = instanceResults.get(text);
            if (cached != null) {
                if (cached.configHash == cfgHash && !isStalePending(cached.result)) {
                    return cached.result;
                }
                instanceResults.remove(text);
            }
            if (!isChatRowPath(pathBuilder)) {
                // A live chat text that is not in an allowlisted component. Logging the path is what
                // shows which component a row that never gets translated belongs to.
                logUnknownChatPath(pathBuilder);
                return text;
            }
            lastChatRowAt = System.currentTimeMillis();
            // Ticker overlays live outside the chat list, where a relayout leaves the card blank,
            // so they are left alone. Super Chat rows are built on live_chat_banner.eml, but their
            // text still comes from live_chat_attributed_text.eml, so the banner must not be
            // excluded by name or the whole Super Chat row would be skipped.
            if (containsAny(pathBuilder, TICKER_PATH)) {
                return text;
            }

            if (!hookVerified) {
                hookVerified = true;
                log("Litho text hook verified on path: " + pathBuilder);
            }

            final Spanned spanned = text instanceof Spanned spannedText ? spannedText : null;
            CharSequence baseText = text;
            int msgStart = -1;
            String messageBody = null;
            String language = null;
            boolean alreadyHandled = false;
            boolean hasPendingSpan = false;
            // A body found from a span is known to be a message, so the word count check that only
            // exists to reject bare author names is not applied to it.
            boolean structuredBody = false;

            if (spanned != null) {
                // One lookup for both span types: this runs during Litho layout, so every extra
                // getSpans call here is a frame-time cost on a busy chat.
                ChatRowSpan[] own = spanned.getSpans(0, len, ChatRowSpan.class);
                if (own != null && own.length > 0) {
                    alreadyHandled = true;
                    if (own[0] instanceof TranslatedChatSpan tSpan) {
                        if (tSpan.configHash == cfgHash) {
                            remember(text, cfgHash, text);
                            return text;
                        }
                        baseText = tSpan.originalText;
                        msgStart = tSpan.msgStart;
                        messageBody = tSpan.messageBody;
                    } else {
                        PendingChatSpan span = (PendingChatSpan) own[0];
                        baseText = span.originalText;
                        msgStart = span.msgStart;
                        messageBody = span.messageBody;
                        language = span.language;
                        hasPendingSpan = true;
                    }
                }
            }

            if (!alreadyHandled) {
                if (containsChar(text, TRANSLATED_MARK)) {
                    return text;
                }
                msgStart = spanned != null ? findMessageStart(spanned) : 0;
                structuredBody = msgStart > 0;
            }

            if (msgStart < 0 || msgStart >= baseText.length()) {
                logSkip("no message body", text);
                if (skipLogs.get() <= 4) {
                    log("spans: " + describeSpans(text));
                }
                return text;
            }

            if (messageBody == null) {
                messageBody = extractCleanMessage(baseText, msgStart);
            }
            if (!shouldTranslate(messageBody, structuredBody)) {
                logSkip("not translatable", baseText);
                remember(text, cfgHash, baseText);
                return baseText;
            }

            if (language == null) {
                language = getTargetLanguage();
            }
            final String key = cacheKey(messageBody, language);

            if (hasPendingSpan && !translationCache.containsKey(key)) {
                // The row already carries its pending span and its translation has not arrived yet.
                // Returning the existing text avoids a new SpannableString (and a new span) every
                // time Litho loads the text again, which happens on every relayout.
                if (skippedLookups.containsKey(key)) {
                    // This row is not translated after all (same text, empty, or excluded). The
                    // pending marker is dropped so the row stops reporting itself as outdated,
                    // which otherwise keeps a relayout running several times per second forever.
                    remember(text, cfgHash, baseText);
                    return baseText;
                }
                // The request is made here rather than only from the visible scan: the scan can run
                // before the row is mounted, and relying on it left rows untranslated forever.
                // queueTranslation is idempotent and the rate limit bounds how much is sent.
                queueTranslation(messageBody, language, key);
                scheduleVisibleScan();
                return text;
            }

            if (skippedLookups.containsKey(key)) {
                return baseText;
            }

            final String translated = translationCache.get(key);
            if (translated != null) {
                final CharSequence result =
                        buildTranslatedSpannable(baseText, msgStart, messageBody, translated, cfgHash);
                remember(text, cfgHash, result);
                scheduleCopyListener();
                return result;
            }

            queueTranslation(messageBody, language, key);
            scheduleCopyListener();
            final SpannableString pending =
                    attachPendingSpan(baseText, new PendingChatSpan(baseText, msgStart, messageBody, language, key));
            // Cached too, so a repeated lookup of this row costs one map hit instead of another
            // span walk while the translation is still in flight.
            remember(text, cfgHash, pending);
            // Translated rows are mounted outside of the chat list too, but only on-screen rows are
            // scanned, so the request is made when the row is visible.
            scheduleVisibleScan();
            return pending;
        } catch (Exception ex) {
            Log.e(TAG, "onLithoTextLoaded failure", ex);
            Logger.printException(() -> "onLithoTextLoaded failure", ex);
            return text;
        }
    }

    /**
     * @return If the result is a row that is still waiting on its translation, but the translation
     *         is available now. Such a result must not be reused, or the row would stay untranslated.
     */
    private static boolean isStalePending(@Nullable CharSequence result) {
        if (!(result instanceof Spanned spanned)) {
            return false;
        }
        PendingChatSpan[] pending = spanned.getSpans(0, spanned.length(), PendingChatSpan.class);
        return pending != null && pending.length > 0 && pending[0].isOutdated();
    }

    private static void remember(CharSequence input, int configHash, CharSequence result) {
        // Enables the relayout hooks for a while, as they otherwise return immediately for every
        // text of the app. A permanent flag made entering and leaving fullscreen stutter.
        LithoRelayoutPatch.onRelayoutSpanRegistered();
        synchronized (instanceResults) {
            if (instanceResults.size() >= MAX_INSTANCE_RESULTS) {
                // Dropping the whole cache would re-parse every visible row at once, which is a
                // frame spike. Evicting a slice keeps the cache warm for the rows still on screen.
                evictSlice(instanceResults, MAX_INSTANCE_RESULTS / 4);
            }
            instanceResults.put(input, new CachedResult(configHash, result));
        }
    }

    /**
     * Removes up to {@code count} entries. Both caches are unordered, so any entry is as good as
     * another one to drop.
     */
    private static <K, V> void evictSlice(Map<K, V> map, int count) {
        final Iterator<K> keys = map.keySet().iterator();
        for (int removed = 0; removed < count && keys.hasNext(); removed++) {
            keys.next();
            keys.remove();
        }
    }

    /**
     * Schedules a pass that queues the on-screen rows which are still untranslated.
     * Called on any thread; the scan itself runs on the main thread.
     */
    private static void scheduleVisibleScan() {
        scheduleVisibleScan(VISIBLE_SCAN_DELAY_MS);
    }

    /**
     * @return If no chat row was seen for a while, so no row can be translated or laid out any more.
     *         Cheap enough for the per row paths, as it only reads a timestamp.
     */
    private static boolean isChatIdle() {
        final long last = lastChatRowAt;
        return last == 0L || System.currentTimeMillis() - last > CHAT_IDLE_MS;
    }

    /**
     * @return If the chat list is gone. Only called from the scan, which runs on the main thread at
     *         most every few hundred milliseconds, as it walks the mounted texts to tell a closed
     *         chat from a paused one where no new row is created.
     */
    private static boolean isChatListGone() {
        if (isChatIdle()) {
            return true;
        }
        for (Object[] entry : LithoRelayoutPatch.findShownTexts()) {
            if (!(entry[1] instanceof Spanned spanned)) {
                continue;
            }
            if (spanned.getSpans(0, spanned.length(), ChatRowSpan.class).length > 0) {
                return false;
            }
        }
        return true;
    }

    private static void scheduleVisibleScan(long delayMs) {
        if (!Settings.LIVE_CHAT_TRANSLATE_ONLY_VISIBLE.get() || isChatIdle()) {
            return;
        }
        if (scanScheduled.compareAndSet(0, 1)) {
            Utils.runOnMainThreadDelayed(LiveChatTranslatePatch::runVisibleScan, delayMs);
        }
    }

    /**
     * Attaches a long press listener to the view that shows a chat row, so the message can be copied.
     * Called on the main thread for the rows the visible scan found.
     *
     * <p>The listener is attached once per host. Copying uses the original text, so the copied
     * message is the one the streamer wrote, not the translation.
     */
    /**
     * Attaches the copy listener to a chat row.
     *
     * <p>The listener is re-attached on every scan instead of once, because the list rebinds views
     * while scrolling and YouTube sets its own long press listener when it does. That is why the
     * copy only worked some of the time. The message is refreshed each time for the same reason: a
     * recycled view can be reused for a different row.
     */
    private static void attachCopyListener(View host, String originalMessage) {
        if (host == null || originalMessage == null || originalMessage.isEmpty()) {
            return;
        }
        // Re-attached on every scan, so a view rebound by the list gets its listener back. The
        // message is stored with it because a recycled view can be reused for a different row.
        host.setTag(COPY_TAG_ID, originalMessage);
        host.setOnLongClickListener(view -> {
            copyToClipboard(originalMessage);
            showCopiedToast();
            return true;
        });
        // Only longClickable is set. Marking the host clickable makes it draw a ripple and reserve
        // room for one, which changed the row height and showed up as a gap on other screens that
        // reuse the same Litho view.
        host.setLongClickable(true);
    }

    /**
     * Attaches the copy listener to every shown chat row. The host of a row is the view its text
     * drawable draws into, which is the view a long press lands on.
     */
    /**
     * Schedules the pass that attaches the copy listener. It runs on the main thread, and only once
     * at a time, so a chat that creates rows quickly does not queue a pass per row.
     */
    private static void scheduleCopyListener() {
        if (copyListenerScheduled.compareAndSet(0, 1)) {
            Utils.runOnMainThreadDelayed(() -> {
                copyListenerScheduled.set(0);
                attachCopyListeners();
            }, 120L);
        }
    }

    private static void attachCopyListeners() {
        try {
            // Runs on the main thread after a row was translated. While the chat is closed there is
            // no row to attach to, so the walk over every mounted text is skipped.
            if (isChatIdle()) {
                return;
            }
            for (Object[] entry : LithoRelayoutPatch.findShownTexts()) {
                final View host = (View) entry[0];
                final CharSequence text = (CharSequence) entry[1];
                // Only rows that still carry our span are chat rows. A recycled view that now shows
                // other content has none, so it is left alone.
                if (!host.isShown() || !(text instanceof Spanned spanned)) {
                    continue;
                }
                final ChatRowSpan[] spans = spanned.getSpans(0, spanned.length(), ChatRowSpan.class);
                if (spans == null || spans.length == 0) {
                    continue;
                }
                final String original = spans[0] instanceof TranslatedChatSpan translated
                        ? translated.messageBody
                        : spans[0] instanceof PendingChatSpan pending ? pending.messageBody : null;
                attachCopyListener(host, original);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "attachCopyListeners failure", ex);
        }
    }

    /**
     * @return If the view is inside the live chat list. The chat is the only place a span of this
     *         patch is created, so a view outside it that still holds one is a recycled view.
     */
    private static boolean isInLiveChat(View view) {
        if (isLiveChatId(view, view.getId())) {
            return true;
        }
        View parent = view.getParent() instanceof View p ? p : null;
        for (int depth = 0; parent != null && depth < 12; depth++) {
            if (isLiveChatId(view, parent.getId())) {
                return true;
            }
            parent = parent.getParent() instanceof View p ? p : null;
        }
        return false;
    }

    /**
     * @return If the resource name of the id contains {@code live_chat}. An id that is not in the
     *         resource table is not a live chat view; {@code getResourceEntryName} throws for
     *         {@link View#NO_ID}, which used to log an exception and show a toast.
     */
    private static boolean isLiveChatId(View view, int id) {
        if (id == View.NO_ID || id == 0) {
            return false;
        }
        final Boolean cached = liveChatIds.get(id);
        if (cached != null) {
            return cached;
        }
        boolean result;
        try {
            final String name = view.getResources().getResourceEntryName(id);
            result = name != null && name.contains("live_chat");
        } catch (Exception ignored) {
            result = false;
        }
        liveChatIds.put(id, result);
        return result;
    }

    private static void copyToClipboard(String text) {
        try {
            final Context context = Utils.getContext();
            final ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("live chat", text));
        } catch (Exception ex) {
            Logger.printException(() -> "copyToClipboard failure", ex);
        }
    }

    private static void showCopiedToast() {
        try {
            Utils.showToastShort("Message copied");
        } catch (Exception ignored) {
            // The clipboard write is what matters.
        }
    }

    /** Runs on the main thread. */
    private static void runVisibleScan() {
        scanScheduled.set(0);
        if (isChatListGone()) {
            // The chat was closed. A translation that already arrived is still written into its row,
            // as the row can be on screen in a paused replay where no new rows are created. Then the
            // queued requests are dropped so nothing is sent for a chat that is gone.
            if (hasRowWaitingToApply()) {
                requestRelayout();
            }
            executor.getQueue().clear();
            pendingLookups.clear();
            return;
        }

        Set<PendingChatSpan> shown = Collections.emptySet();
        try {
            // Only mounted, shown rows are returned, so a long replay backlog costs nothing.
            shown = LithoRelayoutPatch.findShownSpans(PendingChatSpan.class);
        } catch (Exception ex) {
            Logger.printException(() -> "runVisibleScan failure", ex);
        }

        int pendingOnScreen = 0;
        for (PendingChatSpan span : shown) {
            if (translationCache.containsKey(span.cacheKey)
                    || skippedLookups.containsKey(span.cacheKey)
                    || pendingLookups.containsKey(span.cacheKey)) {
                continue;
            }
            pendingOnScreen++;
            // The request rate and the queue size already bound how much is sent, so a seek that
            // mounts hundreds of rows costs the same as a burst of messages: the extra rows simply
            // stay untranslated until a later scan picks them up.
            queueTranslation(span.messageBody, span.language, span.cacheKey);
        }

        attachCopyListeners();

        if (hasRowWaitingToApply()) {
            // Rows whose translation is ready but not written yet. Without this the last groups
            // would never be laid out, as nothing else asks for a relayout.
            requestRelayout();
            scheduleVisibleScan(VISIBLE_SCAN_DELAY_MS);
        } else if (!pendingLookups.isEmpty()) {
            // Requests are still in flight, so their rows will be picked up shortly.
            scheduleVisibleScan(VISIBLE_SCAN_DELAY_MS);
        } else if (pendingOnScreen > 0) {
            // Rows are on screen but could not be queued (rate limit or full queue). Backing off
            // keeps the scan from walking the mounted texts several times per second.
            scheduleVisibleScan(VISIBLE_SCAN_SLOW_DELAY_MS);
        }
    }

    /**
     * Finds the start index of the chat message body inside a `live_chat_attributed_text.eml`
     * Spanned.
     *
     * <p>In `live_chat_attributed_text.eml`, the combined text is:
     * {@code [Author Name (sans-serif-medium)] + [Badges / Spacer (ReplacementSpan)] + [Message (sans-serif-regular)]}.
     * The message can end with custom emojis, which are {@link ReplacementSpan}s and can extend past
     * the style span that covers the words, so the body span start is used without requiring it to
     * cover the end of the text.
     */
    private static int findMessageStart(Spanned spanned) {
        final int length = spanned.length();
        final TypefaceSpan[] typefaces = spanned.getSpans(0, length, TypefaceSpan.class);

        int authorEnd = -1;
        int regularStart = -1;
        int plainStart = -1;
        if (typefaces != null) {
            for (TypefaceSpan typeface : typefaces) {
                final int start = spanned.getSpanStart(typeface);
                final int end = spanned.getSpanEnd(typeface);
                if (end <= 0 || end > length) {
                    continue;
                }
                final String family = typeface.getFamily();
                if (start <= 0) {
                    // A regular span that starts at the beginning covers the whole text, so the row
                    // is the message itself. Super Chat does that: the author name and the amount
                    // are separate components, so the only text here is the message.
                    if (family != null && (family.contains("regular") || family.equals("sans-serif"))) {
                        return 0;
                    }
                    if (end < length && end > authorEnd) {
                        authorEnd = end;
                    }
                } else {
                    if (family == null) {
                        continue;
                    }
                    if (family.contains("regular")) {
                        if (regularStart < 0 || start < regularStart) {
                            regularStart = start;
                        }
                    } else if (family.equals("sans-serif") && plainStart < 0) {
                        // Rows with custom or membership emoji are styled with the plain family
                        // instead of "...-regular". Only "...-regular" was accepted before, so
                        // those rows were treated as having no message body and were skipped.
                        plainStart = start;
                    }
                }
            }
        }
        final int bodyStart = regularStart > 0 ? regularStart : plainStart;

        if (bodyStart <= 0 && authorEnd <= 0) {
            // A row without any author/body span structure. These are gift and membership notices
            // rendered as one uniform span, and rows whose emoji styling hides the split.
            // The row is translated as a whole as long as it holds words: leaving it alone is what
            // made messages that contain emoji never get translated.
            return hasWords(spanned) ? 0 : -1;
        }

        // Inline custom emotes are ReplacementSpans; they are looked up once instead of per index.
        final ReplacementSpan[] replacements =
                spanned.getSpans(0, length, ReplacementSpan.class);
        final int[][] ranges = replacementRanges(spanned, replacements);

        int pos = bodyStart > 0 ? bodyStart : authorEnd;
        if (pos >= length) {
            return -1;
        }
        pos = skipLeadingDecorations(spanned, pos, length, ranges);
        if (pos < length) {
            return pos;
        }
        // An author prefix was found but no body after it: leave the row alone instead of
        // translating the author name as if it were the message.
        return -1;
    }

    /**
     * @return If a row without an author/body span structure is a system notice (such as a gift or
     *         membership announcement) rather than a lone author name or badge.
     */
    private static boolean hasWords(CharSequence text) {
        int letters = 0;
        for (int i = 0, length = text.length(); i < length; i++) {
            if (Character.isLetter(text.charAt(i)) && ++letters >= MIN_NOTICE_LETTERS) {
                return true;
            }
        }
        // Short rows are still translated when they contain a space, which a bare author name or a
        // badge does not. This is what lets "halo :_RirikaLove:" be translated while "John" is not.
        if (text.toString().indexOf(' ') >= 0) {
            return true;
        }
        // A custom emoji only ever appears in a message, never in an author name. Without this a
        // short row such as "ありがとう:_RirikaLove:" (5 letters, no space) was treated as an author
        // name and never translated.
        if (text instanceof Spanned spanned) {
            final int length = spanned.length();
            final ReplacementSpan[] replacements =
                    spanned.getSpans(0, length, ReplacementSpan.class);
            if (replacements != null && replacements.length > 0) {
                return true;
            }
            for (int i = 0; i < length; i++) {
                if (isEmojiCodePoint(spanned.charAt(i))) {
                    return true;
                }
            }
            // The emote can also be left as its shortcode text, such as ":_RirikaLove:".
            if (EMOTE_SHORTCODE.matcher(spanned).find()) {
                return true;
            }
        }
        return false;
    }

    /** @return The span start/end pairs of the replacement spans that were found. */
    private static int[][] replacementRanges(Spanned spanned, @Nullable ReplacementSpan[] replacements) {
        if (replacements == null || replacements.length == 0) {
            return new int[0][];
        }
        final int[][] ranges = new int[replacements.length][];
        for (int i = 0; i < replacements.length; i++) {
            ranges[i] = new int[]{
                    spanned.getSpanStart(replacements[i]),
                    spanned.getSpanEnd(replacements[i])
            };
        }
        return ranges;
    }

    private static int skipLeadingDecorations(Spanned spanned, int start, int length, int[][] ranges) {
        int pos = start;
        while (pos < length) {
            int jump = -1;
            for (int[] range : ranges) {
                if (pos >= range[0] && pos < range[1] && range[1] > jump) {
                    jump = range[1];
                }
            }
            if (jump > pos) {
                pos = jump;
                continue;
            }
            if (spanned.charAt(pos) == ' ') {
                pos++;
                continue;
            }
            break;
        }
        return pos;
    }

    /**
     * Extracts the translatable text of the message body, stripping characters covered by
     * inline {@link ReplacementSpan}s (custom channel emotes/stickers).
     */
    private static String extractCleanMessage(CharSequence baseText, int msgStart) {
        final int length = baseText.length();
        if (!(baseText instanceof Spanned spanned)) {
            return stripEmoji(baseText.subSequence(msgStart, length).toString());
        }

        final ReplacementSpan[] replacements =
                spanned.getSpans(msgStart, length, ReplacementSpan.class);
        if (replacements == null || replacements.length == 0) {
            return stripEmoji(baseText.subSequence(msgStart, length).toString());
        }

        final StringBuilder sb = new StringBuilder(length - msgStart);
        int i = msgStart;
        while (i < length) {
            int skipUntil = -1;
            for (ReplacementSpan replacement : replacements) {
                final int rStart = spanned.getSpanStart(replacement);
                final int rEnd = spanned.getSpanEnd(replacement);
                if (i >= rStart && i < rEnd && rEnd > skipUntil) {
                    skipUntil = rEnd;
                }
            }
            if (skipUntil > i) {
                // A custom emoji is a drawable. A space keeps the words around it apart.
                sb.append(' ');
                i = skipUntil;
            } else {
                sb.append(baseText.charAt(i));
                i++;
            }
        }
        final String stripped = stripEmoji(sb.toString());
        if (hasTranslatableLetters(stripped)) {
            return stripped;
        }
        // The message was nothing but emoji spans. Falling back to the raw text keeps the words of
        // a row whose emoji spans cover more than the emoji, instead of dropping the row.
        return stripEmoji(baseText.subSequence(msgStart, length).toString());
    }

    /**
     * Removes custom emote shortcodes (:_name:, including non-Latin names whose image is not
     * loaded yet) and Unicode emoji, so only the words are translated and counted for max length.
     */
    private static String stripEmoji(String text) {
        if (isPlainAscii(text)) {
            // Latin chats are the common case. Nothing can be an emoji or a shortcode here, so the
            // code point walk and both regex passes are skipped.
            return text.trim();
        }
        final String noShortcodes = text.indexOf(':') >= 0
                ? EMOTE_SHORTCODE.matcher(text).replaceAll(" ")
                : text;
        final StringBuilder sb = new StringBuilder(noShortcodes.length());
        boolean stripped = false;
        int i = 0;
        while (i < noShortcodes.length()) {
            final int cp = noShortcodes.codePointAt(i);
            i += Character.charCount(cp);
            if (isEmojiCodePoint(cp)) {
                sb.append(' ');
                stripped = true;
            } else {
                sb.appendCodePoint(cp);
            }
        }
        return WHITESPACE_RUN.matcher(stripped ? sb : noShortcodes).replaceAll(" ").trim();
    }

    /**
     * @return If every character is printable ASCII without a shortcode separator, so emoji
     *         stripping cannot change the text.
     */
    private static boolean isPlainAscii(String text) {
        for (int i = 0, length = text.length(); i < length; i++) {
            final char c = text.charAt(i);
            if (c >= 0x7F || c == ':' || c < 0x20 && c != '\t') {
                return false;
            }
        }
        return true;
    }

    private static boolean isEmojiCodePoint(int cp) {
        if (cp == 0x200D || cp == 0x20E3 || (cp >= 0xFE00 && cp <= 0xFE0F)) return true;
        if (cp >= 0x1F000 && cp <= 0x1FAFF) return true;
        if (cp >= 0x2600 && cp <= 0x27BF) return true;
        if (cp >= 0xE0020 && cp <= 0xE007F) return true;
        // Membership emoji are shipped as private use characters, which are Co and not symbols.
        // They are not text, so they must not be sent to the translator.
        if (cp >= 0xE000 && cp <= 0xF8FF) return true;
        if (cp >= 0xF0000 && cp <= 0xFFFFD) return true;
        if (cp >= 0x100000 && cp <= 0x10FFFD) return true;
        return Character.getType(cp) == Character.OTHER_SYMBOL && cp > 0x2000;
    }

    /**
     * Builds a new {@link SpannableString} that keeps the entire author/badge prefix and its spans,
     * and shows the translation in the configured order, color, and weight.
     */
    private static CharSequence buildTranslatedSpannable(
            CharSequence baseText,
            int msgStart,
            String originalMessage,
            String translated,
            int cfgHash) {
        final int length = baseText.length();
        final TranslationStyle style = Settings.LIVE_CHAT_TRANSLATE_STYLE.get();
        final Spanned spanned = baseText instanceof Spanned spannedText ? spannedText : null;

        // The row is assembled by appending the original text and the translation as whole strings,
        // then copying the spans of the original onto their new positions. Slicing the source with
        // append(baseText, start, end) drops the span that draws a custom emoji and insert or
        // replace splits it, and both left the row drawn as nothing.
        final SpannableStringBuilder builder = new SpannableStringBuilder();
        final int translatedStart;
        final int translatedEnd;

        if (style == TranslationStyle.TRANSLATED_ONLY) {
            // The author prefix is kept as it is, the message is replaced by the translation.
            builder.append(baseText.subSequence(0, msgStart));
            builder.append(TRANSLATED_MARK);
            translatedStart = builder.length();
            builder.append(translated);
            translatedEnd = builder.length();
            copySpans(spanned, 0, msgStart, builder, 0);
        } else {
            if (style == TranslationStyle.TRANSLATED_AND_ORIGINAL) {
                // "author (translated) original": the author prefix stays in front, then the
                // translation, then the original message with its spans.
                builder.append(baseText.subSequence(0, msgStart));
                builder.append(' ');
                translatedStart = builder.length();
                builder.append(TRANSLATED_MARK).append('(').append(translated).append(')');
                translatedEnd = builder.length();
                builder.append(' ');
                final int bodyStart = builder.length();
                builder.append(baseText.subSequence(msgStart, length));
                copySpans(spanned, 0, msgStart, builder, 0);
                copySpans(spanned, msgStart, length, builder, bodyStart);
            } else {
                // "original (translated)": the whole row first, then the translation.
                builder.append(baseText);
                builder.append(' ');
                translatedStart = builder.length();
                builder.append(TRANSLATED_MARK).append('(').append(translated).append(')');
                translatedEnd = builder.length();
                copySpans(spanned, 0, length, builder, 0);
            }
        }

        if (builder.length() == 0) {
            // Nothing to show, so the row keeps its original text instead of going blank.
            return baseText;
        }

        applyTranslationStyle(builder, translatedStart, translatedEnd);

        builder.setSpan(
                new TranslatedChatSpan(baseText, msgStart, originalMessage, cfgHash),
                0,
                builder.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        // An immutable copy is returned on purpose. A SpannableStringBuilder is a scratch buffer by
        // convention, so handing it to the text component lets it be cleared or reused, which showed
        // up as chat rows rendering with no text at all.
        return new SpannableString(builder);
    }

    /**
     * Copies the spans of {@code source[srcStart, srcEnd)} onto the builder, shifted so the range
     * starts at {@code destStart}. Spans that only partly overlap the range are clipped to it.
     */
    private static void copySpans(
            @Nullable Spanned source,
            int srcStart, int srcEnd,
            SpannableStringBuilder dest,
            int destStart) {
        if (source == null || srcEnd <= srcStart) {
            return;
        }
        final Object[] spans = source.getSpans(srcStart, srcEnd, Object.class);
        if (spans == null) {
            return;
        }
        for (Object span : spans) {
            // Our own spans are rebuilt for the new text.
            if (span instanceof ChatRowSpan) {
                continue;
            }
            final int start = Math.max(source.getSpanStart(span), srcStart) - srcStart + destStart;
            final int end = Math.min(source.getSpanEnd(span), srcEnd) - srcStart + destStart;
            if (end <= start || end > dest.length()) {
                continue;
            }
            Object copy = span;
            if (span instanceof CharacterStyle characterStyle && !(span instanceof ReplacementSpan)) {
                final CharacterStyle wrapped = CharacterStyle.wrap(characterStyle);
                if (wrapped != null) {
                    copy = wrapped;
                }
            }
            dest.setSpan(copy, start, end, source.getSpanFlags(span));
        }
    }

    /**
     * Colors and bolds the translation, honouring the two settings. The original keeps its own
     * styling, which is why the author name is never recolored.
     */
    private static void applyTranslationStyle(
            SpannableStringBuilder dest, int destStart, int destEnd) {
        if (destStart >= destEnd) {
            return;
        }
        if (Settings.LIVE_CHAT_TRANSLATE_USE_COLOR.get()) {
            dest.setSpan(new ForegroundColorSpan(translateColor()), destStart, destEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (Settings.LIVE_CHAT_TRANSLATE_BOLD.get()) {
            dest.setSpan(new TypefaceSpan("sans-serif-bold"), destStart, destEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            dest.setSpan(new StyleSpan(Typeface.BOLD), destStart, destEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }


    /**
     * @return The configured translation color, parsed once per setting value instead of once per
     *         chat row.
     */
    private static int translateColor() {
        final String hex = Settings.LIVE_CHAT_TRANSLATE_COLOR.get();
        if (hex != null && hex.equals(cachedColorHex)) {
            return cachedColorValue;
        }
        int parsed = DEFAULT_TRANSLATE_COLOR;
        if (hex != null && !hex.isEmpty()) {
            try {
                parsed = Color.parseColor(hex.trim());
            } catch (IllegalArgumentException ignored) {
                // Keep the default color.
            }
        }
        cachedColorHex = hex;
        cachedColorValue = parsed;
        return parsed;
    }

    private static SpannableString attachPendingSpan(CharSequence baseText, PendingChatSpan pendingSpan) {
        SpannableString spanned = baseText instanceof SpannableString s
                ? new SpannableString(s)
                : new SpannableString(baseText);
        spanned.setSpan(pendingSpan, 0, spanned.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spanned;
    }

    /**
     * Injection point for legacy view builds.
     */
    public static SpannableStringBuilder onMessagePreRender(
            @Nullable SpannableStringBuilder messageBuilder) {
        if (messageBuilder == null || messageBuilder.length() == 0) {
            return messageBuilder;
        }
        if (!Settings.LIVE_CHAT_TRANSLATE.get()) {
            return messageBuilder;
        }

        try {
            if (containsChar(messageBuilder, TRANSLATED_MARK)) {
                return messageBuilder;
            }
            final String original = stripEmoji(messageBuilder.toString());
            if (!shouldTranslate(original, false)) {
                return messageBuilder;
            }

            final String language = getTargetLanguage();
            final String key = cacheKey(original, language);
            String translated = translationCache.get(key);
            if (translated == null) {
                queueTranslation(original, language, key);
                return messageBuilder;
            }

            SpannableStringBuilder result = new SpannableStringBuilder();
            final TranslationStyle style = Settings.LIVE_CHAT_TRANSLATE_STYLE.get();
            if (style == TranslationStyle.TRANSLATED_ONLY) {
                result.append(TRANSLATED_MARK).append(translated);
                applyTranslationStyle(result, 0, result.length());
            } else if (style == TranslationStyle.TRANSLATED_AND_ORIGINAL) {
                result.append(TRANSLATED_MARK).append('(').append(translated).append(')').append(' ');
                final int start = result.length();
                result.append(messageBuilder);
                applyTranslationStyle(result, 0, start);
            } else {
                result.append(messageBuilder);
                result.append(' ');
                final int start = result.length();
                result.append(TRANSLATED_MARK).append('(').append(translated).append(')');
                applyTranslationStyle(result, start, result.length());
            }
            return result;
        } catch (Exception ex) {
            Log.e(TAG, "exception in onMessagePreRender", ex);
            Logger.printException(() -> "onMessagePreRender failure", ex);
            return messageBuilder;
        }
    }

    private static void queueTranslation(String text, String language, String key) {
        if (isChatIdle()) {
            return;
        }
        if (translationCache.containsKey(key) || skippedLookups.containsKey(key)) {
            return;
        }
        if (pendingLookups.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    fetchTranslation(text, language, key);
                } finally {
                    pendingLookups.remove(key);
                }
            });
        } catch (RejectedExecutionException ex) {
            // Queue full: the row keeps its pending span and is retried on the next visible scan.
            pendingLookups.remove(key);
        }
    }

    private static boolean shouldTranslate(String text, boolean trustedBody) {
        if (text == null) return false;
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) return false;
        if (!hasTranslatableLetters(trimmed)) return false;

        final int maxLength = cachedMaxLength;
        if (maxLength != MAX_LENGTH_UNLIMITED && trimmed.length() > maxLength) return false;

        if (isProbablyUrlOnly(trimmed)) return false;

        if (trustedBody) {
            // The body span was found, so this is a message and not an author name. Short messages
            // such as "草" or "ありがとう" are common and must be translated.
            return true;
        }
        if (trimmed.length() < 2) {
            // Without a body span the row might be a bare author name. A single character is only
            // accepted when it is a word on its own, such as "草", and not a Latin letter or digit.
            final int codePoint = trimmed.codePointAt(0);
            return Character.isLetter(codePoint) && codePoint >= 0x80;
        }
        return true;
    }

    private static String cacheKey(String text, String language) {
        return language + '\u0000' + text;
    }

    /**
     * Waits until the request rate allows another call. Runs on a worker thread, never on the main
     * thread, so a burst of chat rows is spread over time instead of being dropped.
     *
     * @return false when the waiting took too long and the caller should give up for now.
     */
    private static boolean acquireRateLimit() {
        for (int attempt = 0; attempt < 20; attempt++) {
            final long now = System.currentTimeMillis();
            if (now - rateWindowStart.get() >= 1000L) {
                rateWindowStart.set(now);
                rateWindowCount.set(1);
                return true;
            }
            if (rateWindowCount.incrementAndGet() <= MAX_REQUESTS_PER_SECOND) {
                return true;
            }
            rateWindowCount.decrementAndGet();
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Requests a relayout of the chat rows whose translation just arrived. A relayout scans every
     * mounted text of the app, so while the chat is busy the requests are coalesced and spaced out
     * instead of running once per finished translation.
     */
    private static void requestRelayout() {
        final long now = System.currentTimeMillis();
        if (now - lastRelayoutAt.get() >= RELAYOUT_THROTTLE_MS) {
            lastRelayoutAt.set(now);
            LithoRelayoutPatch.relayoutOutdatedTexts();
        } else if (relayoutPending.compareAndSet(0, 1)) {
            // One delayed request is enough; it picks up every translation that finished meanwhile.
            Utils.runOnMainThreadDelayed(() -> {
                relayoutPending.set(0);
                lastRelayoutAt.set(System.currentTimeMillis());
                LithoRelayoutPatch.relayoutOutdatedTexts();
            }, RELAYOUT_THROTTLE_MS);
        }
        scheduleRelayoutRetryIfNeeded();
    }

    /**
     * Keeps triggering relayouts until every released group has been written into its row. The
     * translations that finished later are only released after the current frame, so they still
     * need something to ask for the relayout that displays them.
     */
    private static void scheduleRelayoutRetryIfNeeded() {
        if (!hasRowWaitingToApply() || !relayoutRetry.compareAndSet(0, 1)) {
            return;
        }
        Utils.runOnMainThreadDelayed(() -> {
            relayoutRetry.set(0);
            if (hasRowWaitingToApply()) {
                lastRelayoutAt.set(0L);
                requestRelayout();
            }
        }, RELAYOUT_SPREAD_MS + 40L);
    }

    /** Runs on a background executor thread, never on the main thread. */
    private static void fetchTranslation(String text, String language, String key) {
        if (System.currentTimeMillis() - lastFailureTimestamp < FAILURE_BACKOFF_MS) {
            return;
        }

        try {
            if (!acquireRateLimit()) {
                // Rescheduled by the visible scan instead of blocking the worker.
                return;
            }

            final String url = String.format(
                    Locale.US,
                    TRANSLATE_ENDPOINT,
                    getSourceLanguage(),
                    language,
                    URLEncoder.encode(text, "UTF-8")
            );
            final HttpURLConnection connection = Requester.openConnection(url);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/131.0.0.0 Mobile Safari/537.36");
            final int responseCode = connection.getResponseCode();
            if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                connection.disconnect();
                lastFailureTimestamp = System.currentTimeMillis();
                Log.w(TAG, "HTTP " + responseCode + " from translate endpoint");
                return;
            }
            final String response = Requester.parseStringAndDisconnect(connection);
            if (response == null) {
                lastFailureTimestamp = System.currentTimeMillis();
                Log.w(TAG, "empty response body");
                return;
            }

            final TranslationResult result = parseTranslateResponse(response);
            final String translated = result == null ? null : result.text;
            final String detected = result == null ? null : result.detectedLanguage;

            if (translated == null || translated.isEmpty()
                    || translated.trim().equalsIgnoreCase(text.trim())
                    || detected != null && (isExcludedLanguage(detected)
                    || detected.equalsIgnoreCase(language))) {
                skip(key);
                return;
            }

            if (translationCache.size() > MAX_CACHE_ENTRIES) {
                translationCache.clear();
            }
            translationCache.put(key, translated.trim());
            failures.set(0);
            // Always marked ready, even when the chat looks idle: a paused replay or a quiet chat
            // creates no new rows for a while, and skipping this left those rows untranslated
            // forever. Nothing is displayed when the row is gone, as the relayout only touches
            // mounted rows.
            markReady(key);
            log("translated: " + abbreviate(text) + " -> " + abbreviate(translated.trim()));

            // Trigger in-place relayout of the shown chat rows waiting on this translation.
            requestRelayout();
        } catch (Exception ex) {
            lastFailureTimestamp = System.currentTimeMillis();
            if (failures.incrementAndGet() <= 3) {
                Log.e(TAG, "translate failure", ex);
            }
        }
    }

    private static void skip(String key) {
        if (skippedLookups.size() > MAX_CACHE_ENTRIES) {
            skippedLookups.clear();
        }
        skippedLookups.put(key, Boolean.TRUE);
        requestRelayout();
    }

    private static boolean isExcludedLanguage(String detected) {
        final String excluded = Settings.LIVE_CHAT_TRANSLATE_EXCLUDE.get();
        if (excluded == null || excluded.isEmpty()) {
            return false;
        }
        for (String entry : excluded.split(",")) {
            final String code = entry.trim();
            if (!code.isEmpty() && code.equalsIgnoreCase(detected)) {
                return true;
            }
        }
        return false;
    }

    private static final class TranslationResult {
        final String text;
        @Nullable
        final String detectedLanguage;

        TranslationResult(String text, @Nullable String detectedLanguage) {
            this.text = text;
            this.detectedLanguage = detectedLanguage;
        }
    }

    @Nullable
    private static TranslationResult parseTranslateResponse(String response) {
        try {
            final JSONArray root = new JSONArray(response);
            final JSONArray segments = root.getJSONArray(0);
            final StringBuilder result = new StringBuilder();

            for (int i = 0; i < segments.length(); i++) {
                final Object segment = segments.get(i);
                if (!(segment instanceof JSONArray parts)) continue;
                if (parts.length() == 0) continue;
                final Object text = parts.get(0);
                if (text instanceof String string) {
                    result.append(string);
                }
            }

            // Index 2 of the response holds the detected source language.
            String detected = null;
            if (root.length() > 2 && !root.isNull(2)) {
                final String value = root.optString(2, "");
                if (!value.isEmpty()) {
                    detected = value;
                }
            }
            return new TranslationResult(result.toString(), detected);
        } catch (JSONException ex) {
            Logger.printException(() -> "parseTranslateResponse failure", ex);
            return null;
        }
    }

    private static String getTargetLanguage() {
        String language = Settings.LIVE_CHAT_TRANSLATE_TARGET.get();
        if (language != null) {
            language = language.trim();
        }
        if (language == null || isEmptyOrAuto(language)) {
            language = Locale.getDefault().getLanguage();
            if (language == null || language.isEmpty()) language = "en";
        }
        if (!language.equals(targetLanguage)) {
            targetLanguage = language;
            log("target language: [" + language + "]");
        }
        return language;
    }

    private static String getSourceLanguage() {
        String language = Settings.LIVE_CHAT_TRANSLATE_SOURCE.get();
        if (language == null) {
            return "auto";
        }
        language = language.trim();
        return language.isEmpty() || isEmptyOrAuto(language) ? "auto" : language;
    }

    private static boolean isEmptyOrAuto(String language) {
        return language.isEmpty() || language.equalsIgnoreCase("auto");
    }

    public static void resetTargetLanguage() {
        targetLanguage = null;
        translationCache.clear();
        pendingLookups.clear();
        skippedLookups.clear();
        instanceResults.clear();
        cachedConfigHashAt = 0L;
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }

    private static boolean isProbablyUrlOnly(String text) {
        final String trimmed = text.trim();
        if (!trimmed.startsWith("http")) return false;
        return trimmed.indexOf(' ') < 0;
    }

    private static boolean containsChar(CharSequence text, char value) {
        for (int i = 0, length = text.length(); i < length; i++) {
            if (text.charAt(i) == value) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTranslatableLetters(String text) {
        for (int i = 0, length = text.length(); i < length; i++) {
            if (Character.isLetter(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
