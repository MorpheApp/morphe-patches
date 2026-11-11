/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3862
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.icu.text.CompactDecimalFormat;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.TextContent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.youtube.patches.components.LayoutComponentsFilter;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Shows the view count of feed videos at the bottom left of their thumbnails,
 * lined up with the duration badge at the bottom right.
 * In right to left languages, both are on the other side.
 * <p>
 * The view count is the text YouTube shows below the title, such as
 * "Channel - 4.7M views - 2 days ago". When that text is created, the shortened view count
 * is attached to it as a span, so the card that draws the text knows its view count.
 * The span is copied with the text, so it is kept when the Hide view count option
 * of the Hide layout components patch removes the view count from below the title.
 * No request is made, and the count is the same that YouTube shows. Until the text is drawn,
 * such as while only a part of the card is shown, the count of the element of the video is used,
 * which is loaded before the card.
 * <p>
 * The same text is used by other lists too, such as the videos of a playlist, where no badge is
 * shown. So the view count is removed from below the title only for the texts of the components
 * that show the badge, known by the Litho path of the text. A path is known when a card whose text
 * still shows the view count is found. Other texts keep their view count, and their card has no badge.
 */
@SuppressWarnings("unused")
public final class FeedViewCountPatch {

    public static final boolean ENABLED = isPatchIncluded() && Settings.FEED_VIEW_COUNT.get();

    /**
     * Delimiters of the texts below the title. Older layouts show a dot as text,
     * such as "Channel (dot) 4.7M views (dot) 2 days ago". Newer layouts draw the dot as an icon
     * in place of spaces, such as "Channel      4.7M   2 days ago".
     * A no-break space is used inside a view count, such as "4.7 M", so it is not a delimiter.
     */
    private static final Pattern SEGMENT_DELIMITER_PATTERN =
            Pattern.compile("[ \\t]{2,}|[ \\t\u00A0\u202F]*[·•][ \\t\u00A0\u202F]*");
    private static final int MAX_SUBTITLE_LENGTH = 150;
    private static final int MAX_SEGMENT_LENGTH = 40;

    /**
     * A number, such as 457, 4,7 or 1 234. Some languages show words before it.
     */
    private static final Pattern NUMBER_PATTERN =
            Pattern.compile("\\p{Nd}+(?:[.,'’٫٬\u00A0\u202F ]\\p{Nd}+)*");

    /** Different words of view counts that were seen, such as "views" and "watching". */
    private static final int MAX_VIEW_WORDS = 8;

    /** Allowed difference of sizes and positions that are compared. */
    private static final int TOLERANCE_DP = 4;
    /** Space between the duration badge and the edges of the thumbnail, if the badge is not found. */
    private static final int FALLBACK_MARGIN_DP = 8;
    /** A little more space than the duration badge has, so the count is not at the very edge. */
    private static final int EXTRA_MARGIN_DP = 2;
    /** A square thumbnail is this much taller than 16:9, at least. */
    private static final int SQUARE_MIN_EXTRA_DP = 40;
    /** Space between the thumbnail and the title. */
    private static final int THUMBNAIL_TEXT_GAP_DP = 12;
    private static final int FALLBACK_HEIGHT_DP = 20;
    /** Cards of a grid of up to three columns, such as the feed in landscape. */
    private static final float MIN_GRID_CARD_WIDTH_OF_SCREEN = 0.3f;
    private static final int MIN_DURATION_HEIGHT_DP = 12;
    private static final int PADDING_DP = 5;
    private static final int GAP_DP = 3;
    /** Same as the like count badge, close to the corners of the duration badge. */
    private static final int RADIUS_DP = 8;
    /** As large as the text of the duration badge. */
    private static final float TEXT_SP = 12.5f;
    private static final float ICON_STROKE_DP = 1.4f;
    /** Text size of the badge, relative to its height. */
    private static final float TEXT_SIZE_OF_HEIGHT = 0.66f;
    /** Size of the play icon, relative to the text size. */
    private static final float ICON_SIZE_OF_TEXT = 0.72f;
    /** Width of the play icon, relative to its height. */
    private static final float ICON_WIDTH_OF_HEIGHT = 0.88f;

    private static final int BACKGROUND_COLOR = 0xA6000000;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    /** Shortened number units of the language of the app, such as K and M, longest first. */
    @Nullable
    private static List<String> units;
    @Nullable
    private static Locale unitsLocale;

    /** Used on more than one thread. */
    private static final Set<String> viewWords = new LinkedHashSet<>();

    /** Root hosts that show a view count. Used only on the main thread. */
    private static final Map<ComponentHost, ViewCountBadge> badges = new WeakHashMap<>();

    /** Spaces, which texts of the same view count can show differently, such as "4.7 M" and "4.7M". */
    private static final Pattern SPACES_PATTERN = Pattern.compile("[\\s  ]+");

    /** Number of videos whose view count is kept. */
    private static final int MAX_VIDEO_VIEW_COUNTS = 200;

    /**
     * View count of the element of a video, and the texts of the element that can be the badge on
     * its thumbnail, such as "12:34". The badge of a card must show one of them, so the view count
     * of another video is not shown if the card is matched to the wrong video.
     */
    private record ElementViewCount(String viewCount, Set<String> badgeTexts) {
    }

    /**
     * Video id -> view count of the element of the video, used while the texts below the title
     * of its card are not drawn yet. Oldest videos are removed first.
     */
    @GuardedBy("itself")
    private static final Map<String, ElementViewCount> videoViewCounts = Utils
            .createSizeRestrictedMap(MAX_VIDEO_VIEW_COUNTS, true);

    /**
     * Field of the element of a video or a Short with the metadata of the video, which the element of a video has
     * inside the command that opens it. Its field 2 has the shortened view count, such as 4,8 Mln,
     * and the full view count, such as "4,8 milioni di visualizzazioni".
     */
    private static final int METADATA_FIELD = 218178449;
    /** Field of the full view count inside field 2.3 of {@link #METADATA_FIELD}. */
    private static final int METADATA_FULL_VIEW_COUNT_FIELD = 75730170;

    /** Field of the commands of an element, such as what a tap opens. */
    private static final int COMMAND_FIELD = 169495254;

    /**
     * Characters that can be between the characters of a view count in one text and not in another,
     * such as the spaces of "4,8 Mln" and the marks of the text direction of right to left languages.
     */
    private static final String VIEW_COUNT_GAP = "[\\s\\p{Z}\\p{Cf}]*";

