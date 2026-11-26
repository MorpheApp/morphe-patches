/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3075
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.returnyoutubedislike;

import static app.morphe.extension.shared.StringRef.str;

import android.icu.number.LocalizedNumberFormatter;
import android.icu.number.Notation;
import android.icu.number.NumberFormatter;
import android.icu.text.CompactDecimalFormat;
import android.icu.text.DecimalFormat;
import android.icu.text.DecimalFormatSymbols;
import android.icu.text.NumberFormat;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.Toast;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.NetworkReconnect;
import app.morphe.extension.shared.returnyoutubedislike.requests.RYDVoteData;
import app.morphe.extension.shared.returnyoutubedislike.requests.ReturnYouTubeDislikeAPI;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.theme.ThemeColorPatch;
import app.morphe.extension.shared.theme.ThemeUtils;

/**
 * Handles fetching and creation/replacing of RYD dislike text spans.
 */
public class ReturnYouTubeDislike {

    public enum Vote {
        LIKE("like/like", 1),
        DISLIKE("like/dislike", -1),
        LIKE_REMOVE("like/removelike", 0);

        public final String endpoint;
        public final int value;

        Vote(String endpoint, int value) {
            this.endpoint = endpoint;
            this.value = value;
        }
    }

    /**
     * Maximum amount of time to block the UI from updates while waiting for network call to complete.
     * <p>
     * Must be less than 5 seconds, as per:
     * <a href="https://developer.android.com/topic/performance/vitals/anr">Android guidelines</a>
     */
    private static final long MAX_MILLISECONDS_TO_BLOCK_UI_WAITING_FOR_FETCH = 4000;

    /**
     * How long to retain successful RYD fetches.
     */
    private static final long CACHE_TIMEOUT_SUCCESS_MILLISECONDS = 7 * 60 * 1000; // 7 Minutes

    /**
     * How long to retain unsuccessful RYD fetches,
     * and also the minimum time before retrying again.
     */
    private static final long CACHE_TIMEOUT_FAILURE_MILLISECONDS = 3 * 60 * 1000; // 3 Minutes

    /**
     * Cached lookup of all video IDs.
     */
    @GuardedBy("itself")
    private static final Map<String, ReturnYouTubeDislike> fetchCache = new HashMap<>();

    /**
     * Removes the fetches that failed, so they are fetched again,
     * such as the fetches that failed while the device was offline.
     */
    private static final Runnable REMOVE_FAILED_FETCHES = () -> {
        synchronized (fetchCache) {
            fetchCache.values().removeIf(fetch -> fetch.fetchCompleted() && fetch.getFetchData(0) == null);
        }
    };

    /**
     * Used to send votes, one by one, in the same order the user created them.
     */
    private static final ExecutorService voteSerialExecutor = Executors.newSingleThreadExecutor();

    /**
     * For formatting dislikes as number.
     */
    @GuardedBy("ReturnYouTubeDislike.class") // not thread safe
    private static CompactDecimalFormat dislikeCountFormatter;

    /**
     * For formatting dislikes as percentage.
     */
    @GuardedBy("ReturnYouTubeDislike.class")
    private static NumberFormat dislikePercentageFormatter;

    private final String videoId;

    /**
     * Stores the results of the vote api fetch, and used as a barrier to wait until fetch completes.
     * Absolutely cannot be holding any lock during calls to {@link Future#get()}.
     */
    private final Future<RYDVoteData> future;

    /**
     * Time this instance and the fetch future was created.
     */
    private final long timeFetched;

    /**
     * Optional current vote status of the UI.
     */
    @Nullable
    @GuardedBy("this")
    private Vote userVote;

    /**
     * Original dislike span, before modifications.
     */
    @Nullable
    @GuardedBy("this")
    private Spanned originalDislikeSpan;

    /**
     * Replacement like/dislike span that includes formatted dislikes.
     */
    @Nullable
    @GuardedBy("this")
    private SpannableString replacementLikeDislikeSpan;

    public static ReturnYouTubeDislike getFetchForVideoId(String videoId) {
        return getFetchForVideoId(videoId, true);
    }

    public static ReturnYouTubeDislike getFetchForVideoIdOrNull(String videoId) {
        return getFetchForVideoId(videoId, false);
    }

