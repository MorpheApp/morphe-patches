/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.returnyoutubedislike;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.graphics.drawable.shapes.RectShape;
import android.icu.text.CompactDecimalFormat;
import android.icu.text.DecimalFormat;
import android.icu.text.DecimalFormatSymbols;
import android.icu.text.NumberFormat;
import android.os.Build;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.ReplacementSpan;
import android.widget.Toast;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
import app.morphe.extension.shared.returnyoutubedislike.requests.RYDVoteData;
import app.morphe.extension.shared.returnyoutubedislike.requests.ReturnYouTubeDislikeAPI;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.ui.Dim;

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

    private static boolean isMusic;

    public static void setIsMusic(boolean music) {
        isMusic = music;
    }

    /**
     * Maximum amount of time to block the UI from updates while waiting for network call to complete.
     *
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
     * Unique placeholder character, used to detect if a segmented span already has dislikes added to it.
     * Must be something YouTube is unlikely to use, as it's searched for in all usage of Rolling Number.
     */
    private static final char MIDDLE_SEPARATOR_CHARACTER = '◎'; // 'bullseye'

    /**
     * Cached lookup of all video IDs.
     */
    @GuardedBy("itself")
    private static final Map<String, ReturnYouTubeDislike> fetchCache = new HashMap<>();

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

    // Used for segmented dislike spans.
    public static final Rect leftSeparatorBoundsYouTube;
    public static final Rect leftSeparatorBoundsMusic;
    private static final Rect middleSeparatorBounds;

    /**
     * Horizontal padding between the left and middle separator.
     */
    public static final int leftSeparatorShapePaddingPixels;
    private static final ShapeDrawable leftSeparatorShape;

    static {
        leftSeparatorBoundsYouTube = new Rect(0, 0, Dim.dp(1.2f), Dim.dp(14));
        leftSeparatorBoundsMusic = new Rect(0, 0, Dim.dp(1.2f), Dim.dp(23));

        final int middleSeparatorSize = Dim.dp(3.7f);
        middleSeparatorBounds = new Rect(0, 0, middleSeparatorSize, middleSeparatorSize);

        leftSeparatorShapePaddingPixels = Dim.dp(8.4f);

        leftSeparatorShape = new ShapeDrawable(new RectShape());
        leftSeparatorShape.setBounds(leftSeparatorBoundsYouTube);
    }

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
     * If this instance was previously used for a Short.
     */
    @GuardedBy("this")
    private boolean isShort;

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

    /**
     * Should be called if the user changes dislikes appearance settings.
     */
    public static void clearAllUICaches() {
        synchronized (fetchCache) {
            for (ReturnYouTubeDislike fetch : fetchCache.values()) {
                fetch.clearUICache();
            }
        }
    }

    private static int getSeparatorColor() {
        return isMusic || Utils.isDarkModeEnabled()
                ? 0x33FFFFFF
                : 0xFFD9D9D9;
    }

    public static ShapeDrawable getLeftSeparatorDrawable() {
        Rect bounds = isMusic
                ? leftSeparatorBoundsMusic
                : leftSeparatorBoundsYouTube;
        leftSeparatorShape.setBounds(bounds);
        leftSeparatorShape.getPaint().setColor(getSeparatorColor());
        return leftSeparatorShape;
    }

    /**
     * Pre-emptively set this as a Short.
     */
    public synchronized void setVideoIdIsShort(boolean isShort) {
        this.isShort = isShort;
    }

    public ReturnYouTubeDislike(String videoId) {
        this.videoId = Objects.requireNonNull(videoId);
        this.timeFetched = System.currentTimeMillis();
        this.future = Utils.submitOnBackgroundThread(() -> ReturnYouTubeDislikeAPI.fetchVotes(videoId));
    }

    /**
     * @return the replacement span containing dislikes, or the original span if RYD is not available.
     */
    public synchronized Spanned getDislikesSpanForRegularVideo(Spanned original,
                                                               boolean isSegmentedButton,
                                                               boolean isRollingNumber) {
        return waitForFetchAndUpdateReplacementSpan(original, isSegmentedButton,
                isRollingNumber, false, false);
    }

    /**
     * Called when a Shorts like Spannable is created.
     */
    public synchronized Spanned getLikeSpanForShort(Spanned original) {
        return waitForFetchAndUpdateReplacementSpan(original, false,
                false, true, true);
    }

    private Spanned waitForFetchAndUpdateReplacementSpan(Spanned original,
                                                         boolean isSegmentedButton,
                                                         boolean isRollingNumber,
                                                         boolean spanIsForShort,
                                                         boolean spanIsForLikes) {
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

            synchronized (this) {
                if (spanIsForShort) {
                    isShort = true;
                } else if (isShort) {
                    Logger.printDebug(() -> "Ignoring regular video dislike span,"
                            + " as data loaded was previously used for a Short: " + videoId);
                    return original;
                }

                if (spanIsForLikes) {
                    if (!Utils.containsNumber(original)) {
                        if (!SharedYouTubeSettings.RYD_ESTIMATED_LIKE.get()) {
                            Logger.printDebug(() -> "Likes are hidden");
                            return original;
                        } else {
                            Logger.printDebug(() -> "Using estimated likes");
                        }
                    }

                    Logger.printDebug(() -> "Creating likes span for: " + votingData.videoId);
                    return newSpannableWithLikes(original, votingData);
                }

                if (originalDislikeSpan != null && replacementLikeDislikeSpan != null) {
                    if (spansHaveEqualTextAndColor(original, originalDislikeSpan)) {
                        final Spanned originalSpanFinal = original;
                        Logger.printDebug(() -> "Replacing span: " + originalSpanFinal + " with " +
                                "previously created dislike span of data: " + videoId);
                        return replacementLikeDislikeSpan;
                    }
                }

                // No replacement span exist, create it now.

                if (userVote != null) {
                    votingData.updateUsingVote(userVote);
                }
                originalDislikeSpan = original;
                replacementLikeDislikeSpan = createDislikeSpan(original, votingData,
                        isSegmentedButton, isRollingNumber);
                Logger.printDebug(() -> "Replaced: '" + originalDislikeSpan + "' with: '"
                        + replacementLikeDislikeSpan + "'" + " using video: " + videoId);

                return replacementLikeDislikeSpan;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "waitForFetchAndUpdateReplacementSpan failure", ex);
        }

        return original;
    }

    private SpannableString createDislikeSpan(Spanned oldSpannable,
                                              RYDVoteData voteData,
                                              boolean isSegmentedButton,
                                              boolean isRollingNumber) {
        if (!isSegmentedButton) {
            // Simple replacement of 'dislike' with a number/percentage.
            return newSpannableWithDislikes(oldSpannable, voteData);
        }

        // Note: Some locales use right to left layout (Arabic, Hebrew, etc.).
        // If making changes to this code, change device settings to an RTL language and verify layout is correct.
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

        SpannableStringBuilder builder = new SpannableStringBuilder();

        final boolean compactLayout = SharedYouTubeSettings.RYD_COMPACT_LAYOUT.get();
        if (!compactLayout) {
            String leftSeparatorString = Utils.getTextDirectionString();

            final Spannable leftSeparatorSpan;
            if (isRollingNumber) {
                leftSeparatorSpan = new SpannableString(leftSeparatorString);
            } else {
                leftSeparatorString += "   ";
                leftSeparatorSpan = new SpannableString(leftSeparatorString);

                // Styling spans cannot overwrite RTL or LTR character.
                leftSeparatorSpan.setSpan(
                        new VerticallyCenteredImageSpan(getLeftSeparatorDrawable(), false),
                        1, 2, Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
                leftSeparatorSpan.setSpan(
                        new FixedWidthEmptySpan(leftSeparatorShapePaddingPixels),
                        2, 3, Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
            }
            builder.append(leftSeparatorSpan);
        }

        // likes
        builder.append(newSpanUsingStylingOfAnotherSpan(oldSpannable, oldLikes));

        // middle separator
        String middleSeparatorString = compactLayout
                ? "  " + MIDDLE_SEPARATOR_CHARACTER + "  "
                : " \u2009\u2009" + MIDDLE_SEPARATOR_CHARACTER + "\u2009\u2009 "; // u2009 = 'narrow space'

        final int shapeInsertionIndex = middleSeparatorString.length() / 2;
        Spannable middleSeparatorSpan = new SpannableString(middleSeparatorString);
        ShapeDrawable shapeDrawable = new ShapeDrawable(new OvalShape());
        shapeDrawable.getPaint().setColor(getSeparatorColor());
        shapeDrawable.setBounds(middleSeparatorBounds);
        // Use original text width if using Rolling Number,
        // to ensure the replacement styled span has the same width as the measured String,
        // otherwise layout can be broken (especially on devices with small system font sizes).
        middleSeparatorSpan.setSpan(
                new VerticallyCenteredImageSpan(shapeDrawable, isRollingNumber),
                shapeInsertionIndex, shapeInsertionIndex + 1, Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
        builder.append(middleSeparatorSpan);

        // dislikes
        builder.append(newSpannableWithDislikes(oldSpannable, voteData));

        return new SpannableString(builder);
    }

    /**
     * @return If the text is likely for a previously created likes/dislikes segmented span.
     */
    public static boolean isPreviouslyCreatedSegmentedSpan(String text) {
        return text.indexOf(MIDDLE_SEPARATOR_CHARACTER) >= 0;
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
        if (sourceStyle == newSpanText && sourceStyle instanceof SpannableString) {
            return (SpannableString) sourceStyle;
        }

        SpannableString destination = new SpannableString(newSpanText);
        Object[] spans = sourceStyle.getSpans(0, sourceStyle.length(), Object.class);
        for (Object span : spans) {
            destination.setSpan(span, 0, destination.length(), sourceStyle.getSpanFlags(span));
        }

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

    private static SpannableString newSpannableWithLikes(Spanned sourceStyling, RYDVoteData voteData) {
        return newSpanUsingStylingOfAnotherSpan(sourceStyling, formatDislikeCount(voteData.getLikeCount()));
    }

    private static SpannableString newSpannableWithDislikes(Spanned sourceStyling, RYDVoteData voteData) {
        return newSpanUsingStylingOfAnotherSpan(sourceStyling,
                SharedYouTubeSettings.RYD_DISLIKE_PERCENTAGE.get()
                        ? formatDislikePercentage(voteData.getDislikePercentage())
                        : formatDislikeCount(voteData.getDislikeCount()));
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

/**
 * Styles a Spannable with an empty fixed width.
 */
class FixedWidthEmptySpan extends ReplacementSpan {
    final int fixedWidth;
    /**
     * @param fixedWith Fixed width in screen pixels.
     */
    public FixedWidthEmptySpan(int fixedWith) {
        this.fixedWidth = fixedWith;
        if (fixedWith < 0) throw new IllegalArgumentException();
    }
    @Override
    public int getSize(@NonNull Paint paint, @NonNull CharSequence text,
                       int start, int end, @Nullable Paint.FontMetricsInt fontMetrics) {
        return fixedWidth;
    }
    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, @NonNull Paint paint) {
        // Nothing to draw.
    }
}

/**
 * Vertically centers a Spanned Drawable.
 */
class VerticallyCenteredImageSpan extends ImageSpan {
    final boolean useOriginalWidth;

    /**
     * @param useOriginalWidth Use the original layout width of the text this span is applied to,
     * and not the bounds of the Drawable. Drawable is always displayed using its own bounds,
     * and this setting only affects the layout width of the entire span.
     */
    public VerticallyCenteredImageSpan(Drawable drawable, boolean useOriginalWidth) {
        super(drawable);
        this.useOriginalWidth = useOriginalWidth;
    }

    @Override
    public int getSize(@NonNull Paint paint, @NonNull CharSequence text,
                       int start, int end, @Nullable Paint.FontMetricsInt fontMetrics) {
        Drawable drawable = getDrawable();
        Rect bounds = drawable.getBounds();
        if (fontMetrics != null) {
            Paint.FontMetricsInt paintMetrics = paint.getFontMetricsInt();
            final int fontHeight = paintMetrics.descent - paintMetrics.ascent;
            final int drawHeight = bounds.bottom - bounds.top;
            final int halfDrawHeight = drawHeight / 2;
            final int yCenter = paintMetrics.ascent + fontHeight / 2;

            fontMetrics.ascent = yCenter - halfDrawHeight;
            fontMetrics.top = fontMetrics.ascent;
            fontMetrics.bottom = yCenter + halfDrawHeight;
            fontMetrics.descent = fontMetrics.bottom;
        }
        if (useOriginalWidth) {
            // Horizontally center the drawable in the same space as the original text.
            return (int) paint.measureText(text, start, end);
        }
        return bounds.right;
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, @NonNull Paint paint) {
        Drawable drawable = getDrawable();
        canvas.save();
        Paint.FontMetricsInt paintMetrics = paint.getFontMetricsInt();
        final int fontHeight = paintMetrics.descent - paintMetrics.ascent;
        final int yCenter = y + paintMetrics.descent - fontHeight / 2;
        final Rect drawBounds = drawable.getBounds();
        float translateX = x;
        if (useOriginalWidth) {
            translateX += (paint.measureText(text, start, end) - (drawBounds.right - drawBounds.left)) / 2;
        }
        final int translateY = yCenter - (drawBounds.bottom - drawBounds.top) / 2;
        canvas.translate(translateX, translateY);
        drawable.draw(canvas);
        canvas.restore();
    }
}
