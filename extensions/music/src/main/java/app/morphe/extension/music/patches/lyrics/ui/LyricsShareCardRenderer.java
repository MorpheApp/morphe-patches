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
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.shared.Utils;

/**
 * Renders 1–5 selected lyric lines onto an off-screen PNG card styled with the track's
 * {@link DynamicBackgroundView} blurred artwork mesh or curated solid palette and {@link TrackInfo}
 * metadata.
 */
final class LyricsShareCardRenderer {

    static final int DEFAULT_CARD_SIZE_PX = 1440;
    private static final float LOGICAL_CARD_WIDTH = 340f;
    private static final String SHARE_CACHE_DIR = "lyrics_share";
    private static final String SHARE_FILE_PREFIX = "lyrics_card_";

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
            boolean forceCenter
    ) {
    }

    static boolean isLightColor(int argb) {
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        double luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
        return luminance > 0.56;
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
        swatches.add(forCustomSolidColor(0xFF282038));
        swatches.add(forCustomSolidColor(0xFF3E1C26));
        swatches.add(forCustomSolidColor(0xFF17353B));
        swatches.add(forCustomSolidColor(0xFF1D3326));
        swatches.add(forCustomSolidColor(0xFF161618));
        swatches.add(forCustomSolidColor(0xFFF3F3F5));
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
        float baseExtra = selectedLineCount >= 5 ? 54f : (selectedLineCount == 4 ? 26f : 0f);
        float layerExtra = Math.min(84f, Math.max(0, extraLayerCount) * 24f);
        float brandSave = showBranding ? 0f : 22f;
        float cardH = 310f + baseExtra + layerExtra - brandSave;
        float padding = 24f;
        float artworkSize = 48f;
        float lyricsWidth = LOGICAL_CARD_WIDTH - padding * 2f;
        float lyricsTop = padding + artworkSize + 14f;
        float footerHeight = showBranding ? 24f : 0f;
        float footerTop = showBranding ? (cardH - padding - footerHeight) : (cardH - padding);
        float bottomGap = showBranding ? 12f : 0f;
        float lyricsHeight = footerTop - lyricsTop - bottomGap;
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
        CardLayout layout = computeLayout(entries.size(), extraLayers, options.showBranding());
        float scale = safeWidthPx / layout.cardWidth();
        int cardHeightPx = Math.round(layout.cardHeight() * scale);

        Bitmap out = Bitmap.createBitmap(safeWidthPx, cardHeightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);

        CardPalette palette = options.palette();
        Bitmap artwork = snapshot.artwork();
        Bitmap blurredMesh = snapshot.blurredMesh();
        BackgroundMode mode = options.backgroundMode();
        boolean lightText = !isLightColor(palette.backgroundColor()) || mode != BackgroundMode.SOLID_COLOR;

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
            drawReadabilityScrim(canvas, fullRect, cardHeightPx, 0x8A000000, 0x7A000000, 0xA6000000);
            palette = forceWhiteTextPalette(palette);
        } else if (mode == BackgroundMode.ALBUM_COVER && artwork != null && !artwork.isRecycled()) {
            canvas.drawColor(0xFF141418);
            drawCenterCrop(canvas, artwork, fullRect, bgPaint);
            drawReadabilityScrim(canvas, fullRect, cardHeightPx, 0x8A000000, 0x7A000000, 0xA6000000);
            palette = forceWhiteTextPalette(palette);
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

        // Subtle inner highlight border
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1.5f, 1.0f * scale));
        borderPaint.setColor(lightText ? 0x20FFFFFF : 0x16000000);
        RectF insetRect = new RectF(fullRect);
        insetRect.inset(scale * 0.5f, scale * 0.5f);
        canvas.drawRoundRect(insetRect, outerCornerPx, outerCornerPx, borderPaint);

        // 2. Album artwork cover at (28dp, 28dp) with 10dp rounded corners + soft elevation shadow
        float padPx = layout.padding() * scale;
        float artSidePx = layout.artworkSize() * scale;
        boolean hasArtwork = artwork != null && !artwork.isRecycled();
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
            drawCenterCrop(canvas, artwork, artRect, bgPaint);
            canvas.restoreToCount(artSave);

            borderPaint.setColor(0x28FFFFFF);
            canvas.drawRoundRect(artRect, artCornerPx, artCornerPx, borderPaint);
        }

        // 3. Header metadata from TrackInfo (title + artist)
        String defaultTrackLabel = Utils.getContext() != null
                ? str("morphe_music_lyrics_share_unknown_track")
                : "Unknown Track";
        String title = resolveNonEmpty(
                trackInfo != null ? trackInfo.title() : null,
                fallbackTitle,
                defaultTrackLabel);
        String artist = resolveNonEmpty(
                trackInfo != null ? trackInfo.artist() : null,
                fallbackArtist,
                "");
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

        // 5. Optional footer: YouTube Music badge + brand label
        if (options.showBranding()) {
            float footerTopPx = layout.footerTop() * scale;
            float badgeRadius = 9.5f * scale;
            float badgeCenterX = padPx + badgeRadius;
            float badgeCenterY = footerTopPx + 12f * scale;

            Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            badgePaint.setColor(palette.secondaryTextColor());
            canvas.drawCircle(badgeCenterX, badgeCenterY, badgeRadius, badgePaint);

            int cutoutColor = (mode == BackgroundMode.ALBUM_COVER || mode == BackgroundMode.CUSTOM_IMAGE)
                    ? 0xFF1A1A1E
                    : palette.backgroundColor();
            Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(1.2f * scale);
            ringPaint.setColor(cutoutColor);
            canvas.drawCircle(badgeCenterX, badgeCenterY, badgeRadius * 0.62f, ringPaint);

            Paint playPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            playPaint.setStyle(Paint.Style.FILL);
            playPaint.setColor(cutoutColor);
            Path playTriangle = new Path();
            float triR = badgeRadius * 0.34f;
            playTriangle.moveTo(badgeCenterX - triR * 0.55f, badgeCenterY - triR * 0.85f);
            playTriangle.lineTo(badgeCenterX + triR * 0.95f, badgeCenterY);
            playTriangle.lineTo(badgeCenterX - triR * 0.55f, badgeCenterY + triR * 0.85f);
            playTriangle.close();
            canvas.drawPath(playTriangle, playPaint);

            TextPaint footerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            LyricsViewSupport.applyLyricsBoldPaint(footerPaint);
            footerPaint.setColor(palette.secondaryTextColor());
            footerPaint.setTextSize(11.5f * scale);
            float footerLabelX = badgeCenterX + badgeRadius + 8f * scale;
            float footerMaxW = Math.max(40f, padPx + lyricsWidthPx - footerLabelX);
            drawSingleLineLabel(canvas, "YouTube Music", footerPaint, footerLabelX, footerTopPx + 4.5f * scale, footerMaxW);
        }

        canvas.restoreToCount(rootSave);
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
                if (roma != null) appendStyledSubLine(sb, roma, 0.73f, palette.secondaryTextColor(), false);
            } else {
                if (roma != null) appendStyledSubLine(sb, roma, 0.73f, palette.secondaryTextColor(), false);
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

    private static void drawReadabilityScrim(
            Canvas canvas, RectF rect, int heightPx, int topColor, int midColor, int bottomColor) {
        Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        scrimPaint.setShader(new LinearGradient(
                0f, 0f, 0f, heightPx,
                new int[]{topColor, midColor, bottomColor},
                new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(rect, scrimPaint);
    }
    private static CardPalette forceWhiteTextPalette(CardPalette src) {
        return new CardPalette(
                src.backgroundColor(), src.gradientStartColor(), src.gradientEndColor(),
                0xFFE8E8F0, 0xFFFFFFFF, 0xD0FFFFFF);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
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
