/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

import app.morphe.extension.music.patches.lyrics.derive.StoryAiResult;
import app.morphe.extension.music.patches.lyrics.derive.WeightedWord;
import app.morphe.extension.music.patches.lyrics.derive.WordCloudBuilder;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Renders 1–5 selected lyric lines onto an off-screen PNG card styled with the track's
 * {@link DynamicBackgroundView} blurred artwork mesh or curated solid palette and {@link TrackInfo}
 * metadata.
 */
final class LyricsShareCardRenderer {

    static final int DEFAULT_CARD_SIZE_PX = 1440;
    /** Slot 0 is the live album colour, -1 is a custom colour, the rest are fixed presets. */
    static final int[] SOLID_PRESET_COLORS = {
            0xFF282038, 0xFF3E1C26, 0xFF17353B, 0xFF1D3326, 0xFF161618, 0xFFF3F3F5
    };

    static int solidPresetColor(int slot) {
        int index = slot - 1;
        return index >= 0 && index < SOLID_PRESET_COLORS.length
                ? SOLID_PRESET_COLORS[index] : CardPalette.DEFAULT.backgroundColor();
    }

    /** Text colour presets: 0=default white, 1=auto, -1=custom, so presets start at 2. */
    static final int[] TEXT_COLOR_PRESET_COLORS = {
            0xFFFFFFFF, 0xFFFFD54F, 0xFFFF8A80, 0xFF69F0AE,
            0xFF4FC3F7, 0xFFB388FF, 0xFFE8E8EE, 0xFF141416
    };

    static int textColorPreset(int slot) {
        int index = slot - 2;
        return index >= 0 && index < TEXT_COLOR_PRESET_COLORS.length
                ? TEXT_COLOR_PRESET_COLORS[index] : TEXT_COLOR_PRESET_COLORS[0];
    }

