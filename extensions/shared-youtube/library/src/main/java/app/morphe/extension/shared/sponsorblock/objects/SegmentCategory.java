package app.morphe.extension.shared.sponsorblock.objects;

import static app.morphe.extension.shared.StringRef.sf;

import android.graphics.Color;
import android.graphics.Paint;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.sponsorblock.SponsorBlockApi;

/**
 * SponsorBlock segment category. The enum is host-app agnostic - {@link StringSetting} bindings
 * for behavior and color are resolved on demand via {@link SponsorBlockApi.CategorySettingsProvider}.
 */
public enum SegmentCategory {
    SPONSOR("sponsor", sf("morphe_sb_segments_sponsor"), sf("morphe_sb_segments_sponsor_summary"), sf("morphe_sb_skip_button_sponsor"), sf("morphe_sb_skipped_sponsor")),
    SELF_PROMO("selfpromo", sf("morphe_sb_segments_selfpromo"), sf("morphe_sb_segments_selfpromo_summary"), sf("morphe_sb_skip_button_selfpromo"), sf("morphe_sb_skipped_selfpromo")),
    INTERACTION("interaction", sf("morphe_sb_segments_interaction"), sf("morphe_sb_segments_interaction_summary"), sf("morphe_sb_skip_button_interaction"), sf("morphe_sb_skipped_interaction")),
    /**
     * Unique category that is treated differently than the rest.
     */
    HIGHLIGHT("poi_highlight", sf("morphe_sb_segments_highlight"), sf("morphe_sb_segments_highlight_summary"), sf("morphe_sb_skip_button_highlight"), sf("morphe_sb_skipped_highlight")),
    INTRO("intro", sf("morphe_sb_segments_intro"), sf("morphe_sb_segments_intro_summary"),
            sf("morphe_sb_skip_button_intro_beginning"), sf("morphe_sb_skip_button_intro_middle"), sf("morphe_sb_skip_button_intro_end"),
            sf("morphe_sb_skipped_intro_beginning"), sf("morphe_sb_skipped_intro_middle"), sf("morphe_sb_skipped_intro_end")),
    OUTRO("outro", sf("morphe_sb_segments_outro"), sf("morphe_sb_segments_outro_summary"), sf("morphe_sb_skip_button_outro"), sf("morphe_sb_skipped_outro")),
    PREVIEW("preview", sf("morphe_sb_segments_preview"), sf("morphe_sb_segments_preview_summary"),
            sf("morphe_sb_skip_button_preview_beginning"), sf("morphe_sb_skip_button_preview_middle"), sf("morphe_sb_skip_button_preview_end"),
            sf("morphe_sb_skipped_preview_beginning"), sf("morphe_sb_skipped_preview_middle"), sf("morphe_sb_skipped_preview_end")),
    HOOK("hook", sf("morphe_sb_segments_hook"), sf("morphe_sb_segments_hook_summary"), sf("morphe_sb_skip_button_hook"), sf("morphe_sb_skipped_hook")),
    FILLER("filler", sf("morphe_sb_segments_filler"), sf("morphe_sb_segments_filler_summary"), sf("morphe_sb_skip_button_filler"), sf("morphe_sb_skipped_filler")),
    MUSIC_OFFTOPIC("music_offtopic", sf("morphe_sb_segments_nomusic"), sf("morphe_sb_segments_nomusic_summary"), sf("morphe_sb_skip_button_nomusic"), sf("morphe_sb_skipped_nomusic")),
    UNSUBMITTED("unsubmitted", StringRef.empty, StringRef.empty, sf("morphe_sb_skip_button_unsubmitted"), sf("morphe_sb_skipped_unsubmitted"));

    private static final StringRef skipSponsorTextCompact = sf("morphe_sb_skip_button_compact");
    private static final StringRef skipSponsorTextCompactHighlight = sf("morphe_sb_skip_button_compact_highlight");

    private static final SegmentCategory[] categoriesWithoutHighlights = new SegmentCategory[]{
            SPONSOR,
            SELF_PROMO,
            INTERACTION,
            INTRO,
            OUTRO,
            PREVIEW,
            HOOK,
            FILLER,
            MUSIC_OFFTOPIC
    };