    private static ReturnYouTubeDislike getFetchForVideoId(String videoId, boolean createIfNeeded) {
        Objects.requireNonNull(videoId);
        synchronized (fetchCache) {
            // Remove any expired entries.
            final long now = System.currentTimeMillis();
            fetchCache.values().removeIf(value -> {
                final boolean expired = value.isExpired(now);
                if (expired)
                    Logger.printDebug(() -> "Removing expired fetch: " + value.videoId);
                return expired;
            });

            ReturnYouTubeDislike fetch = fetchCache.get(videoId);
            if (fetch == null && createIfNeeded) {
                fetch = new ReturnYouTubeDislike(videoId);
                fetchCache.put(videoId, fetch);
            }
            return fetch;
        }
    }

    public ReturnYouTubeDislike(String videoId) {
        this.videoId = Objects.requireNonNull(videoId);
        this.timeFetched = System.currentTimeMillis();
        this.future = Utils.submitOnBackgroundThread(() -> {
            RYDVoteData votes = ReturnYouTubeDislikeAPI.fetchVotes(videoId);
            if (votes == null) {
                // A fetch that failed while the device is offline is not kept for the failure time,
                // and is fetched again once the device is online.
                NetworkReconnect.runWhenOnline(REMOVE_FAILED_FETCHES);
            }
            return votes;
        });
    }

    /**
     * @return the replacement span containing dislikes, or the original span if RYD is not available.
     */
    public synchronized Spanned getDislikesSpanForRegularVideo(Spanned original) {
        try {
            RYDVoteData votingData = getFetchData(MAX_MILLISECONDS_TO_BLOCK_UI_WAITING_FOR_FETCH);
            if (votingData == null) {
                // Method automatically prevents showing multiple toasts if the connection failed.
                // This call is needed here in case the api call did succeed but took too long.
                ReturnYouTubeDislikeAPI.handleConnectionError(
                        str("morphe_ryd_failure_connection_timeout"),
                        null, null, Toast.LENGTH_SHORT);
                Logger.printDebug(() -> "Cannot add dislike to UI (RYD data not available)");
                return original;
            }

            if (originalDislikeSpan != null && replacementLikeDislikeSpan != null) {
                // Check if colors match to fix changing light/dark mode while player is opened
                // and avoid recreating the span. But if theme foreground color is replaced
                // then always use replacement span as-is.
                if ((ThemeColorPatch.isPatchIncluded() && SharedYouTubeSettings.THEME_COLOR_CHANGE_FOREGROUND.get())
                        || spansHaveEqualTextAndColor(original, originalDislikeSpan)) {
                    Logger.printDebug(() -> "Replacing span: " + original + " with " +
                            "previously created dislike span of data: " + videoId);
                    return replacementLikeDislikeSpan;
                }
            }

            // No replacement span exist, create it now.

            if (userVote != null) {
                votingData.updateUsingVote(userVote);
            }
            originalDislikeSpan = original;
            replacementLikeDislikeSpan = createDislikeSpan(original, votingData);
            Logger.printDebug(() -> "Replaced: '" + originalDislikeSpan + "' with: '"
                    + replacementLikeDislikeSpan + "'" + " using video: " + videoId);

            return replacementLikeDislikeSpan;
        } catch (Exception ex) {
            Logger.printException(() -> "waitForFetchAndUpdateReplacementSpan failure", ex);
        }

        return original;
    }

    private SpannableString createDislikeSpan(Spanned oldSpannable, RYDVoteData voteData) {
        CharSequence oldLikes = oldSpannable;

        // YouTube creators can hide the like count on a video,
        // and the like count appears as a device language specific string that says 'Like'.
        // Check if the string contains any numbers.
        if (!Utils.containsNumber(oldLikes)) {
            // Likes are hidden by video creator
            if (!SharedYouTubeSettings.RYD_ESTIMATED_LIKE.get()) {
                // Change the "Likes" string to show that likes and dislikes are hidden.
                String hiddenMessageString = str("morphe_ryd_video_likes_hidden_by_video_owner");
                return newSpanUsingStylingOfAnotherSpan(oldSpannable, hiddenMessageString);
            }

            Logger.printDebug(() -> "Using estimated likes");
            oldLikes = formatDislikeCount(voteData.getLikeCount());
        }

        return newSpanUsingStylingOfAnotherSpan(oldSpannable, oldLikes);
    }