    /** Same scrim the card and the fullscreen cover background use for image backdrops. */
    static final int[] COVER_SCRIM_COLORS = {0x8A000000, 0x7A000000, 0xA6000000};
    private static final float LOGICAL_CARD_WIDTH = 340f;
    private static final String SHARE_CACHE_DIR = "lyrics_share";
    private static final String SHARE_FILE_PREFIX = "lyrics_card_";
    private static final float KEYWORD_BAND_HEIGHT = 20f;
    private static final float DATE_BAND_HEIGHT = 24f;
    private static final float SOURCE_LINE_HEIGHT = 16f;
    private static final float BAND_FOOTER_GAP = 24f;
    private static final DateTimeFormatter WEEKDAY_FORMAT =
            DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH);
    private static final DateTimeFormatter WEEKDAY_FULL_FORMAT =
            DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    private LyricsShareCardRenderer() {
    }

    /** Logical card geometry for share cards. */
    record CardLayout(
            float cardWidth,
            float cardHeight,
            float padding,
            float artworkSize,
            float lyricsWidth,
            float lyricsTop,
            float lyricsHeight,
            float footerTop
    ) {
    }

    /** Fitted font size and fallback scale when minimum legible size overflows available height. */
    record TextFit(float fontSize, float drawScale) {
    }

    /** Extracted color palette from {@link DynamicBackgroundView}'s artwork/buffer. */
    record CardPalette(
            int backgroundColor,
            int gradientStartColor,
            int gradientEndColor,
            int accentColor,
            int textColor,
            int secondaryTextColor
    ) {
        static final CardPalette DEFAULT = new CardPalette(
                0xFF1A1A22,
                0xFF282838,
                0xFF121218,
                0xFFE8E8F0,
                0xFFFFFFFF,
                0xB8FFFFFF
        );
    }

    enum BackgroundMode {
        BLUR_MESH,
        ALBUM_COVER,
        SOLID_COLOR,
        CUSTOM_IMAGE
    }

    /** Overall visual layout of the share card. Currently all modes render identically (placeholders). */
    enum CardMode {
        LYRICS,
        WORD_CLOUD,
        STORY
    }

    record CardSwatch(
            CardPalette palette,
            BackgroundMode backgroundMode,
            int swatchColor
    ) {
        CardSwatch(CardPalette palette, boolean useBlurredMesh, int swatchColor) {
            this(palette, useBlurredMesh ? BackgroundMode.BLUR_MESH : BackgroundMode.SOLID_COLOR, swatchColor);
        }

        boolean useBlurredMesh() {
            return backgroundMode == BackgroundMode.BLUR_MESH;
        }
    }

    /** Rich lyric entry carried from the panel into the Share Lyrics preview and renderer. */
    record ShareLineEntry(
            String originalText,
            @Nullable String romanizationText,
            @Nullable String translationText,
            @Nullable String bgBeforeText,
            @Nullable String bgAfterText,
            boolean isRightAligned,
            boolean isRtl
    ) {
        static ShareLineEntry plain(String text) {
            return new ShareLineEntry(text, null, null, null, null, false, ScriptDetector.isLineRtl(text));
        }

        boolean hasBgVocals() {
            return (bgBeforeText != null && !bgBeforeText.trim().isEmpty())
                    || (bgAfterText != null && !bgAfterText.trim().isEmpty());
        }

        boolean hasRomanization() {
            return romanizationText != null && !romanizationText.trim().isEmpty();
        }

        boolean hasTranslation() {
            return translationText != null && !translationText.trim().isEmpty();
        }
    }

    /** Full customization options for rendering a Share Lyrics PNG card. */
    record ShareRenderOptions(
            BackgroundMode backgroundMode,
            CardPalette palette,
            @Nullable Bitmap customBackgroundBitmap,
            boolean showBranding,
            boolean includeBgVocals,
            boolean includeRomaji,
            boolean includeTranslation,
            boolean swapTransRoma,
            boolean forceCenter,
            boolean showWeekday,
            boolean showDate,
            boolean showTimeOfDay,
            boolean guessMode,
            CardMode cardMode,
            @Nullable String keywordLine,
            @Nullable StoryAiResult story,
            @Nullable List<WeightedWord> wordCloud,
            @Nullable Integer textColor,
            boolean timeClockFormat,
            int storyVariant,
            boolean showStoryHook,
            boolean showSourceLine
    ) {
    }

    static boolean isLightColor(int argb) {
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        double luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
        return luminance > 0.56;
    }

    /** Recolours only the sampled ink; the white title/lyrics colour stays as the design defines. */
    static CardPalette withTextColor(CardPalette src, @Nullable Integer argb) {
        if (argb == null) {
            return src;
        }
        int opaque = 0xFF000000 | argb;
        int secondary = 0xD0000000 | (opaque & 0x00FFFFFF);
        return new CardPalette(src.backgroundColor(), src.gradientStartColor(), src.gradientEndColor(),
                opaque, src.textColor(), secondary);
    }

    static CardSwatch forCustomSolidColor(int argb) {
        int opaque = 0xFF000000 | (argb & 0x00FFFFFF);
        boolean light = isLightColor(opaque);
        int text = light ? 0xFF141416 : 0xFFFFFFFF;
        int secondary = light ? 0xA8141416 : 0xC4FFFFFF;
        int accent = light ? 0xFF28282C : 0xFFE8E8EE;
        CardPalette p = new CardPalette(opaque, opaque, opaque, accent, text, secondary);
        return new CardSwatch(p, BackgroundMode.SOLID_COLOR, opaque);
    }

    /** Builds flat solid color presets + album dominant solid color for the Color swatch picker. */
    static List<CardSwatch> buildPaletteSwatches(@Nullable CardPalette albumPalette) {
        CardPalette base = albumPalette != null ? albumPalette : CardPalette.DEFAULT;
        List<CardSwatch> swatches = new ArrayList<>();
        swatches.add(new CardSwatch(base, BackgroundMode.BLUR_MESH, base.backgroundColor()));
        swatches.add(forCustomSolidColor(base.backgroundColor()));
        for (int preset : SOLID_PRESET_COLORS) {
            swatches.add(forCustomSolidColor(preset));
        }
        return swatches;
    }

    /** Resolves duet alignment: only split left/right when both sides appear in the selection. */
    static boolean[] resolveEntryRightAlignments(
            List<ShareLineEntry> entries, boolean forceCenter) {
        boolean[] out = new boolean[entries.size()];
        if (forceCenter || entries.isEmpty()) {
            return out;
        }
        boolean hasLeft = false;
        boolean hasRight = false;
        for (ShareLineEntry e : entries) {
            if (e.isRightAligned()) {
                hasRight = true;
            } else {
                hasLeft = true;
            }
        }
        if (!(hasLeft && hasRight)) {
            return out;
        }
        for (int i = 0; i < entries.size(); i++) {
            out[i] = entries.get(i).isRightAligned();
        }
        return out;
    }

    /** Computes logical layout coordinates for a compact {@code 340dp}-wide share card. */
    static CardLayout computeLayout(int selectedLineCount) {
        return computeLayout(selectedLineCount, 0, true);
    }

    static CardLayout computeLayout(int selectedLineCount, int extraLayerCount, boolean showBranding) {
        return computeLayout(selectedLineCount, extraLayerCount, showBranding, false, false);
    }

    static CardLayout computeLayout(
            int selectedLineCount, int extraLayerCount,
            boolean showBranding, boolean showWeekday, boolean showDate) {
        return computeLayout(selectedLineCount, extraLayerCount, showBranding, showWeekday, showDate, false);
    }

    static CardLayout computeLayout(
            int selectedLineCount, int extraLayerCount,
            boolean showBranding, boolean showWeekday, boolean showDate, boolean showKeywords) {
        return computeLayout(selectedLineCount, extraLayerCount,
                showBranding, showWeekday, showDate, false, showKeywords ? 1 : 0, false);
    }

    static CardLayout computeLayout(
            int selectedLineCount, int extraLayerCount,
            boolean showBranding, boolean showWeekday, boolean showDate,
            boolean showKeywords, boolean showWordCloud) {
        return computeLayout(selectedLineCount, extraLayerCount,
                showBranding, showWeekday, showDate, false, showKeywords ? 1 : 0, showWordCloud);
    }

    static CardLayout computeLayout(
            int selectedLineCount, int extraLayerCount,
            boolean showBranding, boolean showWeekday, boolean showDate, boolean showTimeOfDay,
            boolean showKeywords, boolean showWordCloud) {
        return computeLayout(selectedLineCount, extraLayerCount,
                showBranding, showWeekday, showDate, showTimeOfDay,
                showKeywords ? 1 : 0, showWordCloud);
    }

    static CardLayout computeLayout(
            int selectedLineCount, int extraLayerCount,
            boolean showBranding, boolean showWeekday, boolean showDate, boolean showTimeOfDay,
            int keywordLines, boolean showWordCloud) {
        return computeLayout(selectedLineCount, extraLayerCount, showBranding, showWeekday,
                showDate, showTimeOfDay, keywordLines, showWordCloud, 0);
    }

    static CardLayout computeLayout(
            int selectedLineCount, int extraLayerCount,
            boolean showBranding, boolean showWeekday, boolean showDate, boolean showTimeOfDay,
            int keywordLines, boolean showWordCloud, int sourceLines) {
        int safeSourceLines = Math.max(0, sourceLines);
        boolean hasFooter = showBranding || showWeekday || showDate || showTimeOfDay;
        boolean hasDateLine = showWeekday || showDate || showTimeOfDay;
        float baseExtra = selectedLineCount >= 5 ? 54f : (selectedLineCount == 4 ? 26f : 0f);
        float layerExtra = Math.min(84f, Math.max(0, extraLayerCount) * 24f);
        float keywordExtra = Math.max(0, keywordLines) * KEYWORD_BAND_HEIGHT;
        float dateExtra = hasDateLine ? DATE_BAND_HEIGHT : 0f;
        // Source sits between the keyword band and the date/logo footer, never inside the footer.
        float sourceExtra = safeSourceLines * SOURCE_LINE_HEIGHT;
        // Keep the same breathing room from the footer that the date row enjoys.
        float bandGap = (keywordLines > 0 || safeSourceLines > 0) ? BAND_FOOTER_GAP : 0f;
        float brandSave = (hasFooter || keywordLines > 0 || safeSourceLines > 0 || showWordCloud)
                ? 0f : 22f;
        float cardH = 310f + baseExtra + layerExtra + keywordExtra + sourceExtra + bandGap
                + dateExtra - brandSave;
        float padding = 24f;
        float artworkSize = 48f;
        float lyricsWidth = LOGICAL_CARD_WIDTH - padding * 2f;
        float lyricsTop = padding + artworkSize + 14f;
        float footerHeight = hasFooter
                ? ((showBranding ? 24f : 0f) + (hasDateLine ? DATE_BAND_HEIGHT : 0f))
                : 0f;
        float footerTop = hasFooter ? (cardH - padding - footerHeight) : (cardH - padding);
        float bottomGap = (hasFooter || keywordLines > 0 || safeSourceLines > 0 || showWordCloud)
                ? 12f : 0f;
        float lyricsHeight = footerTop - lyricsTop - bottomGap - keywordExtra - sourceExtra
                - bandGap;
        return new CardLayout(
                LOGICAL_CARD_WIDTH, cardH, padding, artworkSize,
                lyricsWidth, lyricsTop, lyricsHeight, footerTop);
    }

    /**
     * Binary-searches a balanced bold font size so selected lyric lines stay legible and
     * proportional without dwarfing the header or overflowing the card.
     */
    static TextFit fitLyricsImageText(
            float maxHeightPx,
            float textScale,
            DoubleUnaryOperator measureHeightPx) {
        return fitLyricsImageText(maxHeightPx, textScale, 13f, measureHeightPx);
    }

    static TextFit fitLyricsImageText(
            float maxHeightPx,
            float textScale,
            float minLogicalSp,
            DoubleUnaryOperator measureHeightPx) {
        if (maxHeightPx <= 0f) {
            return new TextFit(minLogicalSp, 1f);
        }
        float scale = Math.max(0.5f, Math.min(2f, textScale));
        float minSize = minLogicalSp * scale;
        float maxSize = 21.5f * scale;
        float low = minSize;
        float high = maxSize;
        for (int iter = 0; iter < 16; iter++) {
            float mid = (low + high) * 0.5f;
            if (measureHeightPx.applyAsDouble(mid) <= maxHeightPx) {
                low = mid;
            } else {
                high = mid;
            }
        }
        if (low > minSize + 0.01f) {
            return new TextFit(low, 1f);
        }
        float minHeight = (float) measureHeightPx.applyAsDouble(minSize);
        float drawScale = minHeight <= maxHeightPx ? 1f : Math.min(1f, maxHeightPx / minHeight);
        return new TextFit(minSize, drawScale);
    }

    /** Extracts the live palette from an artwork bitmap for the auto swatch. */
    static CardPalette extractPaletteFromBitmap(@Nullable Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()
                || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return CardPalette.DEFAULT;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        return extractPaletteFromPixels(pixels);
    }

    /**
     * Extracts a rich dark-card color palette from sampled ARGB artwork pixels.
     */
    static CardPalette extractPaletteFromPixels(@Nullable int[] pixels) {
        if (pixels == null || pixels.length == 0) {
            return CardPalette.DEFAULT;
        }
        final int hueBuckets = 12;
        int[] counts = new int[hueBuckets];
        long[] sumR = new long[hueBuckets];
        long[] sumG = new long[hueBuckets];
        long[] sumB = new long[hueBuckets];
        double[] satScore = new double[hueBuckets];

        long allR = 0, allG = 0, allB = 0;
        int validCount = 0;
        float[] hsv = new float[3];

        for (int pixel : pixels) {
            int a = (pixel >>> 24) & 0xFF;
            if (a < 128) continue;
            int r = (pixel >>> 16) & 0xFF;
            int g = (pixel >>> 8) & 0xFF;
            int b = pixel & 0xFF;
            allR += r;
            allG += g;
            allB += b;
            validCount++;

            Color.RGBToHSV(r, g, b, hsv);
            if (hsv[1] < 0.10f || hsv[2] < 0.08f || hsv[2] > 0.97f) {
                continue;
            }
            int bucket = Math.min(hueBuckets - 1, Math.max(0, (int) (hsv[0] / (360f / hueBuckets))));
            counts[bucket]++;
            sumR[bucket] += r;
            sumG[bucket] += g;
            sumB[bucket] += b;
            satScore[bucket] += hsv[1] * (0.4 + 0.6 * hsv[2]);
        }

        if (validCount == 0) {
            return CardPalette.DEFAULT;
        }

        int bestPrimary = -1;
        double bestPrimaryWeight = -1;
        for (int i = 0; i < hueBuckets; i++) {
            if (counts[i] == 0) continue;
            double weight = counts[i] * 0.65 + satScore[i] * 0.35;
            if (weight > bestPrimaryWeight) {
                bestPrimaryWeight = weight;
                bestPrimary = i;
            }
        }

        int bestSecondary = -1;
        double bestSecWeight = -1;
        for (int i = 0; i < hueBuckets; i++) {
            if (counts[i] == 0 || i == bestPrimary) continue;
            int dist = Math.abs(i - bestPrimary);
            dist = Math.min(dist, hueBuckets - dist);
            double diversity = dist >= 2 ? 1.25 : 0.75;
            double weight = satScore[i] * diversity;
            if (weight > bestSecWeight) {
                bestSecWeight = weight;
                bestSecondary = i;
            }
        }

        int domR, domG, domB;
        if (bestPrimary >= 0 && counts[bestPrimary] > 0) {
            domR = (int) (sumR[bestPrimary] / counts[bestPrimary]);
            domG = (int) (sumG[bestPrimary] / counts[bestPrimary]);
            domB = (int) (sumB[bestPrimary] / counts[bestPrimary]);
        } else {
            domR = (int) (allR / validCount);
            domG = (int) (allG / validCount);
            domB = (int) (allB / validCount);
        }

        int secR = domR, secG = domG, secB = domB;
        if (bestSecondary >= 0 && counts[bestSecondary] > 0) {
            secR = (int) (sumR[bestSecondary] / counts[bestSecondary]);
            secG = (int) (sumG[bestSecondary] / counts[bestSecondary]);
            secB = (int) (sumB[bestSecondary] / counts[bestSecondary]);
        }

        // Deep rich card base & gradient stops ensuring crisp WCAG contrast for white lyrics text
        Color.RGBToHSV(domR, domG, domB, hsv);
        float primarySat = Math.min(0.78f, hsv[1] * 1.12f);
        int bgColor = Color.HSVToColor(new float[]{hsv[0], primarySat, Math.max(0.16f, Math.min(0.28f, hsv[2] * 0.42f))});
        int gradStart = Color.HSVToColor(new float[]{hsv[0], Math.min(0.82f, primarySat * 1.08f), Math.max(0.24f, Math.min(0.42f, hsv[2] * 0.62f))});

        Color.RGBToHSV(secR, secG, secB, hsv);
        int gradEnd = Color.HSVToColor(new float[]{hsv[0], Math.min(0.75f, hsv[1] * 1.05f), Math.max(0.12f, Math.min(0.24f, hsv[2] * 0.35f))});
        int accent = Color.HSVToColor(new float[]{hsv[0], Math.min(0.42f, hsv[1] * 0.65f), 0.95f});

        int secondaryText = (accent & 0x00FFFFFF) | 0xCC000000;
        return new CardPalette(bgColor, gradStart, gradEnd, accent, 0xFFFFFFFF, secondaryText);
    }

    /** Writes an already rendered card bitmap to {@code cacheDir/lyrics_share/} and clears stale PNGs. */
    static File saveCardPng(Context context, Bitmap bitmap) throws IOException {
        File dir = new File(context.getCacheDir(), SHARE_CACHE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create share cache directory: " + dir);
        }
        File[] oldFiles = dir.listFiles();
        if (oldFiles != null) {
            for (File old : oldFiles) {
                Utils.deleteQuietly(old);
            }
        }
        File outFile = new File(dir, SHARE_FILE_PREFIX + System.currentTimeMillis() + ".png");
        try (FileOutputStream out = new FileOutputStream(outFile)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IOException("Bitmap PNG compression returned false");
            }
            out.flush();
        }
        return outFile;
    }

    static int clampCardWidthPx(int cardWidthPx) {
        return Math.max(120, cardWidthPx);
    }

    static Bitmap renderRichCardBitmap(
            List<ShareLineEntry> entries,
            @Nullable TrackInfo trackInfo,
            @Nullable String fallbackTitle,
            @Nullable String fallbackArtist,
            DynamicBackgroundView.ShareSnapshot snapshot,
            ShareRenderOptions options,
            int cardWidthPx) {
        int safeWidthPx = clampCardWidthPx(cardWidthPx);
        int extraLayers = 0;
        for (ShareLineEntry e : entries) {
            if (options.includeBgVocals() && e.hasBgVocals()) extraLayers++;
            if (options.includeRomaji() && e.hasRomanization()) extraLayers++;
            if (options.includeTranslation() && e.hasTranslation()) extraLayers++;
        }
        float scale = safeWidthPx / LOGICAL_CARD_WIDTH;
        boolean hasKeywordLine = options.keywordLine() != null && !options.keywordLine().isEmpty();
        TextPaint keywordPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        LyricsViewSupport.applyLyricsBoldPaint(keywordPaint);
        keywordPaint.setTextSize(11.5f * scale);
        float keywordWidth = (LOGICAL_CARD_WIDTH - 48f) * scale;
        int keywordLines = hasKeywordLine
                ? keywordLineCount(options.keywordLine(), keywordPaint, keywordWidth) : 0;
        // Guess mode only hides identity; the story elements stay under the user's chip control.
        StoryAiResult story = options.cardMode() == CardMode.STORY ? options.story() : null;
        boolean wordCloudMode = options.cardMode() == CardMode.WORD_CLOUD;
        List<WeightedWord> weightedWords = wordCloudMode ? options.wordCloud() : null;
        String sourceLine = story == null || !options.showSourceLine() ? ""
                : buildSourceLine(str("morphe_music_lyrics_share_source_line"),
                        str("morphe_music_lyrics_share_source_ai_line"), story.sourceLabel());
        TextPaint sourcePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        sourcePaint.setTypeface(LyricsFontManager.regularOrMainTypeface());
        sourcePaint.setTextSize(11.5f);
        float logicalWidth = LOGICAL_CARD_WIDTH - 48f;
        int sourceLines = sourceLine.isEmpty() ? 0
                : sourceLineCount(sourceLine, sourcePaint, logicalWidth, logicalWidth);
        CardLayout layout = computeLayout(entries.size(), extraLayers,
                options.showBranding(), options.showWeekday(), options.showDate(),
                options.showTimeOfDay(), keywordLines, wordCloudMode || story != null, sourceLines);
        int cardHeightPx = Math.round(layout.cardHeight() * scale);

        Bitmap out = Bitmap.createBitmap(safeWidthPx, cardHeightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        CardPalette palette = options.palette();
        Bitmap artwork = snapshot.artwork();
        Bitmap blurredMesh = snapshot.blurredMesh();
        BackgroundMode mode = options.backgroundMode();
        boolean lightText = !isLightColor(palette.backgroundColor()) || mode != BackgroundMode.SOLID_COLOR;

        // One pixelated copy is reused for background and thumbnail so guess mode never leaks the cover.
        Bitmap pixelatedArtwork = null;
        boolean guessMode = options.guessMode();
        if (guessMode && artwork != null && !artwork.isRecycled()) {
            pixelatedArtwork = pixelateArtwork(artwork);
        }

        RectF fullRect = new RectF(0f, 0f, safeWidthPx, cardHeightPx);
        float outerCornerPx = 20f * scale;

        canvas.drawColor(darkenColor(palette.backgroundColor(), 0.55f));

        Path cardClip = new Path();
        cardClip.addRoundRect(fullRect, outerCornerPx, outerCornerPx, Path.Direction.CW);
        int rootSave = canvas.save();
        canvas.clipPath(cardClip);

        // 1. Background rendering strictly honoring the selected BackgroundMode
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (mode == BackgroundMode.SOLID_COLOR) {
            // Pure flat solid color with zero gradient or radial glow.
            canvas.drawColor(palette.backgroundColor());
        } else if (mode == BackgroundMode.CUSTOM_IMAGE
                && options.customBackgroundBitmap() != null
                && !options.customBackgroundBitmap().isRecycled()) {
            canvas.drawColor(0xFF141418);
            drawCenterCrop(canvas, options.customBackgroundBitmap(), fullRect, bgPaint);
            drawReadabilityScrim(canvas, fullRect, cardHeightPx,
                    COVER_SCRIM_COLORS[0], COVER_SCRIM_COLORS[1], COVER_SCRIM_COLORS[2]);
            if (options.textColor() == null) {
                palette = forceWhiteTextPalette(palette);
            }
        } else if (mode == BackgroundMode.ALBUM_COVER && artwork != null && !artwork.isRecycled()) {
            canvas.drawColor(0xFF141418);
            // In guess mode the full-bleed background must be pixelated too, otherwise it reveals
            // the album cover even though the header thumbnail is censored.
            Bitmap coverForBackground = (pixelatedArtwork != null) ? pixelatedArtwork : artwork;
            drawCenterCrop(canvas, coverForBackground, fullRect, bgPaint);
            drawReadabilityScrim(canvas, fullRect, cardHeightPx,
                    COVER_SCRIM_COLORS[0], COVER_SCRIM_COLORS[1], COVER_SCRIM_COLORS[2]);
            if (options.textColor() == null) {
                palette = forceWhiteTextPalette(palette);
            }
        } else {
            // BLUR_MESH (default): gradient base + DynamicBackgroundView blurred artwork mesh + scrim
            bgPaint.setShader(new LinearGradient(
                    0f, 0f, safeWidthPx * 0.85f, cardHeightPx,
                    new int[]{palette.gradientStartColor(), palette.backgroundColor(), palette.gradientEndColor()},
                    new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(fullRect, bgPaint);
            bgPaint.setShader(null);

            if (blurredMesh != null && !blurredMesh.isRecycled()) {
                bgPaint.setAlpha(225);
                drawCenterCrop(canvas, blurredMesh, fullRect, bgPaint);
                bgPaint.setAlpha(255);
                drawReadabilityScrim(canvas, fullRect, cardHeightPx, 0x4D000000, 0x38000000, 0x70000000);
            }
        }
        palette = withTextColor(palette, options.textColor());

        // Subtle inner highlight border
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1.5f, scale));
        borderPaint.setColor(lightText ? 0x20FFFFFF : 0x16000000);
        RectF insetRect = new RectF(fullRect);
        insetRect.inset(scale * 0.5f, scale * 0.5f);
        canvas.drawRoundRect(insetRect, outerCornerPx, outerCornerPx, borderPaint);

        float padPx = layout.padding() * scale;
        float artSidePx = layout.artworkSize() * scale;
        boolean hasArtwork = artwork != null && !artwork.isRecycled();
        Bitmap displayArtwork = (pixelatedArtwork != null) ? pixelatedArtwork : artwork;
        if (hasArtwork) {
            RectF artRect = new RectF(padPx, padPx, padPx + artSidePx, padPx + artSidePx);
            float artCornerPx = 10f * scale;

            Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadowPaint.setColor(0x48000000);
            RectF shadowRect = new RectF(artRect);
            shadowRect.offset(0f, 3f * scale);
            canvas.drawRoundRect(shadowRect, artCornerPx, artCornerPx, shadowPaint);

            int artSave = canvas.save();
            Path artClip = new Path();
            artClip.addRoundRect(artRect, artCornerPx, artCornerPx, Path.Direction.CW);
            canvas.clipPath(artClip);
            drawCenterCrop(canvas, displayArtwork, artRect, bgPaint);
            canvas.restoreToCount(artSave);

            borderPaint.setColor(0x28FFFFFF);
            canvas.drawRoundRect(artRect, artCornerPx, artCornerPx, borderPaint);
        }

        String title;
        String artist;
        if (options.guessMode()) {
            title = str("morphe_music_lyrics_share_guess_prompt");
            artist = "";
        } else {
            String defaultTrackLabel = Utils.getContext() != null
                    ? str("morphe_music_lyrics_share_unknown_track")
                    : "Unknown Track";
            title = resolveNonEmpty(
                    trackInfo != null ? trackInfo.title() : null,
                    fallbackTitle,
                    defaultTrackLabel);
            artist = resolveNonEmpty(
                    trackInfo != null ? trackInfo.artist() : null,
                    fallbackArtist,
                    "");
        }
        String album = trackInfo != null && trackInfo.album() != null
                ? trackInfo.album().trim()
                : "";

        float headerX = (layout.padding() + (hasArtwork ? (layout.artworkSize() + 12f) : 0f)) * scale;
        float headerMaxW = Math.max(40f, (layout.cardWidth() - layout.padding()) * scale - headerX);

        TextPaint headerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        LyricsViewSupport.applyLyricsBoldPaint(headerPaint);
        headerPaint.setColor(palette.textColor());
        headerPaint.setTextSize(15.5f * scale);
        drawSingleLineLabel(canvas, title, headerPaint, headerX, 28f * scale, headerMaxW);

        if (!artist.isEmpty()) {
            TextPaint artistPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            artistPaint.setTypeface(LyricsFontManager.regularOrMainTypeface());
            artistPaint.setColor(palette.secondaryTextColor());
            artistPaint.setTextSize(12.5f * scale);
            drawSingleLineLabel(canvas, artist, artistPaint, headerX, 49f * scale, headerMaxW);
        }

        // 4. Selected lyric entries (supports BG vocals, Romaji, Translation, and Duet L/R)
        float lyricsTopPx = layout.lyricsTop() * scale;
        float lyricsWidthPx = layout.lyricsWidth() * scale;
        float lyricsHeightPx = layout.lyricsHeight() * scale;
        int layoutWidthPx = Math.max(1, Math.round(lyricsWidthPx));

        if (wordCloudMode) {
            // Lay out at final render scale so preview and 1440px export match and the cloud fills
            // the real lyrics rect (no fixed 0.82/0.7 box, no clipping of placed words).
            List<WordCloudBuilder.PlacedWord> placed =
                    WordCloudBuilder.layoutCloud(weightedWords, padPx, lyricsTopPx,
                            lyricsWidthPx, lyricsHeightPx, scale);
            drawWordCloud(canvas, placed, padPx, lyricsTopPx, lyricsWidthPx,
                    lyricsHeightPx, palette, scale);
        } else if (story != null) {
            // Story mode is a finished card of its own: only the final AI-written narrative is
            // drawn, in the same rect the word cloud uses, with no lyric lines underneath.
            drawStoryBand(canvas, story, options.storyVariant(), options.showStoryHook(),
                    padPx, lyricsTopPx, lyricsWidthPx, lyricsHeightPx, palette, scale);
        } else {
        boolean[] entryRightAligned = resolveEntryRightAlignments(entries, options.forceCenter());
        boolean hasMultiLayer = extraLayers > 0;
        float minSp = hasMultiLayer ? 11.2f : 13f;

        TextPaint lyricsPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        LyricsViewSupport.applyLyricsBoldPaint(lyricsPaint);
        lyricsPaint.setColor(palette.textColor());

        final CardPalette finalPalette = palette;
        TextFit fit = fitLyricsImageText(lyricsHeightPx, 1.0f, minSp, candidateSp ->
                measureRichEntriesHeight(
                        entries, entryRightAligned, options, finalPalette,
                        lyricsPaint, (float) candidateSp, scale, layoutWidthPx, hasMultiLayer));

        List<StaticLayout> entryLayouts = buildRichEntryLayouts(
                entries, entryRightAligned, options, palette,
                lyricsPaint, fit.fontSize(), scale, layoutWidthPx);
        float gapPx = entryGapPx(fit.fontSize(), scale, hasMultiLayer);
        float rawTotalH = totalLayoutHeight(entryLayouts, gapPx);
        float scaledTotalH = rawTotalH * fit.drawScale();
        float startY = lyricsTopPx + Math.max(0f, (lyricsHeightPx - scaledTotalH) * 0.5f);

        int lyricsSave = canvas.save();
        canvas.clipRect(padPx, lyricsTopPx, padPx + lyricsWidthPx, lyricsTopPx + lyricsHeightPx);
        float cursorY = startY;
        for (int i = 0; i < entryLayouts.size(); i++) {
            StaticLayout sl = entryLayouts.get(i);
            Layout.Alignment align = sl.getAlignment();
            float entryX = padPx;
            if (align == Layout.Alignment.ALIGN_CENTER) {
                entryX += lyricsWidthPx * (1f - fit.drawScale()) * 0.5f;
            } else if (align == Layout.Alignment.ALIGN_OPPOSITE) {
                entryX += lyricsWidthPx * (1f - fit.drawScale());
            }
            int itemSave = canvas.save();
            canvas.translate(entryX, cursorY);
            if (fit.drawScale() < 0.999f) {
                canvas.scale(fit.drawScale(), fit.drawScale());
            }
            sl.draw(canvas);
            canvas.restoreToCount(itemSave);
            cursorY += (sl.getHeight() + (i < entryLayouts.size() - 1 ? gapPx : 0f)) * fit.drawScale();
        }
        canvas.restoreToCount(lyricsSave);
        }

        float sourceBandPx = sourceLines * SOURCE_LINE_HEIGHT * scale;
        float keywordBandPx = keywordLines * KEYWORD_BAND_HEIGHT * scale;
        float bandBottomPx = layout.footerTop() * scale
                - ((keywordLines > 0 || sourceLines > 0) ? BAND_FOOTER_GAP * scale : 0f);
        if (keywordLines > 0) {
            keywordPaint.setColor(palette.secondaryTextColor());
            float keywordTopPx = bandBottomPx
                    - sourceBandPx - keywordBandPx + 2f * scale;
            StaticLayout keywordLayout = buildKeywordLayout(
                    options.keywordLine(), keywordPaint, Math.round(lyricsWidthPx));
            float textY = keywordTopPx + Math.max(0f, (keywordBandPx - keywordLayout.getHeight()) * 0.5f);
            int keywordSave = canvas.save();
            canvas.translate(padPx, textY);
            keywordLayout.draw(canvas);
            canvas.restoreToCount(keywordSave);
        }

        if (!sourceLine.isEmpty()) {
            TextPaint sourceDrawPaint =
                    new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            sourceDrawPaint.setTypeface(LyricsFontManager.regularOrMainTypeface());
            sourceDrawPaint.setColor(palette.secondaryTextColor());
            sourceDrawPaint.setTextSize(11.5f * scale);
            StaticLayout[] sourceLayouts = buildSourceLayouts(sourceLine, sourceDrawPaint,
                    lyricsWidthPx, lyricsWidthPx, 1f * scale);
            float sourceTopPx = bandBottomPx - sourceBandPx + 2f * scale;
            for (StaticLayout sourceLayout : sourceLayouts) {
                int sourceSave = canvas.save();
                canvas.translate(padPx, sourceTopPx);
                sourceLayout.draw(canvas);
                canvas.restoreToCount(sourceSave);
                sourceTopPx += sourceLayout.getHeight() + 1f * scale;
            }
        }

        boolean footerVisible = options.showBranding()
                || options.showWeekday() || options.showDate() || options.showTimeOfDay();
        if (footerVisible) {
            float footerTopPx = layout.footerTop() * scale;
            float footerRightX = padPx + lyricsWidthPx;
            boolean hasDateLine = options.showWeekday() || options.showDate() || options.showTimeOfDay();
            float dateBandPx = hasDateLine ? DATE_BAND_HEIGHT * scale : 0f;
            float brandTopPx = footerTopPx + dateBandPx;

            String dateSuffix = buildFooterDateSuffix(LocalDate.now(), LocalTime.now(),
                    options.showWeekday(), options.showDate(), options.showTimeOfDay(),
                    options.timeClockFormat());
            if (!dateSuffix.isEmpty()) {
                float dateBudget = footerRightX - padPx;
                if (dateBudget > 4f) {
                    TextPaint datePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
                    datePaint.setTypeface(LyricsFontManager.regularOrMainTypeface());
                    datePaint.setColor(palette.secondaryTextColor());
                    float baseDateSize = 10.5f * scale;
                    float minDateSize = Math.max(6f, baseDateSize * 0.55f);
                    float size = baseDateSize;
                    datePaint.setTextSize(size);
                    while (size > minDateSize && datePaint.measureText(dateSuffix) > dateBudget) {
                        size = Math.max(minDateSize, size * 0.88f);
                        datePaint.setTextSize(size);
                    }
                    drawSingleLineLabel(canvas, dateSuffix, datePaint, padPx,
                            footerTopPx + 2f * scale, dateBudget);
                }
            }

            if (options.showBranding()) {
                float badgeRadius = 9.5f * scale;
                float badgeCenterX = padPx + badgeRadius;
                float badgeCenterY = brandTopPx + 12f * scale;

                Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                badgePaint.setColor(palette.secondaryTextColor());
                canvas.drawCircle(badgeCenterX, badgeCenterY, badgeRadius, badgePaint);

                int cutoutColor = (mode == BackgroundMode.ALBUM_COVER || mode == BackgroundMode.CUSTOM_IMAGE)
                        ? 0xFF1A1A1E
                        : palette.backgroundColor();
                Drawable brandIcon = ResourceUtils.getDrawable("morphe_lyrics_brand");
                if (brandIcon != null) {
                    brandIcon = brandIcon.mutate();
                    brandIcon.setTint(cutoutColor);
                    int iconSize = Math.round(badgeRadius * 1.5f);
                    brandIcon.setBounds((int) badgeCenterX - iconSize / 2,
                            (int) badgeCenterY - iconSize / 2,
                            (int) badgeCenterX + iconSize / 2,
                            (int) badgeCenterY + iconSize / 2);
                    brandIcon.draw(canvas);
                }

                TextPaint footerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
                LyricsViewSupport.applyLyricsBoldPaint(footerPaint);
                footerPaint.setColor(palette.secondaryTextColor());
                footerPaint.setTextSize(11.5f * scale);
                float footerLabelX = badgeCenterX + badgeRadius + 8f * scale;
                String brandLabel = str("morphe_custom_branding_name_entry_2");
                float brandBudget = Math.max(40f, footerRightX - footerLabelX);
                drawSingleLineLabel(canvas, brandLabel, footerPaint, footerLabelX,
                        brandTopPx + 4.5f * scale, brandBudget);
            }

        }

        canvas.restoreToCount(rootSave);
        if (pixelatedArtwork != null) {
            pixelatedArtwork.recycle();
        }
        return out;
    }

    private static float entryGapPx(float fontSizeLogicalSp, float scale, boolean hasMultiLayer) {
        if (!hasMultiLayer) {
            return Math.max(2f, fontSizeLogicalSp * scale * 0.16f);
        }
        return Math.max(4f, fontSizeLogicalSp * scale * 0.38f);
    }

    private static float measureRichEntriesHeight(
            List<ShareLineEntry> entries,
            boolean[] entryRightAligned,
            ShareRenderOptions options,
            CardPalette palette,
            TextPaint basePaint,
            float fontSizeLogicalSp,
            float scale,
            int widthPx,
            boolean hasMultiLayer) {
        List<StaticLayout> layouts = buildRichEntryLayouts(
                entries, entryRightAligned, options, palette,
                basePaint, fontSizeLogicalSp, scale, widthPx);
        return totalLayoutHeight(layouts, entryGapPx(fontSizeLogicalSp, scale, hasMultiLayer));
    }

    private static float totalLayoutHeight(List<StaticLayout> layouts, float gapPx) {
        float h = 0f;
        for (int i = 0; i < layouts.size(); i++) {
            h += layouts.get(i).getHeight();
            if (i < layouts.size() - 1) {
                h += gapPx;
            }
        }
        return h;
    }

    private static List<StaticLayout> buildRichEntryLayouts(
            List<ShareLineEntry> entries,
            boolean[] entryRightAligned,
            ShareRenderOptions options,
            CardPalette palette,
            TextPaint basePaint,
            float fontSizeLogicalSp,
            float scale,
            int widthPx) {
        List<StaticLayout> layouts = new ArrayList<>(entries.size());
        TextPaint paint = new TextPaint(basePaint);
        int textSizePx = Math.max(10, Math.round(fontSizeLogicalSp * scale));
        paint.setTextSize(textSizePx);
        String sampleOrig = entries.isEmpty() ? "" : entries.get(0).originalText();
        paint.setLetterSpacing(LyricsViewSupport.lineLetterSpacing(sampleOrig, fontSizeLogicalSp));

        int transColor = withAlpha(palette.secondaryTextColor(), 0xD8);
        for (int i = 0; i < entries.size(); i++) {
            ShareLineEntry e = entries.get(i);
            SpannableStringBuilder sb = new SpannableStringBuilder();

            if (options.includeBgVocals() && e.bgBeforeText() != null && !e.bgBeforeText().trim().isEmpty()) {
                appendStyledSubLine(sb, e.bgBeforeText().trim(), 0.80f, palette.secondaryTextColor(), false);
            }
            if (!e.originalText().isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(e.originalText());
            }
            if (options.includeBgVocals() && e.bgAfterText() != null && !e.bgAfterText().trim().isEmpty()) {
                appendStyledSubLine(sb, e.bgAfterText().trim(), 0.80f, palette.secondaryTextColor(), false);
            }

            String roma = (options.includeRomaji() && e.hasRomanization()) ? e.romanizationText().trim() : null;
            String trans = (options.includeTranslation() && e.hasTranslation()) ? e.translationText().trim() : null;
            if (options.swapTransRoma()) {
                if (trans != null) appendStyledSubLine(sb, trans, 0.75f, transColor, false);
                if (roma != null)
                    appendStyledSubLine(sb, roma, 0.73f, palette.secondaryTextColor(), false);
            } else {
                if (roma != null)
                    appendStyledSubLine(sb, roma, 0.73f, palette.secondaryTextColor(), false);
                if (trans != null) appendStyledSubLine(sb, trans, 0.75f, transColor, false);
            }

            if (sb.length() == 0) {
                sb.append(' ');
            }

            Layout.Alignment align;
            if (options.forceCenter()) {
                align = Layout.Alignment.ALIGN_CENTER;
            } else {
                boolean right = i < entryRightAligned.length && entryRightAligned[i];
                if (right) {
                    align = e.isRtl() ? Layout.Alignment.ALIGN_NORMAL : Layout.Alignment.ALIGN_OPPOSITE;
                } else {
                    align = e.isRtl() ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
                }
            }

            StaticLayout sl = StaticLayout.Builder.obtain(sb, 0, sb.length(), paint, widthPx)
                    .setAlignment(align)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1.16f)
                    .build();
            layouts.add(sl);
        }
        return layouts;
    }

    private static void appendStyledSubLine(
            SpannableStringBuilder sb, String text, float relSize, int color, boolean bold) {
        if (sb.length() > 0) {
            sb.append('\n');
        }
        int start = sb.length();
        sb.append(text);
        int end = sb.length();
        sb.setSpan(new RelativeSizeSpan(relSize), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!bold) {
            sb.setSpan(new StyleSpan(Typeface.NORMAL), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    static void drawReadabilityScrim(
            Canvas canvas, RectF rect, int heightPx, int topColor, int midColor, int bottomColor) {
        Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        scrimPaint.setShader(new LinearGradient(
                0f, 0f, 0f, heightPx,
                new int[]{topColor, midColor, bottomColor},
                new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(rect, scrimPaint);
    }

    /** Legacy overload for tests/callers that do not render a time-of-day element. */
    static String buildFooterDateSuffix(LocalDate date, boolean showWeekday, boolean showDate) {
        return buildFooterDateSuffix(date, null, showWeekday, showDate, false);
    }

    /** English weekday/date/time text; full weekday when date is off. */
    static String buildFooterDateSuffix(LocalDate date, @Nullable LocalTime time,
                                        boolean showWeekday, boolean showDate,
                                        boolean showTimeOfDay) {
        return buildFooterDateSuffix(date, time, showWeekday, showDate, showTimeOfDay, false);
    }

    /** English weekday/date/time text; the clock flag switches time to an a.m./p.m. label. */
    static String buildFooterDateSuffix(LocalDate date, @Nullable LocalTime time,
                                        boolean showWeekday, boolean showDate,
                                        boolean showTimeOfDay, boolean clockFormat) {
        String weekday = "";
        if (showWeekday) {
            weekday = showDate ? WEEKDAY_FORMAT.format(date) : WEEKDAY_FULL_FORMAT.format(date);
        }
        String datePart = showDate ? DATE_FORMAT.format(date) : "";
        String timePart = (showTimeOfDay && time != null)
                ? buildFooterTimeOfDaySuffix(time, clockFormat) : "";
        StringBuilder out = new StringBuilder();
        if (!weekday.isEmpty()) {
            out.append(weekday);
            if (!datePart.isEmpty()) out.append(", ");
        }
        if (!datePart.isEmpty()) {
            out.append(datePart);
        }
        if (!timePart.isEmpty()) {
            if (out.length() > 0) out.append(" · ");
            out.append(timePart);
        }
        return out.toString();
    }

    /** English literary label for the current hour. */
    static String buildFooterTimeOfDaySuffix(LocalTime time) {
        return buildFooterTimeOfDaySuffix(time, false);
    }

    static String buildFooterTimeOfDaySuffix(LocalTime time, boolean clockFormat) {
        if (clockFormat) {
            int hour = time.getHour() % 12;
            return (hour == 0 ? 12 : hour) + (time.getHour() < 12 ? " a.m." : " p.m.");
        }
        int hour = time.getHour();
        if (hour < 2) return "Midnight";
        if (hour < 4) return "The Wee Hours";
        if (hour < 6) return "First Light";
        if (hour < 8) return "Early Morning";
        if (hour < 11) return "Morning";
        if (hour < 13) return "High Noon";
        if (hour < 15) return "Afternoon";
        if (hour < 17) return "Late Afternoon";
        if (hour < 18) return "Twilight";
        if (hour < 19) return "Evening";
        if (hour < 21) return "Nightfall";
        return "The Dead of Night";
    }

    private static CardPalette forceWhiteTextPalette(CardPalette src) {
        return new CardPalette(
                src.backgroundColor(), src.gradientStartColor(), src.gradientEndColor(),
                0xFFE8E8F0, 0xFFFFFFFF, 0xD0FFFFFF);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static void drawWordCloud(Canvas canvas,
                                      @Nullable List<WordCloudBuilder.PlacedWord> placed,
                                      float x, float y, float width, float height,
                                      CardPalette palette, float scale) {
        int save = canvas.save();
        canvas.clipRect(x, y, x + width, y + height);
        if (placed == null || placed.isEmpty()) {
            TextPaint emptyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            emptyPaint.setTypeface(LyricsFontManager.regularOrMainTypeface());
            emptyPaint.setColor(palette.secondaryTextColor());
            emptyPaint.setTextSize(11.5f * scale);
            String label = Utils.getContext() != null
                    ? str("morphe_music_lyrics_share_word_cloud_empty")
                    : "No words to cloud";
            float w = emptyPaint.measureText(label);
            drawSingleLineLabel(canvas, label, emptyPaint,
                    x + Math.max(0f, (width - w) / 2f), y + height / 2f, width);
        } else {
            for (WordCloudBuilder.PlacedWord word : placed) {
                TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
                paint.setTypeface(LyricsFontManager.regularOrMainTypeface());
                paint.setTextSize(word.sizePx());
                paint.setColor((word.sizePx() >= 18f * scale)
                        ? palette.accentColor() : palette.secondaryTextColor());
                canvas.drawText(word.word(), word.leftPx(), word.topPx(), paint);
            }
        }
        canvas.restoreToCount(save);
    }

    /** Selects one of the three long story paragraphs; the selector always has content to draw. */
    static String storyParagraph(StoryAiResult story, int variant) {
        if (story == null) {
            return "";
        }
        String text = variant == 1 ? story.background()
                : variant == 2 ? story.keyMetaphor() : story.lyricStory();
        if (text == null || text.trim().isEmpty()) {
            text = story.lyricStory();
        }
        if (text == null || text.trim().isEmpty()) {
            text = story.oneLineHook();
        }
        return text == null ? "" : text.trim();
    }

    private static void drawStoryBand(Canvas canvas, StoryAiResult story, int variant,
                                      boolean showHook, float x, float topY, float width,
                                      float heightPx, CardPalette palette, float scale) {
        String finalText = storyParagraph(story, variant);
        if (finalText.isEmpty()) {
            return;
        }
        String hook = !showHook || story.oneLineHook() == null
                ? "" : story.oneLineHook().trim();
        float quoteArea = 0f;
        if (!hook.isEmpty()) {
            TextPaint markPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            markPaint.setTypeface(LyricsFontManager.regularOrMainTypeface());
            markPaint.setColor(palette.secondaryTextColor());
            String open = "“";
            String close = "”";

            TextPaint hookPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            hookPaint.setTypeface(LyricsFontManager.regularOrMainTypeface());
            hookPaint.setColor(palette.textColor());
            float blockWidth = width * 0.72f;
            float hookSize = 22f * scale;
            float minHookSize = 10f * scale;
            StaticLayout hookLayout;
            while (true) {
                hookPaint.setTextSize(hookSize);
                markPaint.setTextSize(hookSize * 1.6f);
                float hookWidth = Math.max(4f,
                        blockWidth - markPaint.measureText(open) - markPaint.measureText(close)
                                - 8f * scale);
                hookLayout = StaticLayout.Builder.obtain(
                                hook, 0, hook.length(), hookPaint,
                                Math.max(1, (int) Math.ceil(hookWidth)))
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(false)
                        .setLineSpacing(1f * scale, 1f)
                        .build();
                float widest = 0f;
                for (int i = 0; i < hookLayout.getLineCount(); i++) {
                    widest = Math.max(widest, hookLayout.getLineRight(i));
                }
                if ((hookLayout.getLineCount() <= 2 && widest >= hookWidth * 0.85f)
                        || hookSize <= minHookSize) {
                    break;
                }
                hookSize = Math.max(minHookSize, hookSize * 0.9f);
            }
            float openWidth = markPaint.measureText(open);
            float closeWidth = markPaint.measureText(close);
            float needed = Math.max(hookLayout.getHeight(), hookSize * 1.6f) + 10f * scale;
            if (needed < heightPx - 30f * scale) {
                quoteArea = needed;
                float textX = x + openWidth + 4f * scale;
                float textY = topY + 4f * scale;
                canvas.drawText(open, x, textY + hookSize * 1.6f, markPaint);
                int hookSave = canvas.save();
                canvas.translate(textX, textY);
                hookLayout.draw(canvas);
                canvas.restoreToCount(hookSave);
                int last = hookLayout.getLineCount() - 1;
                float closeX = Math.min(x + width - closeWidth,
                        textX + hookLayout.getLineRight(last) + 2f * scale);
                float closeY = textY + hookLayout.getLineBottom(last) + hookSize * 0.35f;
                canvas.drawText(close, closeX, closeY, markPaint);
            }
        }
        float paragraphTop = topY + quoteArea;
        float paragraphHeight = heightPx - quoteArea;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(LyricsFontManager.regularOrMainTypeface());
        paint.setColor(palette.textColor());
        float size = 13.5f * scale;
        float minSize = 7f * scale;
        StaticLayout layout;
        while (true) {
            paint.setTextSize(size);
            layout = StaticLayout.Builder.obtain(finalText, 0, finalText.length(), paint,
                    Math.max(1, (int) Math.ceil(width)))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setLineSpacing(2f * scale, 1f)
                    .build();
            if (layout.getHeight() <= paragraphHeight || size <= minSize) {
                break;
            }
            size = Math.max(minSize, size * 0.9f);
        }
        float y = paragraphTop + Math.max(0f, (paragraphHeight - layout.getHeight()) * 0.5f);
        int save = canvas.save();
        canvas.translate(x, y);
        layout.draw(canvas);
        canvas.restoreToCount(save);
    }

    private static StaticLayout buildKeywordLayout(String text, TextPaint paint, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build();
    }

    static int keywordLineCount(String text, TextPaint paint, float maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 1f) {
            return 0;
        }
        return Math.max(1, buildKeywordLayout(text, paint, (int) Math.ceil(maxWidth)).getLineCount());
    }

    static String buildSourceLine(String template, String aiTemplate, String sourceLabel) {
        if (sourceLabel == null || sourceLabel.trim().isEmpty()) {
            return "";
        }
        String label = sourceLabel.trim();
        if ("AI".equalsIgnoreCase(label) || "Lyrics imagery".equalsIgnoreCase(label)) {
            return aiTemplate == null || aiTemplate.isEmpty()
                    ? "Generated by AI" : aiTemplate;
        }
        String pattern = template == null || template.isEmpty()
                ? "Generated from %1$s" : template;
        return pattern.replace("%1$s", label);
    }

    static int sourceLineCount(String text, TextPaint paint,
                               float firstWidth, float fullWidth) {
        StaticLayout[] layouts = buildSourceLayouts(text, paint, firstWidth, fullWidth, 1f);
        int count = 0;
        for (StaticLayout layout : layouts) {
            count += layout.getLineCount();
        }
        return count;
    }

    static StaticLayout[] buildSourceLayouts(String text, TextPaint paint,
                                             float firstWidth, float fullWidth, float spacing) {
        StaticLayout first = buildSourceLayout(text, paint, firstWidth, spacing);
        if (first.getLineCount() <= 1) {
            return new StaticLayout[]{first};
        }
        String rest = text.substring(first.getLineEnd(0)).trim();
        if (rest.isEmpty()) {
            return new StaticLayout[]{first};
        }
        return new StaticLayout[]{first, buildSourceLayout(rest, paint, fullWidth, spacing)};
    }

    private static StaticLayout buildSourceLayout(String text, TextPaint paint,
                                                  float width, float spacing) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint,
                Math.max(1, (int) Math.ceil(width)))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(spacing, 1f)
                .build();
    }

    private static void drawSingleLineLabel(
            Canvas canvas, String text, TextPaint paint, float x, float topY, float maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 1f) {
            return;
        }
        CharSequence ellipsized = TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END);
        StaticLayout sl = StaticLayout.Builder.obtain(
                        ellipsized, 0, ellipsized.length(), paint, Math.max(1, (int) Math.ceil(maxWidth)))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(1)
                .build();
        int save = canvas.save();
        canvas.translate(x, topY);
        sl.draw(canvas);
        canvas.restoreToCount(save);
    }

    /**
     * Keeps a soft colour clue in guess mode without revealing the cover. Every bitmap is freshly
     * allocated, never aliased from the source, so recycling cannot corrupt the shared snapshot.
     */
    @Nullable
    static Bitmap pixelateArtwork(@Nullable Bitmap source) {
        if (source == null || source.isRecycled()) {
            return null;
        }
        int sw = source.getWidth();
        int sh = source.getHeight();
        if (sw <= 0 || sh <= 0) {
            return null;
        }
        final int tiny = 24;
        int side = Math.min(sw, sh);
        int left = (sw - side) / 2;
        int top = (sh - side) / 2;

        Paint noFilter = new Paint(Paint.FILTER_BITMAP_FLAG);
        noFilter.setFilterBitmap(false); // nearest-neighbor for the blocky mosaic look

        Bitmap small = Bitmap.createBitmap(tiny, tiny, Bitmap.Config.ARGB_8888);
        Canvas smallCanvas = new Canvas(small);
        Rect srcCrop = new Rect(left, top, left + side, top + side);
        RectF dstTiny = new RectF(0f, 0f, tiny, tiny);
        smallCanvas.drawBitmap(source, srcCrop, dstTiny, noFilter);

        Bitmap out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas outCanvas = new Canvas(out);
        Rect srcTiny = new Rect(0, 0, tiny, tiny);
        RectF dstSide = new RectF(0f, 0f, side, side);
        outCanvas.drawBitmap(small, srcTiny, dstSide, noFilter);

        small.recycle();
        return out;
    }

    private static void drawCenterCrop(Canvas canvas, Bitmap bmp, RectF dst, Paint paint) {
        int bw = bmp.getWidth();
        int bh = bmp.getHeight();
        if (bw <= 0 || bh <= 0) return;
        float dstAspect = dst.width() / Math.max(1f, dst.height());
        float srcAspect = (float) bw / bh;
        Rect src;
        if (srcAspect > dstAspect) {
            int cropW = Math.max(1, Math.round(bh * dstAspect));
            int left = (bw - cropW) / 2;
            src = new Rect(left, 0, left + cropW, bh);
        } else {
            int cropH = Math.max(1, Math.round(bw / dstAspect));
            int top = (bh - cropH) / 2;
            src = new Rect(0, top, bw, top + cropH);
        }
        canvas.drawBitmap(bmp, src, dst, paint);
    }

    private static int darkenColor(int color, float factor) {
        int r = Math.max(0, Math.min(255, Math.round(((color >>> 16) & 0xFF) * factor)));
        int g = Math.max(0, Math.min(255, Math.round(((color >>> 8) & 0xFF) * factor)));
        int b = Math.max(0, Math.min(255, Math.round((color & 0xFF) * factor)));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static String resolveNonEmpty(@Nullable String first, @Nullable String second, String fallback) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return fallback;
    }
}