    private static final SegmentCategory[] categoriesWithoutUnsubmitted = new SegmentCategory[]{
            SPONSOR,
            SELF_PROMO,
            INTERACTION,
            HIGHLIGHT,
            INTRO,
            OUTRO,
            PREVIEW,
            HOOK,
            FILLER,
            MUSIC_OFFTOPIC
    };

    public static final String COLOR_DOT_STRING = "⬤";

    public static final float CATEGORY_DEFAULT_OPACITY = 0.7f;

    private static final Map<String, SegmentCategory> mValuesMap = new HashMap<>(2 * categoriesWithoutUnsubmitted.length);

    /**
     * Categories currently enabled, formatted for an API call.
     */
    public static String sponsorBlockAPIFetchCategories = "[]";

    static {
        for (SegmentCategory value : categoriesWithoutUnsubmitted)
            mValuesMap.put(value.keyValue, value);
    }

    /**
     * Returns an array of categories excluding the unsubmitted category.
     */
    public static SegmentCategory[] categoriesWithoutUnsubmitted() {
        return categoriesWithoutUnsubmitted;
    }

    /**
     * Returns an array of categories excluding the highlight category.
     */
    public static SegmentCategory[] categoriesWithoutHighlights() {
        return categoriesWithoutHighlights;
    }

    /**
     * Categories the current host app actually consumes. Drives setting loading and the API request.
     * Filtered by {@link SponsorBlockApi.Configuration#includesHighlight()}; {@link #UNSUBMITTED}
     * is always excluded (used internally only).
     */
    @NonNull
    public static SegmentCategory[] activeCategories() {
        SponsorBlockApi.Configuration config = SponsorBlockApi.config();
        List<SegmentCategory> active = new ArrayList<>(categoriesWithoutUnsubmitted.length);
        for (SegmentCategory cat : categoriesWithoutUnsubmitted) {
            if (cat == HIGHLIGHT && !config.includesHighlight()) continue;
            active.add(cat);
        }
        return active.toArray(new SegmentCategory[0]);
    }

    /**
     * Retrieves a category by its key.
     */
    @Nullable
    public static SegmentCategory byCategoryKey(@NonNull String key) {
        return mValuesMap.get(key);
    }

    /**
     * Updates the list of enabled categories for API calls. Must be called when any category's behavior changes.
     */
    public static void updateEnabledCategories() {
        Utils.verifyOnMainThread();
        Logger.printDebug(() -> "updateEnabledCategories");
        SegmentCategory[] categories = activeCategories();
        List<String> enabledCategories = new ArrayList<>(categories.length);
        for (SegmentCategory category : categories) {
            if (category.behaviour != CategoryBehaviour.IGNORE) {
                enabledCategories.add(category.keyValue);
            }
        }

        //"[%22sponsor%22,%22outro%22,%22music_offtopic%22,%22intro%22,%22selfpromo%22,%22interaction%22,%22preview%22]";
        if (enabledCategories.isEmpty())
            sponsorBlockAPIFetchCategories = "[]";
        else
            sponsorBlockAPIFetchCategories = "[%22" + TextUtils.join("%22,%22", enabledCategories) + "%22]";
    }

    /**
     * Loads all category settings from persistent storage.
     */
    public static void loadAllCategoriesFromSettings() {
        for (SegmentCategory category : activeCategories()) {
            category.loadFromSettings();
        }
        // UNSUBMITTED is host-internal (not in the API request set) and excluded from
        // activeCategories(), so its persisted color/behavior must be loaded explicitly.
        if (SponsorBlockApi.config().supportsSegmentCreation()) {
            UNSUBMITTED.loadFromSettings();
        }
        updateEnabledCategories();
    }

    public final String keyValue;

    public final StringRef title;
    public final StringRef description;

    /**
     * Skip button text, if the skip occurs in the first quarter of the video.
     */
    public final StringRef skipButtonTextBeginning;
    /**
     * Skip button text, if the skip occurs in the middle half of the video.
     */
    public final StringRef skipButtonTextMiddle;
    /**
     * Skip button text, if the skip occurs in the last quarter of the video.
     */
    public final StringRef skipButtonTextEnd;
    /**
     * Skipped segment toast, if the skip occurred in the first quarter of the video.
     */
    public final StringRef skippedToastBeginning;
    /**
     * Skipped segment toast, if the skip occurred in the middle half of the video.
     */
    public final StringRef skippedToastMiddle;
    /**
     * Skipped segment toast, if the skip occurred in the last quarter of the video.
     */
    public final StringRef skippedToastEnd;