    private static SpannableString setSpanForegroundColor(SpannableString span) {
        if (ThemeColorPatch.isPatchIncluded()) {
            // Remove any existing colors
            ForegroundColorSpan[] existing = span.getSpans(0, span.length(), ForegroundColorSpan.class);
            for (ForegroundColorSpan fcs : existing) {
                span.removeSpan(fcs);
            }

            span.setSpan(
                    new ForegroundColorSpan(ThemeUtils.getAppForegroundColor()),
                    0,
                    span.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        return span;
    }

    private static boolean spansHaveEqualTextAndColor(Spanned one, Spanned two) {
        if (!one.toString().equals(two.toString())) {
            return false;
        }
        ForegroundColorSpan[] oneColors = one.getSpans(0, one.length(), ForegroundColorSpan.class);
        ForegroundColorSpan[] twoColors = two.getSpans(0, two.length(), ForegroundColorSpan.class);
        final int oneLength = oneColors.length;
        if (oneLength != twoColors.length) {
            return false;
        }
        for (int i = 0; i < oneLength; i++) {
            if (oneColors[i].getForegroundColor() != twoColors[i].getForegroundColor()) {
                return false;
            }
        }
        return true;
    }

    protected static SpannableString newSpanUsingStylingOfAnotherSpan(Spanned sourceStyle, CharSequence newSpanText) {
        if (sourceStyle == newSpanText && sourceStyle instanceof SpannableString spannable) {
            return setSpanForegroundColor(spannable);
        }

        SpannableString destination = new SpannableString(newSpanText);
        Object[] spans = sourceStyle.getSpans(0, sourceStyle.length(), Object.class);
        for (Object span : spans) {
            destination.setSpan(span, 0, destination.length(), sourceStyle.getSpanFlags(span));
        }

        // Apply theme color.
        setSpanForegroundColor(destination);

        return destination;
    }

    protected static String formatDislikeCount(long dislikeCount) {
        synchronized (ReturnYouTubeDislike.class) {
            if (dislikeCountFormatter == null) {
                Locale locale = Locale.getDefault();
                dislikeCountFormatter = CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);
                    symbols.setDigitStrings(DecimalFormatSymbols.getInstance(Locale.ENGLISH).getDigitStrings());
                    dislikeCountFormatter.setDecimalFormatSymbols(symbols);
                }
            }

            return dislikeCountFormatter.format(dislikeCount);
        }
    }