    /**
     * Text below the title without spaces -> its part that is the view count of the metadata of its video,
     * without spaces, such as "430" of "Channel  430" of a music video without the upload time.
     * The view count of such texts is found by it, as they are not shaped like the other texts.
     * Each text is also kept from its view count on, which the cards show in the row below the channel.
     * Oldest texts are removed first.
     */
    @GuardedBy("itself")
    private static final Map<String, String> subtitleViewCounts = Utils
            .createSizeRestrictedMap(2 * MAX_VIDEO_VIEW_COUNTS, true);

    /**
     * Video id -> shortened view count of the element of a Short. The cards of Shorts do not show their view count.
     * Oldest videos are removed first.
     */
    @GuardedBy("itself")
    private static final Map<String, String> shortsViewCounts = Utils
            .createSizeRestrictedMap(MAX_VIDEO_VIEW_COUNTS, true);

    private FeedViewCountPatch() {
    }

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Shortened view count attached to the texts below the title. Not a {@link android.text.NoCopySpan},
     * so it is copied with the text. Equal by value, so texts with it are still equal
     * to the same texts created again.
     *
     * @param path    Elements of the Litho path of the text, or null if not known yet.
     * @param removed If the view count was removed from the text.
     */
    private record ViewCountSpan(String viewCount, @Nullable String path, boolean removed) {
    }

    /**
     * Litho paths of the texts of the cards that show the badge, such as the videos of the home feed.
     * Texts of other paths, such as the videos of a playlist, keep their view count.
     * Used on more than one thread.
     */
    private static final Set<String> cardPaths = ConcurrentHashMap.newKeySet();
    private static final int MAX_CARD_PATHS = 40;
    private static final int MAX_PATH_LENGTH = 400;
    private static final String PATH_SEPARATOR = "\n";

    static {
        if (ENABLED) {
            loadCardPaths();
        }
    }