    public final Paint paint;

    /**
     * Lazily-resolved StringSetting backing this category's behavior. Cached so that the hot
     * playback loops do not pay for the {@code SponsorBlockApi.config().categorySettings()} chain
     * on every access. Enum constants are singletons, so the cache lives for the app's lifetime —
     * configure() is called once per APK launch and never replaced.
     */
    @Nullable
    private StringSetting cachedBehaviorSetting;
    @Nullable
    private StringSetting cachedColorSetting;

    /**
     * Category color with opacity applied.
     */
    @ColorInt
    private int color;

    /**
     * Value must be changed using {@link #setBehaviour(CategoryBehaviour)}.
     * Caller must also call {@link #updateEnabledCategories()}.
     * <p>
     * Marked {@code volatile} because the field is written from the settings UI thread and read
     * from the player playback thread (notably the Music media-player background thread that
     * dispatches the {@code setVideoTime} hook); without it, toggling a category off may not be
     * observed promptly in the hot iteration loop.
     */
    public volatile CategoryBehaviour behaviour = CategoryBehaviour.IGNORE;

    SegmentCategory(String keyValue, StringRef title, StringRef description,
                    StringRef skipButtonText,
                    StringRef skippedToastText) {
        this(keyValue, title, description,
                skipButtonText, skipButtonText, skipButtonText,
                skippedToastText, skippedToastText, skippedToastText);
    }

    SegmentCategory(String keyValue, StringRef title, StringRef description,
                    StringRef skipButtonTextBeginning, StringRef skipButtonTextMiddle, StringRef skipButtonTextEnd,
                    StringRef skippedToastBeginning, StringRef skippedToastMiddle, StringRef skippedToastEnd) {
        this.keyValue = Objects.requireNonNull(keyValue);
        this.title = Objects.requireNonNull(title);
        this.description = Objects.requireNonNull(description);
        this.skipButtonTextBeginning = Objects.requireNonNull(skipButtonTextBeginning);
        this.skipButtonTextMiddle = Objects.requireNonNull(skipButtonTextMiddle);
        this.skipButtonTextEnd = Objects.requireNonNull(skipButtonTextEnd);
        this.skippedToastBeginning = Objects.requireNonNull(skippedToastBeginning);
        this.skippedToastMiddle = Objects.requireNonNull(skippedToastMiddle);
        this.skippedToastEnd = Objects.requireNonNull(skippedToastEnd);
        this.paint = new Paint();
    }

    /**
     * @return The host app's behavior {@link StringSetting} backing this category.
     */
    @NonNull
    public StringSetting behaviorSetting() {
        StringSetting s = cachedBehaviorSetting;
        if (s == null) {
            s = SponsorBlockApi.config().categorySettings().behaviorFor(this);
            cachedBehaviorSetting = s;
        }
        return s;
    }

    /**
     * @return The host app's color {@link StringSetting} backing this category.
     */
    @NonNull
    public StringSetting colorSetting() {
        StringSetting s = cachedColorSetting;
        if (s == null) {
            s = SponsorBlockApi.config().categorySettings().colorFor(this);
            cachedColorSetting = s;
        }
        return s;
    }

    /**
     * Loads the category's behavior and color from settings.
     */
    private void loadFromSettings() {
        StringSetting behaviorSetting = behaviorSetting();
        String behaviorString = behaviorSetting.get();
        CategoryBehaviour savedBehavior = CategoryBehaviour.byMorpheKeyValue(behaviorString);
        if (savedBehavior == null) {
            Logger.printException(() -> "Invalid behavior: " + behaviorString);
            behaviorSetting.resetToDefault();
            loadFromSettings();
            return;
        }
        this.behaviour = savedBehavior;

        StringSetting colorSetting = colorSetting();
        String colorString = colorSetting.get();
        try {
            setColorWithOpacity(colorString);
        } catch (Exception ex) {
            Logger.printException(() -> "Invalid color: " + colorString, ex);
            colorSetting.resetToDefault();
            loadFromSettings();
        }
    }