    /**
     * Formats the like count the way YouTube does, which truncates instead of rounding (2,589 is 2.5K).
     * Android 10 and lower lack the formatter that can truncate, and round instead.
     */
    public static String formatLikeCount(long likeCount) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return TruncatingLikeCountFormatter.format(likeCount);
        }
        return formatDislikeCount(likeCount);
    }

    /**
     * CompactDecimalFormat ignores its rounding mode, so this uses the newer ICU number formatter.
     * A separate class keeps the Android 11 classes from loading on older devices.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private static final class TruncatingLikeCountFormatter {
        // Immutable and thread safe without synchronization.
        private static final LocalizedNumberFormatter formatter;

        static {
            Locale locale = Locale.getDefault();
            DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);
            symbols.setDigitStrings(DecimalFormatSymbols.getInstance(Locale.ENGLISH).getDigitStrings());

            formatter = NumberFormatter.withLocale(locale)
                    .notation(Notation.compactShort())
                    .roundingMode(RoundingMode.DOWN)
                    .symbols(symbols);
        }

        static String format(long count) {
            return formatter.format(count).toString();
        }
    }

    protected static String formatDislikePercentage(float dislikePercentage) {
        synchronized (ReturnYouTubeDislike.class) {
            if (dislikePercentageFormatter == null) {
                Locale locale = Locale.getDefault();
                dislikePercentageFormatter = NumberFormat.getPercentInstance(locale);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        && dislikePercentageFormatter instanceof DecimalFormat decimalFormatter) {
                    DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);
                    symbols.setDigitStrings(DecimalFormatSymbols.getInstance(Locale.ENGLISH).getDigitStrings());
                    decimalFormatter.setDecimalFormatSymbols(symbols);
                }
            }

            if (dislikePercentage >= 0.01) {
                // at least 1%
                dislikePercentageFormatter.setMaximumFractionDigits(0);
            } else {
                // show up to 1 digit precision
                dislikePercentageFormatter.setMaximumFractionDigits(1);
            }

            return dislikePercentageFormatter.format(dislikePercentage);
        }
    }

    protected boolean isExpired(long now) {
        final long timeSinceCreation = now - timeFetched;
        if (timeSinceCreation < CACHE_TIMEOUT_FAILURE_MILLISECONDS) {
            return false;
        }
        if (timeSinceCreation > CACHE_TIMEOUT_SUCCESS_MILLISECONDS) {
            return true;
        }
        return (!fetchCompleted() || getFetchData(MAX_MILLISECONDS_TO_BLOCK_UI_WAITING_FOR_FETCH) == null);
    }

    @Nullable
    public RYDVoteData getFetchData(long maxTimeToWait) {
        try {
            return future.get(maxTimeToWait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printDebug(() -> "Waited but future was not complete after: " + maxTimeToWait + "ms");
        } catch (ExecutionException | InterruptedException ex) {
            Logger.printException(() -> "Future failure ", ex); // will never happen
        }
        return null;
    }

    /**
     * @return if the RYD fetch call has completed.
     */
    public boolean fetchCompleted() {
        return future.isDone();
    }

    /**
     * @return The formatted like count, or null if the fetch has not completed, or it failed.
     */
    @Nullable
    public String getFormattedLikes() {
        RYDVoteData voteData = getCompletedVoteData();
        return voteData == null
                ? null
                : formatDislikeCount(voteData.getLikeCount());
    }

    /**
     * @return The formatted dislike count or percentage, or null if the fetch has not completed, or it failed.
     */
    @Nullable
    public String getFormattedDislikes() {
        RYDVoteData voteData = getCompletedVoteData();
        if (voteData == null) {
            return null;
        }
        return SharedYouTubeSettings.RYD_DISLIKE_PERCENTAGE.get()
                ? formatDislikePercentage(voteData.getDislikePercentage())
                : formatDislikeCount(voteData.getDislikeCount());
    }

    /**
     * @return If the user liked the video after it was opened.
     */
    public synchronized boolean isLikedByUser() {
        return userVote == Vote.LIKE;
    }

    @Nullable
    private RYDVoteData getCompletedVoteData() {
        if (!future.isDone()) {
            return null;
        }
        RYDVoteData voteData = getFetchData(0);
        if (voteData == null) {
            return null;
        }
        synchronized (this) {
            // A vote cast before the fetch completed has not been applied yet.
            if (userVote != null) {
                voteData.updateUsingVote(userVote);
            }
        }
        return voteData;
    }

    private synchronized void clearUICache() {
        if (replacementLikeDislikeSpan != null) {
            Logger.printDebug(() -> "Clearing replacement span for: " + videoId);
        }
        replacementLikeDislikeSpan = null;
    }

    public String getVideoId() {
        return videoId;
    }

    public void sendVote(Vote vote) {
        Utils.verifyOnMainThread();
        Objects.requireNonNull(vote);

        try {
            Objects.requireNonNull(vote);
            try {
                Logger.printDebug(() -> "setUserVote: " + vote);

                synchronized (this) {
                    userVote = vote;
                    clearUICache();
                }

                if (future.isDone()) {
                    RYDVoteData voteData = getFetchData(MAX_MILLISECONDS_TO_BLOCK_UI_WAITING_FOR_FETCH);
                    if (voteData == null) {
                        Logger.printDebug(() -> "Cannot update UI (vote data not available)");
                    } else {
                        voteData.updateUsingVote(vote);
                    }
                }

            } catch (Exception ex1) {
                Logger.printException(() -> "setUserVote failure", ex1);
            }

            voteSerialExecutor.execute(() -> {
                try { // Must wrap in try/catch to properly log exceptions.
                    ReturnYouTubeDislikeAPI.sendVote(videoId, vote);
                } catch (Exception ex) {
                    Logger.printException(() -> "Failed to send vote", ex);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "Error trying to send vote", ex);
        }
    }

}