    private static void loadCardPaths() {
        try {
            for (String path : Settings.FEED_VIEW_COUNT_PATHS.get().split(PATH_SEPARATOR)) {
                if (!path.isEmpty()) {
                    cardPaths.add(path);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not load the card paths", ex);
            Settings.FEED_VIEW_COUNT_PATHS.resetToDefault();
        }
    }

    /**
     * A card shows the view count in its text, instead of the badge,
     * so the view count is removed from the texts of this path from now on.
     */
    private static synchronized void addCardPath(String path) {
        if (cardPaths.size() >= MAX_CARD_PATHS || !cardPaths.add(path)) {
            return;
        }
        Logger.printDebug(() -> "Card text path: " + path);
        Settings.FEED_VIEW_COUNT_PATHS.save(String.join(PATH_SEPARATOR, cardPaths));
    }

    /**
     * @return The element names of the path, such as 'video_lockup_with_attachment.eml',
     *         without the hashes and the types, which are the same for the same layout.
     */
    @Nullable
    private static String getElementPath(@Nullable ContextInterface contextInterface) {
        if (contextInterface == null) {
            return null;
        }
        StringBuilder path = contextInterface.patch_getPathBuilder();
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        StringJoiner elements = new StringJoiner("|");
        for (String part : path.toString().split("\\|")) {
            if (part.indexOf('.') > 0) {
                elements.add(part);
            }
        }
        String joined = elements.toString();
        if (joined.isEmpty() || joined.length() > MAX_PATH_LENGTH) {
            return null;
        }
        return joined;
    }

    // region view count text

    private static synchronized List<String> getUnits(Locale locale) {
        if (units == null || !locale.equals(unitsLocale)) {
            Set<String> found = new LinkedHashSet<>();
            // Short units, such as M, and long units, such as million.
            for (CompactDecimalFormat.CompactStyle style : CompactDecimalFormat.CompactStyle.values()) {
                try {
                    CompactDecimalFormat format = CompactDecimalFormat.getInstance(locale, style);
                    // From a thousand up to a quadrillion.
                    long value = 1_000;
                    for (int i = 0; i < 13; i++) {
                        // Units of more words keep the space between them, such as thousand crore.
                        String unit = format.format(value).replaceAll("[\\p{Nd}.,'’]", "")
                                .replaceAll("[\\s\u00A0\u202F]+", " ").trim();
                        if (!unit.isEmpty()) {
                            found.add(unit);
                        }
                        value *= 10;
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "Units failure", ex);
                }
            }
            // The app can be in another language than its locale.
            found.add("K");
            found.add("M");
            found.add("B");
            // English (India) shows lakh and crore, but abbreviates them as L and Cr.
            found.add("lakh");
            found.add("crore");
            List<String> list = new ArrayList<>(found);
            list.sort((a, b) -> b.length() - a.length());
            units = list;
            unitsLocale = locale;
        }
        return units;
    }

    private static boolean isSeparator(char c) {
        return Character.isWhitespace(c) || c == '\u00A0' || c == '\u202F';
    }

    /**
     * Languages written without spaces show the unit attached to the word, such as Chinese.
     */
    private static boolean isWrittenWithoutSpaces(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HANGUL
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.THAI;
    }

    /**
     * Result of {@link #parseViewCount}: the shortened count, such as 4,7Mn, and the words of it,
     * such as "views", which are the same for every video.
     */
    private record ParsedViewCount(String count, String words) {
    }

    /**
     * @return The number and its unit, such as 4,7Mn from "4,7 Mn views", or null if there is no number.
     */
    @Nullable
    private static ParsedViewCount parseViewCount(String text, Locale locale) {
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String number = matcher.group().trim();
        final int length = text.length();
        int index = matcher.end();
        while (index < length && isSeparator(text.charAt(index))) {
            index++;
        }

        String unit = "";
        for (String candidate : getUnits(locale)) {
            if (!text.regionMatches(true, index, candidate, 0, candidate.length())) {
                continue;
            }
            final int end = index + candidate.length();
            final boolean wordEnds = end >= length
                    || !Character.isLetterOrDigit(text.charAt(end))
                    || isWrittenWithoutSpaces(candidate.charAt(candidate.length() - 1));
            if (wordEnds) {
                unit = text.substring(index, end);
                index = end;
                break;
            }
        }

        String words = (text.substring(0, matcher.start()) + " " + text.substring(index))
                .replaceAll("[\\s\u00A0\u202F]+", " ").trim();
        // A space keeps the unit apart from the number, so 78 B is not read as 788.
        // Languages written without spaces show the unit attached to the number.
        final boolean attachUnit = unit.isEmpty() || isWrittenWithoutSpaces(unit.charAt(0));
        return new ParsedViewCount(attachUnit ? number + unit : number + "\u00A0" + unit, words);
    }

    private static void addViewWords(String words) {
        if (words.isEmpty()) {
            return;
        }
        synchronized (viewWords) {
            if (viewWords.size() < MAX_VIEW_WORDS) {
                viewWords.add(words);
            }
        }
    }

    private static boolean isViewWords(String words) {
        synchronized (viewWords) {
            return viewWords.contains(words);
        }
    }

    /**
     * A part of the texts below the title, such as the channel, the view count or the upload time.
     */
    private record Segment(String text, int start, int end) {

        boolean startsWithNumber() {
            return !text.isEmpty() && Character.isDigit(text.charAt(0));
        }

        boolean hasNumber() {
            for (int i = 0, length = text.length(); i < length; i++) {
                if (Character.isDigit(text.charAt(i))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * The view count found in the texts below the title.
     *
     * @param index Index of the segment of the view count.
     */
    private record ViewCountMatch(List<Segment> segments, int index, ParsedViewCount parsed) {
    }

    private static List<Segment> splitSegments(String text) {
        List<Segment> segments = new ArrayList<>();
        Matcher matcher = SEGMENT_DELIMITER_PATTERN.matcher(text);
        int start = 0;
        while (matcher.find()) {
            addSegment(text, start, matcher.start(), segments);
            start = matcher.end();
        }
        addSegment(text, start, text.length(), segments);
        return segments;
    }

    /**
     * Adds the text without the surrounding spaces, such as the space after an icon.
     */
    private static void addSegment(String text, int start, int end, List<Segment> segments) {
        while (start < end && isSeparator(text.charAt(start))) {
            start++;
        }
        while (end > start && isSeparator(text.charAt(end - 1))) {
            end--;
        }
        if (end > start) {
            segments.add(new Segment(text.substring(start, end), start, end));
        }
    }

    /**
     * @return The view count of the texts below the title, or null if not found.
     */
    @Nullable
    private static ViewCountMatch findViewCount(String text, Locale locale) {
        if (text.length() > MAX_SUBTITLE_LENGTH) {
            return null;
        }
        List<Segment> segments = splitSegments(text);
        final int size = segments.size();
        if (size == 1 && isSeparator(text.charAt(0))) {
            // The view count alone after an icon, such as the row below the channel of the cards
            // that show the channel and the view count without the upload time.
            return findElementViewCount(text, segments, locale);
        }
        if (size < 2) {
            return null;
        }
        if (size > 3) {
            return findElementViewCount(text, segments, locale);
        }
        ParsedViewCount[] parsed = new ParsedViewCount[size];
        for (int i = 0; i < size; i++) {
            Segment segment = segments.get(i);
            if (segment.text.length() <= MAX_SEGMENT_LENGTH) {
                parsed[i] = parseViewCount(segment.text, locale);
            }
        }

        final int index = findViewCountIndex(segments, parsed);
        if (index < 0) {
            return findElementViewCount(text, segments, locale);
        }
        addViewWords(parsed[index].words);
        return new ViewCountMatch(segments, index, parsed[index]);
    }

    /**
     * @return Index of the segment that is the view count, or -1 if none is.
     */
    private static int findViewCountIndex(List<Segment> segments, ParsedViewCount[] parsed) {
        final int size = segments.size();
        for (int i = 0; i < size; i++) {
            // Words of view counts that were seen before, such as "views".
            if (parsed[i] != null && !parsed[i].words.isEmpty() && isViewWords(parsed[i].words)) {
                return i;
            }
        }
        if (size == 3) {
            // The channel, the view count and the upload time.
            if (parsed[1] != null && segments.get(2).hasNumber()) {
                return 1;
            }
        } else if (parsed[0] != null && segments.get(0).startsWithNumber()
                && segments.get(1).hasNumber()) {
            // The view count and the upload time, such as on the channel page and on the cards.
            // Some languages show a word before the number of the upload time,
            // such as "vor 2 Tagen" in German.
            return 0;
        }
        return -1;
    }

    /**
     * @return The part of a text below the title that is the view count of the element of its video,
     *         or null if the text is not known, see {@link #subtitleViewCounts}.
     */
    @Nullable
    private static ViewCountMatch findElementViewCount(String text, List<Segment> segments, Locale locale) {
        String viewCount;
        synchronized (subtitleViewCounts) {
            viewCount = subtitleViewCounts.get(SPACES_PATTERN.matcher(text).replaceAll(""));
        }
        if (viewCount == null) {
            return null;
        }
        // The last one, as the channel is before the view count.
        for (int i = segments.size() - 1; i >= 0; i--) {
            Segment segment = segments.get(i);
            if (SPACES_PATTERN.matcher(segment.text).replaceAll("").equals(viewCount)) {
                ParsedViewCount parsed = parseViewCount(segment.text, locale);
                return parsed == null ? null : new ViewCountMatch(segments, i, parsed);
            }
        }
        return null;
    }

    /**
     * @return Where the icon right before the view count starts, or -1 if there is none.
     *         Newer layouts draw icons as spans in place of spaces: the dots between the texts,
     *         and a play icon before the view count. Older layouts show the dots as text.
     */
    private static int findIconBefore(Spanned text, List<Segment> segments, int index) {
        final int runStart = index == 0 ? 0 : segments.get(index - 1).end;
        final int runEnd = segments.get(index).start;
        String run = text.subSequence(runStart, runEnd).toString();
        if (run.indexOf('·') >= 0 || run.indexOf('•') >= 0) {
            return -1;
        }
        int iconStart = -1;
        for (Object span : text.getSpans(runStart, runEnd, Object.class)) {
            final int spanStart = text.getSpanStart(span);
            final int spanEnd = text.getSpanEnd(span);
            // Only spans inside the spaces, not spans of the whole text.
            if (spanStart >= runStart && spanEnd <= runEnd && spanEnd > spanStart
                    && spanStart > iconStart) {
                iconStart = spanStart;
            }
        }
        return iconStart;
    }

    /**
     * @return The text without the view count and the delimiter next to it,
     *         or null if the text has no view count.
     */
    @Nullable
    private static SpannableString removeViewCount(CharSequence text) {
        ViewCountMatch match = findViewCount(text.toString(), Locale.getDefault());
        if (match == null) {
            return null;
        }
        List<Segment> segments = match.segments;
        final int index = match.index;
        // The view count and the delimiter after it, or before it if it is the last text.
        Segment segment = segments.get(index);
        int start;
        final int end;
        if (segments.size() == 1) {
            start = segment.start;
            end = segment.end;
        } else if (index < segments.size() - 1) {
            start = segment.start;
            end = segments.get(index + 1).start;
        } else {
            start = segments.get(index - 1).end;
            end = segment.end;
        }
        // Newer layouts show a play icon before the view count, which is removed with it.
        if (text instanceof Spanned spanned) {
            final int iconStart = findIconBefore(spanned, segments, index);
            if (iconStart >= 0) {
                start = Math.min(start, iconStart);
            }
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        for (ViewCountSpan span : builder.getSpans(0, builder.length(), ViewCountSpan.class)) {
            builder.removeSpan(span);
        }
        builder.delete(start, end);
        return new SpannableString(builder);
    }

    @Nullable
    private static ViewCountSpan getViewCountSpan(CharSequence text) {
        if (text instanceof Spanned spanned) {
            ViewCountSpan[] spans = spanned.getSpans(0, spanned.length(), ViewCountSpan.class);
            if (spans.length > 0) {
                return spans[0];
            }
        }
        return null;
    }

    private static SpannableString withSpan(CharSequence text, ViewCountSpan span) {
        SpannableString spannable = new SpannableString(text);
        for (ViewCountSpan old : spannable.getSpans(0, spannable.length(), ViewCountSpan.class)) {
            spannable.removeSpan(old);
        }
        spannable.setSpan(span, 0, spannable.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        return spannable;
    }

    /**
     * Called by {@link LayoutComponentsFilter#modifyFeedSubtitleSpan} when the text below the title
     * of a video is created, such as "Channel - 4.7M views - 2 days ago".
     * The view count is attached to the text as a span.
     * The text still shows it, as the same text is used by lists that do not show the badge.
     */
    public static SpannableString onFeedSubtitleCreated(SpannableString text) {
        if (!ENABLED || text == null) {
            return text;
        }
        try {
            ViewCountMatch match = findViewCount(text.toString(), Locale.getDefault());
            if (match == null) {
                return text;
            }
            return withSpan(text, new ViewCountSpan(match.parsed.count, null, false));
        } catch (Exception ex) {
            Logger.printException(() -> "onFeedSubtitleCreated failure", ex);
        }
        return text;
    }

    /**
     * Called by {@link FeedVideoCardsPatch} when the element of a video that is not live is loaded,
     * before its cards are drawn. Litho draws only the part of a card that is shown, so a card that
     * enters from the bottom of the list shows its thumbnail before the texts below its title.
     * The view count of the element is kept, so the badge shows it as soon as the thumbnail is shown.
     * <p>
     * Only the text shown below the title is used, such as "Channel  4.7M  2 days ago":
     * the text with a part that is the view count of the metadata of the video, such as "4.7M",
     * or else whose parts are also in the text read by accessibility services.
     * Usually called off the main thread.
     *
     * @param isShort If the element is of a Short, whose view count is in the metadata of the element.
     */
    public static void onVideoElementLoaded(String videoId, List<ProtoNode> textNodes, boolean isShort) {
        if (!ENABLED) {
            return;
        }
        try {
            if (isShort) {
                // The cards of Shorts do not show their view count, which is in the metadata of the element.
                String viewCount = null;
                for (ProtoNode node : textNodes) {
                    if (node.pathEndsWith(METADATA_FIELD, 2, 1, 1)) {
                        viewCount = node.getText().trim();
                        break;
                    }
                }
                if (viewCount == null || !NUMBER_PATTERN.matcher(viewCount).find()) {
                    return;
                }
                // Some cards show it below the title, such as the Shorts of the search results.
                // They have no badge, decided now, as the texts of a card that enters the list
                // are drawn after its thumbnail, so the badge would be shown and then removed.
                // The view count is found in any language: the spaces and the marks of the text
                // direction between its characters can differ, and it must not be part of
                // a longer number or word of Latin letters, such as of a URL.
                StringBuilder regex = new StringBuilder("(?<![A-Za-z\\p{Nd}])");
                final int start = regex.length();
                viewCount.codePoints().forEach(codePoint -> {
                    if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                            || Character.getType(codePoint) == Character.FORMAT) {
                        return;
                    }
                    if (regex.length() > start) {
                        regex.append(VIEW_COUNT_GAP);
                    }
                    regex.append(Pattern.quote(new String(Character.toChars(codePoint))));
                });
                regex.append("(?![A-Za-z\\p{Nd}])");
                Pattern countPattern = Pattern.compile(regex.toString());
                ProtoNode shortItem = null;
                for (ProtoNode node : textNodes) {
                    if (node.getText().startsWith(FeedVideoCardsPatch.SHORTS_ITEM_PREFIX)) {
                        shortItem = node.getParent();
                        break;
                    }
                }
                nodes:
                for (ProtoNode node : textNodes) {
                    String text = node.getText();
                    // The texts of the Short itself are not drawn, such as its accessibility label,
                    // which has the view count in some languages.
                    if (text.length() > MAX_SUBTITLE_LENGTH || node.getParent() == shortItem) {
                        continue;
                    }
                    for (ProtoNode parent = node.getParent(); parent != null; parent = parent.getParent()) {
                        final int field = parent.getFieldNumber();
                        if (field == METADATA_FIELD || field == COMMAND_FIELD) {
                            continue nodes;
                        }
                    }
                    if (countPattern.matcher(text).find()) {
                        return;
                    }
                }
                synchronized (shortsViewCounts) {
                    shortsViewCounts.put(videoId, viewCount);
                }
                return;
            }
            Set<String> badgeTexts = new HashSet<>();
            for (ProtoNode node : textNodes) {
                String text = node.getText().trim();
                if (FeedVideoCardsPatch.isVideoBadgeText(text)) {
                    badgeTexts.add(text);
                }
            }
            if (badgeTexts.isEmpty()) {
                return;
            }
            Locale locale = Locale.getDefault();
            // The shortened and the full view count of the metadata of the video, such as "4.7M" and "4.7M views".
            Set<String> metadataViewCounts = new HashSet<>();
            for (ProtoNode node : textNodes) {
                if (node.pathEndsWith(METADATA_FIELD, 2, 1, 1)
                        || node.pathEndsWith(METADATA_FIELD, 2, 3, METADATA_FULL_VIEW_COUNT_FIELD, 2)) {
                    metadataViewCounts.add(SPACES_PATTERN.matcher(node.getText()).replaceAll(""));
                }
            }
            // The texts below the title that have one of them as a part, whatever their other parts are,
            // such as "Channel  4.7M" of a music video without the upload time.
            // The texts of the commands are not shown.
            String metadataViewCount = null;
            nodes:
            for (ProtoNode node : metadataViewCounts.isEmpty() ? List.<ProtoNode>of() : textNodes) {
                String text = node.getText();
                if (text.length() > MAX_SUBTITLE_LENGTH) {
                    continue;
                }
                List<Segment> segments = splitSegments(text);
                if (segments.size() < 2) {
                    continue;
                }
                for (ProtoNode parent = node.getParent(); parent != null; parent = parent.getParent()) {
                    if (parent.getFieldNumber() == COMMAND_FIELD) {
                        continue nodes;
                    }
                }
                for (Segment segment : segments) {
                    String part = SPACES_PATTERN.matcher(segment.text).replaceAll("");
                    if (!metadataViewCounts.contains(part)) {
                        continue;
                    }
                    ParsedViewCount parsed = parseViewCount(segment.text, locale);
                    if (parsed == null) {
                        continue;
                    }
                    // The cards show the text from the view count on in the row below the channel.
                    synchronized (subtitleViewCounts) {
                        subtitleViewCounts.put(SPACES_PATTERN.matcher(text).replaceAll(""), part);
                        subtitleViewCounts.put(SPACES_PATTERN.matcher(text.substring(segment.start))
                                .replaceAll(""), part);
                    }
                    metadataViewCount = parsed.count;
                    break;
                }
            }
            if (metadataViewCount != null) {
                synchronized (videoViewCounts) {
                    videoViewCounts.put(videoId, new ElementViewCount(metadataViewCount, badgeTexts));
                }
                return;
            }
            // The metadata is missing for some accounts and videos. Then the text that accessibility services
            // read is used, such as "Channel, 4.7 million views, 2 days ago": it has each segment of the text
            // below the title, or else it's number, in the same order. It is not a copy of this text,
            // such as the label of the menu, which has the title.
            for (ProtoNode node : textNodes) {
                String text = node.getText();
                if (text.length() > MAX_SUBTITLE_LENGTH) {
                    continue;
                }
                List<Segment> segments = splitSegments(text);
                if (segments.size() < 2) {
                    continue;
                }
                boolean spoken = false;
                final int last = segments.size() - 1;
                String start = text.substring(segments.get(0).start, segments.get(1).end);
                String end = text.substring(segments.get(last - 1).start, segments.get(last).end);
                for (ProtoNode other : textNodes) {
                    String otherText = other.getText();
                    if (other == node || otherText.length() > 2 * MAX_SUBTITLE_LENGTH
                            || otherText.contains(start) || otherText.contains(end)) {
                        continue;
                    }
                    Matcher otherNumber = NUMBER_PATTERN.matcher(otherText);
                    int from = 0;
                    for (Segment segment : segments) {
                        // The same segment, such as the channel, or else it's number, such as of "4.7M".
                        // Both must be whole words, not a part of a longer word or number, such as of a URL.
                        String part = segment.text;
                        int found = otherText.indexOf(part, from);
                        while (found >= 0
                                && ((found > 0 && Character.isLetterOrDigit(otherText.codePointBefore(found)))
                                || (found + part.length() < otherText.length()
                                && Character.isLetterOrDigit(otherText.codePointAt(found + part.length()))))) {
                            found = otherText.indexOf(part, found + 1);
                        }
                        Matcher number = NUMBER_PATTERN.matcher(segment.text);
                        if (found < 0 && number.find()) {
                            part = number.group();
                            found = otherText.indexOf(part, from);
                            while (found >= 0
                                    && ((found > 0 && Character.isLetterOrDigit(otherText.codePointBefore(found)))
                                    || !(otherNumber.region(found, otherText.length()).lookingAt()
                                    && otherNumber.end() == found + part.length()))) {
                                found = otherText.indexOf(part, found + 1);
                            }
                        }
                        if (found < 0) {
                            from = -1;
                            break;
                        }
                        from = found + part.length();
                    }
                    if (from >= 0) {
                        spoken = true;
                        break;
                    }
                }
                if (!spoken) {
                    continue;
                }
                ViewCountMatch match = findViewCount(text, locale);
                if (match != null) {
                    synchronized (videoViewCounts) {
                        videoViewCounts.put(videoId, new ElementViewCount(match.parsed.count, badgeTexts));
                    }
                    return;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onVideoElementLoaded failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * Called when a Litho text is created or reused. Usually called off the main thread.
     * The view count is removed from the texts of the paths of the cards that show the badge.
     */
    public static CharSequence onLithoTextLoaded(ContextInterface contextInterface, CharSequence text) {
        if (!ENABLED || text == null) {
            return text;
        }
        try {
            ViewCountSpan span = getViewCountSpan(text);
            if (span == null || span.removed) {
                return text;
            }
            String path = getElementPath(contextInterface);
            if (path == null) {
                return text;
            }
            if (cardPaths.contains(path)) {
                SpannableString removed = removeViewCount(text);
                if (removed != null) {
                    return withSpan(removed, new ViewCountSpan(span.viewCount, path, true));
                }
                return text;
            }
            if (path.equals(span.path)) {
                return text;
            }
            // Not known yet. The text keeps its view count, and the path is learned
            // if the badge is shown on its card.
            return withSpan(text, new ViewCountSpan(span.viewCount, path, false));
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoTextLoaded failure", ex);
        }
        return text;
    }

    // endregion

    // region card

    /**
     * What is found on a card: its view count, and where its duration badge is.
     */
    private static final class CardInfo {
        @Nullable
        String viewCount;
        /** Span of the text below the title, if the text was created with it. */
        @Nullable
        ViewCountSpan span;
        /** If the text below the title shows the view count, which the Hide view count option removes. */
        boolean textShowsViewCount;
        @Nullable
        Rect duration;
        /** If the card shows a duration or a live badge, which videos do. */
        boolean hasDuration;
        /** Text of the first duration or live badge found, such as "12:34". */
        @Nullable
        String badgeText;
        /**
         * The thumbnail, if a host of its size shows the duration, or else its image.
         * Cards of some layouts show it inset from their edges.
         */
        @Nullable
        Rect thumbnail;
        /** If the thumbnail is the host that shows the duration, not only the image. */
        boolean thumbnailShowsDuration;
        /** Texts below the thumbnail, with their top and left. */
        final List<PlacedText> texts = new ArrayList<>();
        /** Texts above this are on the thumbnail, such as the duration. */
        final int textsTop;

        CardInfo(int textsTop) {
            this.textsTop = textsTop;
        }

        void addText(CharSequence text, Rect rect) {
            if (viewCount == null) {
                span = getViewCountSpan(text);
                if (span != null) {
                    viewCount = span.viewCount;
                    textShowsViewCount = !span.removed && findViewCount(text.toString(), Locale.getDefault()) != null;
                }
            }
            String item = text.toString().trim();
            if (!item.isEmpty() && rect.top >= textsTop && item.length() <= MAX_SUBTITLE_LENGTH) {
                texts.add(new PlacedText(item, rect.top, rect.left));
            }
        }
    }

    private record PlacedText(String text, int top, int left) {
    }

    private static void findCardInfo(ComponentHost root, View view, CardInfo info, Rect rect) {
        if (!info.thumbnailShowsDuration && view instanceof ImageView) {
            rect.set(0, 0, view.getWidth(), view.getHeight());
            root.offsetDescendantRectToMyCoords(view, rect);
            // The smallest, as a background can be as large as the card.
            if (isThumbnailShaped(root, rect)
                    && (info.thumbnail == null || rect.height() < info.thumbnail.height())) {
                info.thumbnail = new Rect(rect);
            }
        }
        if (view instanceof TextView textView) {
            CharSequence text = textView.getText();
            if (text != null) {
                rect.set(0, 0, view.getWidth(), view.getHeight());
                root.offsetDescendantRectToMyCoords(view, rect);
                info.addText(text, rect);
            }
        }
        if (view instanceof ComponentHost host) {
            TextContent textContent = host.getTextContent();
            if (textContent != null) {
                for (CharSequence text : textContent.getTextItems()) {
                    if (text == null) {
                        continue;
                    }
                    rect.set(0, 0, host.getWidth(), host.getHeight());
                    if (host != root) {
                        root.offsetDescendantRectToMyCoords(host, rect);
                    }
                    info.addText(text, rect);
                    String badgeText = text.toString().trim();
                    if (!FeedVideoCardsPatch.isVideoBadgeText(badgeText)
                            || !FeedVideoCardsPatch.isThumbnailBadge(root, rect)) {
                        continue;
                    }
                    info.hasDuration = true;
                    if (info.badgeText == null) {
                        info.badgeText = badgeText;
                    }
                    if (info.duration == null) {
                        if (isDurationBadgeShaped(root, rect)) {
                            info.duration = new Rect(rect);
                        } else if (!info.thumbnailShowsDuration && isThumbnailShaped(root, rect)) {
                            // The duration is drawn by the host of the whole thumbnail.
                            info.thumbnail = new Rect(rect);
                            info.thumbnailShowsDuration = true;
                        }
                    }
                }
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE && !FeedVideoCardsPatch.isHorizontalList(child)) {
                    findCardInfo(root, child, info, rect);
                }
            }
        }
    }

    private static int getTextsTop(ComponentHost root) {
        final float density = root.getResources().getDisplayMetrics().density;
        return Math.round(root.getWidth() * FeedVideoCardsPatch.THUMBNAIL_RATIO - TOLERANCE_DP * density);
    }

    /**
     * @return The view count of the texts below the thumbnail, such as
     *         "Channel - 4.7M views - 2 days ago", or null if not found.
     *         Used when the text was not created with the view count span.
     */
    @Nullable
    private static String findViewCountInTexts(List<PlacedText> texts) {
        texts.sort((a, b) -> a.top != b.top ? a.top - b.top : a.left - b.left);
        Locale locale = Locale.getDefault();
        // The texts below the title are below it, so they are checked first.
        // The title is the first text, and can have numbers and delimiters too.
        final int first = texts.size() > 1 ? 1 : 0;
        for (int i = texts.size() - 1; i >= first; i--) {
            ViewCountMatch match = findViewCount(texts.get(i).text, locale);
            if (match != null) {
                return match.parsed.count;
            }
        }
        return null;
    }

    /** Videos whose texts were logged, so each is logged once. Used only on the main thread. */
    private static final Set<String> loggedVideoIds = new LinkedHashSet<>();
    private static final int MAX_LOGGED_VIDEOS = 200;

    private static void logTexts(String videoId, List<PlacedText> texts) {
        if (!loggedVideoIds.add(videoId)) {
            return;
        }
        if (loggedVideoIds.size() > MAX_LOGGED_VIDEOS) {
            loggedVideoIds.clear();
        }
        Logger.printDebug(() -> {
            StringBuilder builder = new StringBuilder();
            for (PlacedText placed : texts) {
                builder.append(" [").append(placed.text).append(']');
            }
            return "View count not found: " + videoId + " texts:" + builder;
        });
    }

    /**
     * @return The bottom of a square thumbnail, found from the texts below it, or -1.
     *         A square thumbnail is taller than 16:9, so the first text is lower.
     */
    private static int findSquareThumbnailBottom(ComponentHost root, List<PlacedText> texts) {
        final float density = root.getResources().getDisplayMetrics().density;
        int firstTextTop = Integer.MAX_VALUE;
        for (PlacedText placed : texts) {
            if (!FeedVideoCardsPatch.isVideoBadgeText(placed.text)) {
                firstTextTop = Math.min(firstTextTop, placed.top);
            }
        }
        final float wideBottom = root.getWidth() * FeedVideoCardsPatch.THUMBNAIL_RATIO;
        if (firstTextTop == Integer.MAX_VALUE || firstTextTop < wideBottom + SQUARE_MIN_EXTRA_DP * density) {
            return -1;
        }
        return Math.round(firstTextTop - THUMBNAIL_TEXT_GAP_DP * density);
    }

    /**
     * A view almost as wide as the card at its top, such as a 16:9, a square or a vertical thumbnail.
     * Cards of some layouts show the thumbnail inset from their edges, so its shape is
     * checked with its own width.
     */
    private static boolean isThumbnailShaped(ComponentHost root, Rect rect) {
        final float density = root.getResources().getDisplayMetrics().density;
        final int maxInset = Math.round(FeedVideoCardsPatch.MAX_THUMBNAIL_INSET_DP * density);
        final int width = rect.width();
        return width > 0
                && rect.left <= maxInset
                && root.getWidth() - rect.right <= maxInset
                && rect.top <= maxInset
                && rect.height() >= width * FeedVideoCardsPatch.THUMBNAIL_RATIO - TOLERANCE_DP * density
                // Thumbnails can be taller than a square, such as of vertical videos, and have the
                // texts below them. A background can be as large as the card.
                && rect.height() < root.getHeight();
    }

    /**
     * A host only as large as the badge, at the bottom right of the thumbnail,
     * or at the bottom left in right to left languages.
     */
    private static boolean isDurationBadgeShaped(ComponentHost root, Rect rect) {
        final float density = root.getResources().getDisplayMetrics().density;
        // Thumbnails are 16:9 or square, such as of music videos.
        return rect.width() > 0
                && rect.width() <= FeedVideoCardsPatch.MAX_DURATION_WIDTH_DP * density
                && rect.height() >= MIN_DURATION_HEIGHT_DP * density
                && rect.height() <= FeedVideoCardsPatch.MAX_DURATION_HEIGHT_DP * density
                && (isRtl(root) ? rect.centerX() < root.getWidth() / 2 : rect.centerX() > root.getWidth() / 2)
                && rect.bottom <= root.getWidth() + TOLERANCE_DP * density;
    }

    /**
     * @return If the card is laid out right to left, where the duration badge is at the bottom left
     *         of the thumbnail, so the badges are placed on the other side.
     */
    public static boolean isRtl(View view) {
        return view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    /**
     * Where the duration badge and the thumbnail of a card are, relative to the card.
     *
     * @param duration  The duration badge, or null if not found.
     * @param thumbnail The thumbnail, or null if not found.
     * @param estimated If the duration badge is placed as high as it can be, because what it's placed from is not drawn yet,
     *                  such as the title of a Short that is only partly shown. It is placed again once that is drawn.
     */
        public record ThumbnailFrame(@Nullable Rect duration, @Nullable Rect thumbnail,
                                     boolean estimated) {
    }

    /**
     * Finds the duration badge and the thumbnail of a card, the same way as for the view count badge.
     * The like count badge of the Return YouTube Dislike patch uses it too. So both badges are placed
     * on cards whose thumbnail is inset from their edges, such as the shelves of a channel,
     * or is below other content, such as the video of a channel post.
     * Must be called on the main thread.
     */
    public static ThumbnailFrame findThumbnailFrame(ComponentHost host) {
        CardInfo info = new CardInfo(getTextsTop(host));
        findCardInfo(host, host, info, new Rect());
        Rect thumbnail = info.thumbnail;
        if (info.duration == null && thumbnail == null) {
            final int squareBottom = findSquareThumbnailBottom(host, info.texts);
            if (squareBottom > 0) {
                thumbnail = new Rect(0, 0, host.getWidth(), squareBottom);
            }
        }
        return new ThumbnailFrame(info.duration, thumbnail, false);
    }

    /**
     * Called on the main thread after a root host is laid out.
     *
     * @param videoId     The video of the card, or null if the host is not a video card
     *                    or its video is not known.
     * @param shortsFrame If the host is the card of a Short, its thumbnail and the row above its title,
     *                    as the duration badge at the end of the row. Null for any other card.
     */
    public static void onCardLaidOut(ComponentHost host, @Nullable String videoId,
                                     @Nullable ThumbnailFrame shortsFrame) {
        if (!ENABLED) {
            return;
        }
        String viewCount = null;
        ViewCountSpan span = null;
        boolean textShowsViewCount = false;
        Rect duration = null;
        Rect thumbnail = null;
        // Videos of shelves are not known, but the view count is shown by the card itself,
        // so it is shown on any card shaped like a video with a duration.
        // Cards of a grid, such as in landscape, are narrower than the screen and their video is not known.
        final boolean checkCard = shortsFrame == null && (videoId != null
                || (FeedVideoCardsPatch.isVideoShaped(host, MIN_GRID_CARD_WIDTH_OF_SCREEN)
                && !FeedVideoCardsPatch.isPlaylistCard(host)
                && !FeedVideoCardsPatch.hasHorizontalList(host)));
        if (shortsFrame != null && videoId != null) {
            synchronized (shortsViewCounts) {
                viewCount = shortsViewCounts.get(videoId);
            }
            if (viewCount != null) {
                // The view count is shown only once, and some layouts can show it below the title.
                CardInfo info = new CardInfo(0);
                findCardInfo(host, host, info, new Rect());
                String count = SPACES_PATTERN.matcher(viewCount).replaceAll("");
                for (PlacedText placed : info.texts) {
                    if (SPACES_PATTERN.matcher(placed.text).replaceAll("").contains(count)) {
                        viewCount = null;
                        break;
                    }
                }
            }
            duration = shortsFrame.duration;
            thumbnail = shortsFrame.thumbnail;
        } else if (checkCard && !FeedVideoCardsPatch.isInDialog(host) && !(videoId != null
                // The card can still be known as the video it showed before, while it enters the list.
                ? FeedVideoCardsPatch.isLiveVideo(videoId)
                : FeedVideoCardsPatch.isLiveCard(host))) {
            CardInfo info = new CardInfo(getTextsTop(host));
            findCardInfo(host, host, info, new Rect());
            if (videoId != null || info.hasDuration) {
                viewCount = info.viewCount;
                span = info.span;
                textShowsViewCount = info.textShowsViewCount;
                duration = info.duration;
                thumbnail = info.thumbnail;
                if (duration == null && thumbnail == null) {
                    final int squareBottom = findSquareThumbnailBottom(host, info.texts);
                    if (squareBottom > 0) {
                        thumbnail = new Rect(0, 0, host.getWidth(), squareBottom);
                    }
                }
                if (viewCount == null) {
                    final int textsTop = duration != null
                            ? duration.bottom
                            : thumbnail != null ? thumbnail.bottom : -1;
                    if (textsTop > 0) {
                        // Texts on a square thumbnail are not texts of the video.
                        info.texts.removeIf(placed -> placed.top < textsTop);
                    }
                    viewCount = findViewCountInTexts(info.texts);
                    textShowsViewCount = viewCount != null;
                }
                if (viewCount == null) {
                    // The texts below the title are not drawn yet, such as while only the thumbnail of
                    // a card that enters the list is shown, so the view count of the element is shown.
                    // The element of the video is loaded before its card, so the view count is known
                    // as soon as the card is shown. Before its duration is drawn, the card is known
                    // only by the video passed, which is a video with a duration.
                    String cardVideoId = videoId != null
                            ? videoId
                            : FeedVideoCardsPatch.findVideoIdByDescription(host, false);
                    if (cardVideoId != null && (info.badgeText != null || videoId != null)) {
                        ElementViewCount element;
                        synchronized (videoViewCounts) {
                            element = videoViewCounts.get(cardVideoId);
                        }
                        if (element != null
                                && (info.badgeText == null || element.badgeTexts.contains(info.badgeText))) {
                            viewCount = element.viewCount;
                            // Checked again when its texts are drawn, as they can show the view count.
                            FeedVideoCardsPatch.scheduleScrollCheck(host);
                        }
                    }
                }
                final String id = videoId == null ? "unknown" : videoId;
                if (viewCount == null) {
                    // Checked again when its texts are drawn, as they can show the view count.
                    FeedVideoCardsPatch.scheduleRetry(host);
                    logTexts(id, info.texts);
                } else if (duration == null) {
                    final Rect found = thumbnail;
                    Logger.printDebug(() -> "Duration badge not found: " + id + " thumbnail: " + found);
                }
            }
        }

        if (textShowsViewCount) {
            // The view count is shown only once.
            viewCount = null;
            // The text still shows the view count, as its path was not known yet.
            // Texts of this path do not show it from now on, and their cards show the badge.
            if (span != null && !span.removed && span.path != null) {
                addCardPath(span.path);
            }
        }

        ViewCountBadge badge = badges.get(host);
        if (viewCount == null) {
            if (badge != null) {
                badge.detach(host);
                badges.remove(host);
            }
            return;
        }
        if (badge == null) {
            badge = new ViewCountBadge(host);
            badges.put(host, badge);
        }
        badge.bind(host, viewCount, duration, thumbnail);
    }

    // endregion

    /**
     * Dark rounded badge with a play icon and the view count, drawn on the overlay of the card.
     */
    private static final class ViewCountBadge extends Drawable implements View.OnAttachStateChangeListener {
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path iconPath = new Path();
        private final RectF rect = new RectF();
        private final float density;
        private final float maxTextSize;
        private final WeakReference<ComponentHost> hostRef;

        /**
         * False after the card was detached, such as when a list reuses it for something else.
         * Nothing is drawn until the card is checked again, so the view count of the previous
         * video is not shown on what the card shows now.
         */
        private boolean confirmed = true;
        private String viewCount = "";
        private boolean added;
        private int alpha = 255;
        @Nullable
        private ValueAnimator fadeIn;

        ViewCountBadge(ComponentHost host) {
            hostRef = new WeakReference<>(host);
            host.addOnAttachStateChangeListener(this);
            density = host.getResources().getDisplayMetrics().density;
            maxTextSize = TEXT_SP * density * host.getResources().getConfiguration().fontScale;

            backgroundPaint.setColor(BACKGROUND_COLOR);

            textPaint.setColor(TEXT_COLOR);
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            textPaint.setTextSize(maxTextSize);

            iconPaint.setColor(TEXT_COLOR);
            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            iconPaint.setStrokeWidth(ICON_STROKE_DP * density);
        }

        private int px(float dp) {
            return Math.round(dp * density);
        }

        /**
         * @param thumbnail The thumbnail, or null if not found.
         *                  Then a 16:9 thumbnail as wide as the card is used.
         */
        void bind(ComponentHost host, String newViewCount, @Nullable Rect duration, @Nullable Rect thumbnail) {
            confirmed = true;
            // Fades in when shown, or when the card shows another video.
            // Not when the card is only laid out again.
            final boolean changed = !newViewCount.equals(viewCount);
            viewCount = newViewCount;

            // Cards of some layouts show the thumbnail inset from their edges,
            // so the margins are from the edges of the thumbnail, not of the card.
            final Rect frame = thumbnail != null
                    ? thumbnail
                    : new Rect(0, 0, host.getWidth(),
                    Math.round(host.getWidth() * FeedVideoCardsPatch.THUMBNAIL_RATIO));

            // Same height, bottom and margin as the duration badge, on the other side of the thumbnail.
            final boolean rtl = isRtl(host);
            final int top;
            final int bottom;
            // Distance of the badge from the edge of the thumbnail on its side.
            final int margin;
            if (duration != null) {
                top = duration.top;
                bottom = duration.bottom;
                margin = Math.max(0, rtl ? duration.left - frame.left : frame.right - duration.right)
                        + px(EXTRA_MARGIN_DP);
            } else {
                bottom = frame.bottom - px(FALLBACK_MARGIN_DP);
                top = bottom - px(FALLBACK_HEIGHT_DP);
                margin = px(FALLBACK_MARGIN_DP + EXTRA_MARGIN_DP);
            }
            final int height = bottom - top;

            textPaint.setTextSize(Math.min(maxTextSize, height * TEXT_SIZE_OF_HEIGHT));
            final float iconSize = textPaint.getTextSize() * ICON_SIZE_OF_TEXT;
            final int width = Math.round(px(PADDING_DP) * 2 + iconSize + px(GAP_DP)
                    + textPaint.measureText(viewCount));

            final int left = rtl ? frame.right - margin - width : frame.left + margin;
            setBounds(left, top, left + width, bottom);
            if (!added) {
                host.getOverlay().add(this);
                added = true;
            }
            if (changed) {
                startFadeIn();
            } else {
                invalidateSelf();
            }
        }

        private void startFadeIn() {
            endFadeIn();
            ValueAnimator animator = ValueAnimator.ofInt(0, 255);
            animator.setDuration(FeedVideoCardsPatch.BADGE_FADE_IN_MS);
            animator.addUpdateListener(animation -> setAlpha((int) animation.getAnimatedValue()));
            fadeIn = animator;
            setAlpha(0);
            animator.start();
        }

        /**
         * Ends the fade in, so the badge is fully shown.
         */
        private void endFadeIn() {
            if (fadeIn != null) {
                ValueAnimator animator = fadeIn;
                fadeIn = null;
                animator.end();
            }
        }

        void detach(ComponentHost host) {
            host.removeOnAttachStateChangeListener(this);
            endFadeIn();
            if (added) {
                host.getOverlay().remove(this);
                added = false;
            }
        }

        @Override
        public void onViewAttachedToWindow(@NonNull View view) {
            try {
                // A card that shows the same video may not be laid out again.
                FeedVideoCardsPatch.requestUpdate((ComponentHost) view);
            } catch (Exception ex) {
                Logger.printException(() -> "onViewAttachedToWindow failure", ex);
            }
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull View view) {
            confirmed = false;
            endFadeIn();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (!confirmed) {
                return;
            }
            ComponentHost host = hostRef.get();
            if (host != null && FeedVideoCardsPatch.isCardPlayingInline(host)) {
                // The inline player shows its own controls over the thumbnail.
                return;
            }
            backgroundPaint.setAlpha(Color.alpha(BACKGROUND_COLOR) * alpha / 255);
            textPaint.setAlpha(alpha);
            iconPaint.setAlpha(alpha);

            Rect bounds = getBounds();
            final float radius = px(RADIUS_DP);
            rect.set(bounds);
            canvas.drawRoundRect(rect, radius, radius, backgroundPaint);

            final float centerY = bounds.exactCenterY();
            final float iconSize = textPaint.getTextSize() * ICON_SIZE_OF_TEXT;
            final float iconLeft = bounds.left + px(PADDING_DP) + iconPaint.getStrokeWidth() / 2;
            final float iconHeight = iconSize - iconPaint.getStrokeWidth();
            final float iconWidth = iconHeight * ICON_WIDTH_OF_HEIGHT;
            iconPath.reset();
            iconPath.moveTo(iconLeft, centerY - iconHeight / 2);
            iconPath.lineTo(iconLeft + iconWidth, centerY);
            iconPath.lineTo(iconLeft, centerY + iconHeight / 2);
            iconPath.close();
            canvas.drawPath(iconPath, iconPaint);

            final float textX = bounds.left + px(PADDING_DP) + iconSize + px(GAP_DP);
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            final float baseline = centerY - (metrics.ascent + metrics.descent) / 2;
            canvas.drawText(viewCount, textX, baseline, textPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Deprecated
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