    /**
     * Sets the behavior of the category and saves it to settings.
     */
    public void setBehaviour(CategoryBehaviour behaviour) {
        this.behaviour = Objects.requireNonNull(behaviour);
        Logger.printDebug(() -> "SegmentCategory " + keyValue + " behaviour -> " + behaviour.morpheKeyValue);
        behaviorSetting().save(behaviour.morpheKeyValue);
    }

    /**
     * Sets the segment color with opacity from a color string in #AARRGGBB format.
     */
    public void setColorWithOpacity(String colorString) throws IllegalArgumentException {
        int colorWithOpacity = Color.parseColor(colorString);
        colorSetting().save(String.format(Locale.US, "#%08X", colorWithOpacity));
        color = colorWithOpacity;
        paint.setColor(color);
    }

    /**
     * @param opacity [0, 1] opacity value.
     */
    public void setOpacity(double opacity) {
        color = Color.argb((int) (opacity * 255), Color.red(color), Color.green(color), Color.blue(color));
        paint.setColor(color);
    }

    /**
     * Gets the color with opacity applied (ARGB).
     */
    @ColorInt
    public int getColorWithOpacity() {
        return color;
    }

    /**
     * @return The default color with opacity applied.
     */
    @ColorInt
    public int getDefaultColorWithOpacity() {
        return Color.parseColor(colorSetting().defaultValue);
    }

    /**
     * @return The color as a hex string without opacity (#RRGGBB).
     */
    public String getColorStringWithoutOpacity() {
        final int colorNoOpacity = getColorWithOpacity() & 0x00FFFFFF;
        return String.format(Locale.US, "#%06X", colorNoOpacity);
    }

    /**
     * @return [0, 1] opacity value.
     */
    public double getOpacity() {
        double opacity = Color.alpha(color) / 255.0;
        return Math.round(opacity * 100.0) / 100.0; // Round to 2 decimal digits.
    }

    /**
     * Gets the title of the category.
     */
    public StringRef getTitle() {
        return title;
    }

    /**
     * Creates a {@link SpannableString} that starts with a colored dot followed by the provided text.
     */
    private static SpannableString getCategoryColorDotSpan(String text, @ColorInt int color) {
        SpannableString dotSpan = new SpannableString(COLOR_DOT_STRING + text);
        dotSpan.setSpan(new ForegroundColorSpan(color), 0, 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        dotSpan.setSpan(new RelativeSizeSpan(1.5f), 0, 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return dotSpan;
    }

    /**
     * Returns the category title with a colored dot.
     */
    public SpannableString getTitleWithColorDot(@ColorInt int categoryColor) {
        return getCategoryColorDotSpan(" " + title, categoryColor);
    }

    /**
     * Returns the category title with a colored dot.
     */
    public SpannableString getTitleWithColorDot() {
        return getTitleWithColorDot(color);
    }

    /**
     * Gets the skip button text based on segment position.
     *
     * @param segmentStartTime Video time the segment category started.
     * @param videoLength      Length of the video.
     * @return The skip button text.
     */
    public StringRef getSkipButtonText(long segmentStartTime, long videoLength) {
        if (SponsorBlockApi.config().compactSkipButtonEnabled()) {
            return (this == SegmentCategory.HIGHLIGHT)
                    ? skipSponsorTextCompactHighlight
                    : skipSponsorTextCompact;
        }

        if (videoLength == 0) {
            return skipButtonTextBeginning; // Video is still loading. Assume it's the beginning.
        }
        final float position = segmentStartTime / (float) videoLength;
        if (position < 0.25f) {
            return skipButtonTextBeginning;
        } else if (position < 0.75f) {
            return skipButtonTextMiddle;
        }
        return skipButtonTextEnd;
    }

    /**
     * Gets the skipped segment toast message based on segment position.
     *
     * @param segmentStartTime Video time the segment category started.
     * @param videoLength      Length of the video.
     * @return The skipped segment toast message.
     */
    public StringRef getSkippedToastText(long segmentStartTime, long videoLength) {
        if (videoLength == 0) {
            return skippedToastBeginning; // Video is still loading. Assume it's the beginning.
        }
        final float position = segmentStartTime / (float) videoLength;
        if (position < 0.25f) {
            return skippedToastBeginning;
        } else if (position < 0.75f) {
            return skippedToastMiddle;
        }
        return skippedToastEnd;
    }
}
