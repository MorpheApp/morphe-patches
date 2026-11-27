/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.util.Pair;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import app.morphe.extension.music.patches.lyrics.derive.OpenAIClient;
import app.morphe.extension.music.patches.lyrics.derive.StoryAiResult;
import app.morphe.extension.music.patches.lyrics.derive.WeightedWord;
import app.morphe.extension.music.patches.lyrics.derive.WordCloudBuilder;
import app.morphe.extension.music.patches.lyrics.derive.WordCloudStopwords;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.patches.lyrics.requests.StoryFactFetcher;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache;
import app.morphe.extension.music.patches.lyrics.ui.LyricsShareCardRenderer.BackgroundMode;
import app.morphe.extension.music.patches.lyrics.ui.LyricsShareCardRenderer.CardMode;
import app.morphe.extension.music.patches.lyrics.ui.LyricsShareCardRenderer.CardPalette;
import app.morphe.extension.music.patches.lyrics.ui.LyricsShareCardRenderer.CardSwatch;
import app.morphe.extension.music.patches.lyrics.ui.LyricsShareCardRenderer.ShareLineEntry;
import app.morphe.extension.music.patches.lyrics.ui.LyricsShareCardRenderer.ShareRenderOptions;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.ColorPickerView;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;

/**
 * Fullscreen Share Lyrics preview &amp; customization studio. Pairs a dark tinted preview stage
 * with a grounded matte control sheet for background modes, optional content layers, remembered
 * footer branding, plain-text copy, and high-resolution PNG sharing.
 */
final class LyricsShareDialog {

    private static final int PREVIEW_WIDTH_PX = 900;
    private static final int SLIDE_ANIMATION_MILLIS = 280;
    private static final int MAX_PICKED_IMAGE_DIM = 1280;
    /** The card shows at most five hashtags; the full weighted list still feeds the word cloud. */
    static final int MAX_CARD_KEYWORDS = 5;
    private static final String PICKER_FRAGMENT_TAG = "MorpheSharePhotoPickerFragment";
    private static final int SEGMENT_TRACK_COLOR = 0xFF222229;
    private static final String ICON_ROMAJI = "morphe_yt_romanize";
    private static final String ICON_TRANSLATE = "yt_outline_experimental_translate_vd_theme_24";
    private static final String ICON_BG = "morphe_share_bg_tag";
    private static final String ICON_WEEKDAY = "morphe_share_weekday_tag";
    private static final String ICON_DATE = "morphe_share_date_tag";
    private static final String ICON_TIME = "morphe_share_time_tag";
    private static final String ICON_GUESS = "morphe_share_guess_tag";
    private static final String ICON_KEYWORD = "morphe_share_keyword_tag";
    private static final String ICON_MOOD = "morphe_share_mood_tag";
    private static final String ICON_STORY_TEXT = "morphe_share_story_tag";
    private static final String ICON_HOOK = "morphe_share_hook_tag";
    private static final String ICON_SOURCE = "morphe_share_source_tag";
    private static final String ICON_BRANDING = "morphe_lyrics_brand";
    private static final String ICON_COPY = "morphe_yt_copy_bold";
    private static final String ICON_SHARE = "yt_outline_experimental_share_vd_theme_24";

    private LyricsShareDialog() {
    }

    /** Computes a guaranteed dark studio stage color regardless of light card swatches (WCAG AAA for header). */
    static int computeStageBackdropColor(int cardBaseColor) {
        int r = Math.max(10, Math.min(34, Math.round(((cardBaseColor >>> 16) & 0xFF) * 0.14f) + 10));
        int g = Math.max(10, Math.min(34, Math.round(((cardBaseColor >>> 8) & 0xFF) * 0.14f) + 10));
        int b = Math.max(13, Math.min(40, Math.round((cardBaseColor & 0xFF) * 0.14f) + 13));
        return Color.argb(255, r, g, b);
    }

    static void show(
            Context context,
            List<ShareLineEntry> entries,
            @Nullable List<String> allLyricLines,
            @Nullable List<String> translatedLyricLines,
            @Nullable TrackInfo track,
            @Nullable String rawTitle,
            @Nullable String rawArtist,
            DynamicBackgroundView.ShareSnapshot snapshot,
            boolean initialRomajiOn,
            boolean initialTranslateOn,
            boolean swapTransRoma,
            boolean forceCenter,
            @Nullable Bitmap initialCustomPhoto,
            @Nullable java.util.function.Consumer<Bitmap> onCustomPhotoChanged,
            Runnable onShared,
            Runnable onFinished) {
        final CardPalette albumPalette = LyricsPaletteCache.albumPalette();
        final List<CardSwatch> solidSwatches = new ArrayList<>();
        for (CardSwatch s : LyricsShareCardRenderer.buildPaletteSwatches(albumPalette)) {
            if (s.backgroundMode() == BackgroundMode.SOLID_COLOR) {
                solidSwatches.add(s);
            }
        }
        if (solidSwatches.isEmpty()) {
            solidSwatches.add(LyricsShareCardRenderer.forCustomSolidColor(albumPalette.backgroundColor()));
        }

        boolean anyBg = false;
        boolean anyRoma = false;
        boolean anyTrans = false;
        for (ShareLineEntry e : entries) {
            if (e.hasBgVocals()) anyBg = true;
            if (e.hasRomanization()) anyRoma = true;
            if (e.hasTranslation()) anyTrans = true;
        }
        final boolean hasBgOption = anyBg;
        final boolean hasRomaOption = anyRoma;
        final boolean hasTransOption = anyTrans;
        final boolean hasArtworkCover = snapshot.artwork() != null && !snapshot.artwork().isRecycled();

        final BackgroundMode[] bgMode = {LyricsBackgroundStyle.savedMode()};
        final int savedSlot = LyricsBackgroundStyle.savedColorSlot();
        final int[] solidIndex = {savedSlot >= 0 && savedSlot < solidSwatches.size() ? savedSlot : 0};
        final int[] customSolidColor = {LyricsBackgroundStyle.savedColor(albumPalette.backgroundColor())};
        final Integer[] customTextColor = {readCardTextColor()};
        final boolean[] usingCustomSolid = {savedSlot == -1};
        final Bitmap[] customPhotoBitmap = {initialCustomPhoto};
        final boolean[] showBranding = {Settings.LYRICS_SHARE_SHOW_BRANDING.get()};
        final boolean[] showWeekday = {Settings.LYRICS_SHARE_SHOW_WEEKDAY.get()};
        final boolean[] showDate = {Settings.LYRICS_SHARE_SHOW_DATE.get()};
        final boolean[] showTimeOfDay = {Settings.LYRICS_SHARE_SHOW_TIME_OF_DAY.get()};
        final boolean[] timeClockFormat = {Settings.LYRICS_SHARE_TIME_CLOCK_FORMAT.get()};
        final boolean[] guessMode = {Settings.LYRICS_SHARE_GUESS_MODE.get()};
        final CardMode[] cardMode = {CardMode.LYRICS};
        final boolean[] keywordEnabled = {false};
        final String[] keywordLine = {null};
        final boolean[] moodEnabled = {false};
        final String[] moodLine = {null};
        final int[] storyVariant = {0};
        final boolean[] showHook = {true};
        final boolean[] showSourceLine = {true};
        final Runnable[] pendingMood = new Runnable[1];
        final Runnable[] refreshOptionChips = new Runnable[1];
        final Runnable[] requestStory = new Runnable[1];
        final StoryAiResult[] story = {null};
        final java.util.concurrent.atomic.AtomicInteger storySeq =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicReference<List<WeightedWord>> wordCloud =
                new java.util.concurrent.atomic.AtomicReference<>(null);
        final java.util.concurrent.atomic.AtomicReference<List<WeightedWord>> keywordWeighted =
                new java.util.concurrent.atomic.AtomicReference<>(null);
        final boolean[] includeBg = {hasBgOption};
        final boolean[] includeRoma = {hasRomaOption && initialRomajiOn};
        final boolean[] includeTrans = {hasTransOption && initialTranslateOn};
        final Bitmap[] currentPreview = {null};
        final boolean[] busy = {false};
        final boolean[] dismissed = {false};
        final boolean[] exportInFlight = {false};

        LinearLayout sheet = SheetBottomDialog.createMainLayout(context, LyricsSheetChrome.BACKGROUND_COLOR);
        Dialog dialog = SheetBottomDialog.createSlideDialog(context, sheet, SLIDE_ANIMATION_MILLIS);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        // Top studio header (>= 48dp accessible back button + high-contrast title)
        LinearLayout topBar = LyricsSheetChrome.createTopBar(context,
                str("morphe_music_lyrics_share_preview_title"), dialog::dismiss);
        root.addView(topBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Center preview stage with explicit loading state overlay
        FrameLayout previewContainer = new FrameLayout(context);
        previewContainer.setPadding(Dim.dp12, Dim.dp4, Dim.dp12, Dim.dp12);

        ImageView previewImage = new ImageView(context);
        previewImage.setAdjustViewBounds(true);
        previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewImage.setContentDescription(str("morphe_music_lyrics_share_preview_title"));
        previewImage.setElevation(Dim.dp(8));
        FrameLayout.LayoutParams imgParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        imgParams.gravity = Gravity.CENTER;
        previewContainer.addView(previewImage, imgParams);

        LinearLayout loadingPill = new LinearLayout(context);
        loadingPill.setOrientation(LinearLayout.HORIZONTAL);
        loadingPill.setGravity(Gravity.CENTER);
        loadingPill.setPadding(Dim.dp16, Dim.dp10, Dim.dp16, Dim.dp10);
        GradientDrawable loadingBg = new GradientDrawable();
        loadingBg.setShape(GradientDrawable.RECTANGLE);
        loadingBg.setCornerRadius(Dim.dp(20));
        loadingBg.setColor(0xE6141418);
        loadingBg.setStroke(Math.max(1, Dim.dp(1)), 0x33FFFFFF);
        loadingPill.setBackground(loadingBg);
        loadingPill.setVisibility(View.GONE);

        ProgressBar progressSpinner = new ProgressBar(context);
        progressSpinner.setIndeterminateTintList(ColorStateList.valueOf(0xFFFFFFFF));
        LinearLayout.LayoutParams spinParams = new LinearLayout.LayoutParams(Dim.dp(18), Dim.dp(18));
        spinParams.rightMargin = Dim.dp10;
        loadingPill.addView(progressSpinner, spinParams);

        TextView loadingLabel = new TextView(context);
        loadingLabel.setTextColor(0xFFFFFFFF);
        loadingLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        loadingLabel.setTypeface(null, Typeface.BOLD);
        loadingPill.addView(loadingLabel);

        FrameLayout.LayoutParams loadParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        loadParams.gravity = Gravity.CENTER;
        previewContainer.addView(loadingPill, loadParams);

        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        int previewHeight = Math.max(Dim.dp(200), Math.min(Dim.dp(360), (int) (screenHeight * 0.45f)));
        root.addView(previewContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, previewHeight));

        // Grounded matte bottom control sheet (solid dark surface with crisp hairline divider)
        LinearLayout bottomDock = new LinearLayout(context);
        bottomDock.setOrientation(LinearLayout.VERTICAL);
        bottomDock.setPadding(Dim.dp16, Dim.dp(14), Dim.dp16, Dim.dp(18));

        final boolean aiEnabled = Settings.LYRICS_SHARE_USE_AI.get();
        LinearLayout cardModeTrack = new LinearLayout(context);
        cardModeTrack.setOrientation(LinearLayout.HORIZONTAL);
        cardModeTrack.setGravity(Gravity.CENTER_VERTICAL);
        cardModeTrack.setPadding(Dim.dp4, Dim.dp4, Dim.dp4, Dim.dp4);
        GradientDrawable cardModeTrackBg = new GradientDrawable();
        cardModeTrackBg.setShape(GradientDrawable.RECTANGLE);
        cardModeTrackBg.setCornerRadius(Dim.dp(26));
        cardModeTrackBg.setColor(SEGMENT_TRACK_COLOR);
        cardModeTrack.setBackground(cardModeTrackBg);
        LinearLayout.LayoutParams cardModeTrackParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardModeTrackParams.bottomMargin = Dim.dp10;
        bottomDock.addView(cardModeTrack, cardModeTrackParams);

        final TextView lyricsModeTab = createSegmentTab(context, str("morphe_music_lyrics_share_mode_lyrics"));
        final TextView wordCloudModeTab = createSegmentTab(context, str("morphe_music_lyrics_share_mode_word_cloud"));
        boolean enoughLyrics = allLyricLines != null && allLyricLines.size() >= 4;
        String songTitle = track != null && !track.title().isEmpty()
                ? track.title() : (rawTitle != null ? rawTitle : "");
        String lowerTitle = songTitle.toLowerCase(java.util.Locale.ROOT);
        boolean instrumental = lowerTitle.contains("instrumental")
                || lowerTitle.contains("纯音乐") || lowerTitle.contains("伴奏")
                || lowerTitle.contains("off vocal");
        final TextView storyModeTab = (aiEnabled && enoughLyrics && !instrumental)
                ? createSegmentTab(context, str("morphe_music_lyrics_share_mode_story"))
                : null;
        cardModeTrack.addView(lyricsModeTab, segmentTabParams());
        cardModeTrack.addView(wordCloudModeTab, segmentTabParams());
        if (storyModeTab != null) {
            cardModeTrack.addView(storyModeTab, segmentTabParams());
        }

        // 1. Unified segmented background-mode control (Blur | Cover | Color | Photo)
        LinearLayout modeSegmentTrack = new LinearLayout(context);
        modeSegmentTrack.setOrientation(LinearLayout.HORIZONTAL);
        modeSegmentTrack.setGravity(Gravity.CENTER_VERTICAL);
        modeSegmentTrack.setPadding(Dim.dp4, Dim.dp4, Dim.dp4, Dim.dp4);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setShape(GradientDrawable.RECTANGLE);
        trackBg.setCornerRadius(Dim.dp(26));
        trackBg.setColor(SEGMENT_TRACK_COLOR);
        modeSegmentTrack.setBackground(trackBg);
        LinearLayout.LayoutParams segTrackParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        segTrackParams.bottomMargin = Dim.dp10;
        bottomDock.addView(modeSegmentTrack, segTrackParams);

        // 2. Flat color swatches strip (48dp touch targets; visible only when Color mode active)
        HorizontalScrollView swatchScroll = new HorizontalScrollView(context);
        swatchScroll.setHorizontalScrollBarEnabled(false);
        swatchScroll.setVisibility(View.GONE);
        LinearLayout swatchRow = new LinearLayout(context);
        swatchRow.setOrientation(LinearLayout.HORIZONTAL);
        swatchRow.setGravity(Gravity.CENTER_VERTICAL);
        swatchRow.setPadding(Dim.dp2, Dim.dp2, Dim.dp2, Dim.dp10);
        swatchScroll.addView(swatchRow);
        bottomDock.addView(swatchScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 3. Structured options strip: content toggles (BG vocals, Romaji, Translate) | YT Music logo
        HorizontalScrollView optionsScroll = new HorizontalScrollView(context);
        optionsScroll.setHorizontalScrollBarEnabled(false);
        optionsScroll.setFillViewport(true);
        LinearLayout optionsRow = new LinearLayout(context);
        optionsRow.setOrientation(LinearLayout.HORIZONTAL);
        optionsRow.setGravity(Gravity.CENTER);
        optionsScroll.addView(optionsRow, new HorizontalScrollView.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams optionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        optionsParams.bottomMargin = Dim.dp(14);
        bottomDock.addView(optionsScroll, optionsParams);

        // 4. Primary & secondary CTA row (rectangular-rounded secondary Copy text + focal pill Share card)
        LinearLayout actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);

        View copyBtn = createActionButton(context, str("morphe_music_lyrics_copy_text_cta"),
                ICON_COPY, false);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                0, Dim.dp(44), 1f);
        copyParams.rightMargin = Dim.dp10;
        actionRow.addView(copyBtn, copyParams);

        View shareBtn = createActionButton(context, str("morphe_music_lyrics_share_card_cta"),
                ICON_SHARE, true);
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(
                0, Dim.dp(44), 1.45f);
        actionRow.addView(shareBtn, shareParams);
        bottomDock.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(bottomDock, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ScrollView sheetScroll = SheetBottomDialog.createCappedScrollView(context, 85);
        sheetScroll.addView(root, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        sheet.addView(sheetScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final java.util.function.Supplier<ShareRenderOptions> buildOptions = () -> {
            CardPalette activePalette;
            if (bgMode[0] == BackgroundMode.SOLID_COLOR) {
                CardSwatch chosen = usingCustomSolid[0]
                        ? LyricsShareCardRenderer.forCustomSolidColor(customSolidColor[0])
                        : solidSwatches.get(solidIndex[0]);
                activePalette = chosen.palette();
            } else {
                activePalette = albumPalette;
            }
            boolean lyricsMode = cardMode[0] == CardMode.LYRICS;
            boolean storyMode = cardMode[0] == CardMode.STORY;
            // Only the lyrics card uses the layer toggles; the mood band replaces keywords on the
            // story card, and neither band belongs on the word cloud.
            String band = cardMode[0] == CardMode.WORD_CLOUD
                    ? null
                    : (storyMode && moodEnabled[0]
                            ? moodLine[0]
                            : (keywordEnabled[0] ? keywordLine[0] : null));
            return new ShareRenderOptions(
                    bgMode[0],
                    activePalette,
                    customPhotoBitmap[0],
                    showBranding[0],
                    lyricsMode && includeBg[0],
                    lyricsMode && includeRoma[0],
                    lyricsMode && includeTrans[0],
                    swapTransRoma,
                    forceCenter,
                    showWeekday[0],
                    showDate[0],
                    showTimeOfDay[0],
                    guessMode[0],
                    cardMode[0],
                    band,
                    story[0],
                    wordCloud.get(),
                    customTextColor[0],
                    timeClockFormat[0],
                    storyVariant[0],
                    showHook[0],
                    showSourceLine[0]);
        };

        final Runnable updateBackdropAndPreview = () -> {
            ShareRenderOptions opts = buildOptions.get();
            int baseColor = opts.backgroundMode() == BackgroundMode.SOLID_COLOR
                    ? opts.palette().backgroundColor()
                    : albumPalette.backgroundColor();
            int stageColor = computeStageBackdropColor(baseColor);
            root.setBackgroundColor(stageColor);

            // A story card without its generated result would fall back to lyric lines and read
            // as a failure. Keep the preview blank until the story is actually available.
            if (cardMode[0] == CardMode.STORY && story[0] == null) {
                Bitmap stale = currentPreview[0];
                currentPreview[0] = null;
                previewImage.setImageBitmap(null);
                if (stale != null && !stale.isRecycled()) {
                    stale.recycle();
                }
                return;
            }

            Bitmap bmp = LyricsShareCardRenderer.renderRichCardBitmap(
                    entries, track, rawTitle, rawArtist, snapshot, opts, PREVIEW_WIDTH_PX);
            Bitmap old = currentPreview[0];
            currentPreview[0] = bmp;
            previewImage.setImageBitmap(bmp);
            if (old != null && !old.isRecycled()) {
                old.recycle();
            }
        };

        final Runnable refreshTextColor = () -> {
            customTextColor[0] = readCardTextColor();
            updateBackdropAndPreview.run();
        };
        final Runnable showTextColorPicker = () -> showTextColorDialog(context,
                Settings.LYRICS_TEXT_COLOR_SLOT.get(), readTextColor(), refreshTextColor);

        // 48dp accessible swatch targets wrapping 32dp color discs
        final List<FrameLayout> swatchTargets = new ArrayList<>();
        final List<View> swatchDiscs = new ArrayList<>();
        final FrameLayout customColorTarget = new FrameLayout(context);
        final View customColorDisc = new View(context);
        final Drawable customPaletteGlyph = new CustomPaletteDrawable();

        Runnable updateSwatchBorders = () -> {
            for (int i = 0; i < swatchDiscs.size(); i++) {
                View disc = swatchDiscs.get(i);
                boolean active = !usingCustomSolid[0] && i == solidIndex[0];
                GradientDrawable gd = new GradientDrawable();
                gd.setShape(GradientDrawable.OVAL);
                gd.setColor(solidSwatches.get(i).swatchColor());
                gd.setStroke(active ? Dim.dp(3) : Dim.dp(1), active ? 0xFFFFFFFF : 0x44FFFFFF);
                disc.setBackground(gd);
                swatchTargets.get(i).setSelected(active);
            }
            if (usingCustomSolid[0]) {
                GradientDrawable activeCust = new GradientDrawable();
                activeCust.setShape(GradientDrawable.OVAL);
                activeCust.setColor(customSolidColor[0]);
                activeCust.setStroke(Dim.dp(3), 0xFFFFFFFF);
                customColorDisc.setBackground(activeCust);
            } else {
                customColorDisc.setBackground(customPaletteGlyph);
            }
            customColorTarget.setSelected(usingCustomSolid[0]);
        };

        int touchPx = Dim.dp(48);
        int discPx = Dim.dp(32);
        for (int i = 0; i < solidSwatches.size(); i++) {
            final int idx = i;
            FrameLayout hitBox = new FrameLayout(context);
            hitBox.setFocusable(true);
            hitBox.setContentDescription(String.format(
                    Locale.getDefault(), str("morphe_music_lyrics_share_color_swatch"), i + 1));
            LyricsSheetChrome.attachInteractiveBackground(hitBox, null, Dim.dp(24), 0x33FFFFFF);

            View disc = new View(context);
            FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(discPx, discPx, Gravity.CENTER);
            hitBox.addView(disc, dp);

            hitBox.setOnClickListener(v -> {
                if (busy[0]) return;
                usingCustomSolid[0] = false;
                solidIndex[0] = idx;
                bgMode[0] = BackgroundMode.SOLID_COLOR;
                LyricsBackgroundStyle.saveColorSlot(idx);
                applyCardBackground(bgMode[0], solidSwatches.get(idx).swatchColor());
                updateSwatchBorders.run();
                updateBackdropAndPreview.run();
            });
            swatchTargets.add(hitBox);
            swatchDiscs.add(disc);
            swatchRow.addView(hitBox, new LinearLayout.LayoutParams(touchPx, touchPx));
        }

        customColorTarget.setFocusable(true);
        customColorTarget.setContentDescription(str("morphe_music_lyrics_share_custom_color"));
        LyricsSheetChrome.attachInteractiveBackground(customColorTarget, null, Dim.dp(24), 0x33FFFFFF);
        customColorTarget.addView(customColorDisc, new FrameLayout.LayoutParams(discPx, discPx, Gravity.CENTER));
        customColorTarget.setOnClickListener(v -> {
            if (busy[0]) return;
            showCustomColorDialog(context, customSolidColor[0], chosenColor -> {
                customSolidColor[0] = chosenColor;
                usingCustomSolid[0] = true;
                bgMode[0] = BackgroundMode.SOLID_COLOR;
                LyricsBackgroundStyle.saveColorSlot(-1);
                applyCardBackground(bgMode[0], chosenColor);
                updateSwatchBorders.run();
                updateBackdropAndPreview.run();
            });
        });
        customColorTarget.setOnLongClickListener(v -> {
            if (busy[0]) return true;
            showTextColorPicker.run();
            return true;
        });
        swatchRow.addView(customColorTarget, new LinearLayout.LayoutParams(touchPx, touchPx));

        // Segmented mode tabs
        TextView blurTab = createSegmentTab(context, str("morphe_music_lyrics_share_bg_blur"));
        TextView coverTab = hasArtworkCover
                ? createSegmentTab(context, str("morphe_music_lyrics_share_bg_cover"))
                : null;
        TextView colorTab = createSegmentTab(context, str("morphe_music_lyrics_share_bg_color"));
        TextView photoTab = createSegmentTab(context, str("morphe_music_lyrics_share_bg_photo"));

        modeSegmentTrack.addView(blurTab, segmentTabParams());
        if (coverTab != null) modeSegmentTrack.addView(coverTab, segmentTabParams());
        modeSegmentTrack.addView(colorTab, segmentTabParams());
        modeSegmentTrack.addView(photoTab, segmentTabParams());

        final LinearLayout bgSwitch = hasBgOption
                ? createOptionChip(context,
                str("morphe_music_lyrics_share_layer_bg")) : null;
        final LinearLayout romaSwitch = hasRomaOption
                ? createOptionChip(context,
                str("morphe_music_lyrics_share_layer_romaji")) : null;
        final LinearLayout transSwitch = hasTransOption
                ? createOptionChip(context,
                str("morphe_music_lyrics_share_layer_translate")) : null;
        final LinearLayout brandSwitch = createOptionChip(context,
                str("morphe_music_lyrics_share_branding_logo"));

        if (bgSwitch != null) {
            styleOptionChip(bgSwitch, includeBg[0], ICON_BG);
            bgSwitch.setOnClickListener(v -> {
                if (busy[0]) return;
                includeBg[0] = !includeBg[0];
                styleOptionChip(bgSwitch, includeBg[0], ICON_BG);
                updateBackdropAndPreview.run();
            });
            optionsRow.addView(bgSwitch, iconOptionLayoutParams(optionsRow.getChildCount() > 0));
        }
        if (romaSwitch != null) {
            styleOptionChip(romaSwitch, includeRoma[0], ICON_ROMAJI);
            romaSwitch.setOnClickListener(v -> {
                if (busy[0]) return;
                includeRoma[0] = !includeRoma[0];
                styleOptionChip(romaSwitch, includeRoma[0], ICON_ROMAJI);
                updateBackdropAndPreview.run();
            });
            optionsRow.addView(romaSwitch, iconOptionLayoutParams(optionsRow.getChildCount() > 0));
        }
        if (transSwitch != null) {
            styleOptionChip(transSwitch, includeTrans[0], ICON_TRANSLATE);
            transSwitch.setOnClickListener(v -> {
                if (busy[0]) return;
                includeTrans[0] = !includeTrans[0];
                styleOptionChip(transSwitch, includeTrans[0], ICON_TRANSLATE);
                updateBackdropAndPreview.run();
            });
            optionsRow.addView(transSwitch, iconOptionLayoutParams(optionsRow.getChildCount() > 0));
        }
        styleOptionChip(brandSwitch, showBranding[0], ICON_BRANDING);
        brandSwitch.setOnClickListener(v -> {
            if (busy[0]) return;
            showBranding[0] = !showBranding[0];
            Settings.LYRICS_SHARE_SHOW_BRANDING.save(showBranding[0]);
            styleOptionChip(brandSwitch, showBranding[0], ICON_BRANDING);
            updateBackdropAndPreview.run();
        });
        optionsRow.addView(brandSwitch, iconOptionLayoutParams(optionsRow.getChildCount() > 0));

        final Consumer<Boolean> setBusyState = isBusy -> {
            busy[0] = isBusy;
            shareBtn.setEnabled(!isBusy);
            copyBtn.setEnabled(!isBusy);
            shareBtn.setAlpha(isBusy ? 0.55f : 1f);
            copyBtn.setAlpha(isBusy ? 0.55f : 1f);
            if (!isBusy) {
                loadingPill.setVisibility(View.GONE);
            }
        };

        final LinearLayout keywordSwitch = aiEnabled
                ? createOptionChip(context, str("morphe_music_lyrics_share_layer_keyword"))
                : null;
        if (keywordSwitch != null) {
            styleOptionChip(keywordSwitch, false, ICON_KEYWORD);
            keywordSwitch.setOnClickListener(v -> {
                if (busy[0]) return;
                if (keywordEnabled[0]) {
                    keywordEnabled[0] = false;
                    refreshOptionChips[0].run();
                    updateBackdropAndPreview.run();
                    return;
                }
                if (cardMode[0] == CardMode.STORY && moodEnabled[0]) {
                    moodEnabled[0] = false;
                }
                if (keywordLine[0] != null) {
                    keywordEnabled[0] = true;
                    refreshOptionChips[0].run();
                    updateBackdropAndPreview.run();
                    return;
                }
                if (allLyricLines == null || allLyricLines.isEmpty()) {
                    Utils.showToastShort(str("morphe_music_lyrics_share_keyword_failed"));
                    return;
                }
                loadingLabel.setText(str("morphe_music_lyrics_share_keyword_loading"));
                loadingPill.setVisibility(View.VISIBLE);
                setBusyState.accept(true);
                Utils.runOnBackgroundThread(() -> {
                    String extracted = null;
                    String shown = null;
                    List<WeightedWord> weighted = null;
                    try {
                        String title = track != null && !track.title().isEmpty()
                                ? track.title() : (rawTitle != null ? rawTitle : "");
                        String artist = track != null && !track.artist().isEmpty()
                                ? track.artist() : (rawArtist != null ? rawArtist : "");
                        String language = LyricsRequests.translationLanguage();
                        String promptTemplate = Settings.LYRICS_AI_KEYWORD_PROMPT.get();
                        // Keywords follow the translation target language; the word cloud stays source-language.
                        List<String> keywordSource = translatedLyricLines != null
                                && !translatedLyricLines.isEmpty()
                                ? translatedLyricLines : allLyricLines;
                        List<String> cappedLines = capKeywordInput(keywordSource);
                        String cached = LyricsCache.getCardAi(
                                track, "keyword", language, promptTemplate, cappedLines);
                        if (cached != null) {
                            weighted = WordCloudBuilder.parseWeightedKeywords(
                                    WordCloudBuilder.normalizeCachedKeywords(cached), 12);
                        }
                        if (weighted == null) {
                            String prompt = OpenAIClient.renderPrompt(
                                    promptTemplate, "keywords", language, title, artist, cappedLines);
                            weighted = OpenAIClient.extractWeightedKeywords(
                                    Settings.LYRICS_AI_BASE_URL.get(),
                                    Settings.LYRICS_AI_API_TOKEN.get(),
                                    Settings.LYRICS_AI_MODEL.get(),
                                    prompt, 12);
                        }
                        if (weighted != null && !weighted.isEmpty()) {
                            // Cache one weighted word per line so the round trip never joins
                            // separate keywords into a single dot-separated string.
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < weighted.size(); i++) {
                                WeightedWord word = weighted.get(i);
                                sb.append(i + 1).append(". ").append(word.word())
                                        .append('|').append(word.weight()).append('\n');
                            }
                            extracted = sb.toString();
                            shown = buildHashtagLine(weighted, MAX_CARD_KEYWORDS);
                            LyricsCache.putCardAi(track, "keyword", language,
                                    promptTemplate, cappedLines, extracted);
                        }
                    } catch (Throwable ex) {
                        Logger.printException(() -> "Failed to extract share-card keywords", ex);
                    }
                    final String result = shown;
                    final List<WeightedWord> resultWeighted = weighted;
                    Utils.runOnMainThread(() -> {
                        if (dismissed[0]) return;
                        setBusyState.accept(false);
                        if (result == null) {
                            Utils.showToastShort(str("morphe_music_lyrics_share_keyword_failed"));
                            return;
                        }
                        keywordLine[0] = result;
                        keywordWeighted.set(resultWeighted);
                        wordCloud.set(null); // re-layout if the word-cloud tab is active
                        keywordEnabled[0] = true;
                        moodEnabled[0] = false;
                        refreshOptionChips[0].run();
                        updateBackdropAndPreview.run();
                    });
                });
            });
            optionsRow.addView(keywordSwitch, iconOptionLayoutParams(optionsRow.getChildCount() > 0));
        }

        final LinearLayout moodSwitch = storyModeTab != null
                ? createOptionChip(context, str("morphe_music_lyrics_share_layer_mood"))
                : null;
        final Runnable[] applyMoodAction = new Runnable[1];
        if (moodSwitch != null) {
            styleOptionChip(moodSwitch, false, ICON_MOOD);
            applyMoodAction[0] = () -> {
                if (story[0] == null) {
                    return;
                }
                String line = buildMoodLine(story[0].moodTags(), MAX_CARD_KEYWORDS);
                if (line.isEmpty()) {
                    Utils.showToastShort(str("morphe_music_lyrics_share_story_failed"));
                    return;
                }
                moodLine[0] = line;
                moodEnabled[0] = true;
                keywordEnabled[0] = false;
                refreshOptionChips[0].run();
                updateBackdropAndPreview.run();
            };
            moodSwitch.setOnClickListener(v -> {
                if (busy[0]) return;
                if (moodEnabled[0]) {
                    moodEnabled[0] = false;
                    refreshOptionChips[0].run();
                    updateBackdropAndPreview.run();
                    return;
                }
                if (story[0] == null) {
                    pendingMood[0] = applyMoodAction[0];
                    requestStory[0].run();
                    return;
                }
                applyMoodAction[0].run();
            });
            optionsRow.addView(moodSwitch, iconOptionLayoutParams(optionsRow.getChildCount() > 0));
        }

        final LinearLayout storyTextSwitch = storyModeTab != null
                ? createOptionChip(context, storyVariantLabel(0)) : null;
        if (storyTextSwitch != null) {
            styleOptionChip(storyTextSwitch, false, ICON_STORY_TEXT);
            storyTextSwitch.setOnClickListener(v -> {
                if (busy[0]) return;
                storyVariant[0] = (storyVariant[0] + 1) % 3;
                ((TextView) storyTextSwitch.getChildAt(1)).setText(
                        storyVariantLabel(storyVariant[0]));
                refreshOptionChips[0].run();
                updateBackdropAndPreview.run();
            });
            optionsRow.addView(storyTextSwitch,
                    iconOptionLayoutParams(optionsRow.getChildCount() > 0));
        }

        final LinearLayout hookSwitch = storyModeTab != null
                ? createOptionChip(context, str("morphe_music_lyrics_share_layer_hook"))
                : null;
        if (hookSwitch != null) {
            styleOptionChip(hookSwitch, showHook[0], ICON_HOOK);
            hookSwitch.setOnClickListener(v -> {
                if (busy[0]) return;
                showHook[0] = !showHook[0];
                refreshOptionChips[0].run();
                updateBackdropAndPreview.run();
            });
            optionsRow.addView(hookSwitch, iconOptionLayoutParams(optionsRow.getChildCount() > 0));
        }

        final LinearLayout sourceSwitch = storyModeTab != null
                ? createOptionChip(context, str("morphe_music_lyrics_share_layer_source"))
                : null;
        if (sourceSwitch != null) {
            styleOptionChip(sourceSwitch, showSourceLine[0], ICON_SOURCE);
            sourceSwitch.setOnClickListener(v -> {
                if (busy[0]) return;
                showSourceLine[0] = !showSourceLine[0];
                refreshOptionChips[0].run();
                updateBackdropAndPreview.run();
            });
            optionsRow.addView(sourceSwitch,
                    iconOptionLayoutParams(optionsRow.getChildCount() > 0));
        }

        final LinearLayout weekdaySwitch = createOptionChip(context,
                str("morphe_music_lyrics_share_layer_weekday"));
        styleOptionChip(weekdaySwitch, showWeekday[0], ICON_WEEKDAY);
        weekdaySwitch.setOnClickListener(v -> {
            if (busy[0]) return;
            showWeekday[0] = !showWeekday[0];
            Settings.LYRICS_SHARE_SHOW_WEEKDAY.save(showWeekday[0]);
            styleOptionChip(weekdaySwitch, showWeekday[0], ICON_WEEKDAY);
            updateBackdropAndPreview.run();
        });
        optionsRow.addView(weekdaySwitch, iconOptionLayoutParams(optionsRow.getChildCount() > 0));

        final LinearLayout dateSwitch = createOptionChip(context,
                str("morphe_music_lyrics_share_layer_date"));
        styleOptionChip(dateSwitch, showDate[0], ICON_DATE);
        dateSwitch.setOnClickListener(v -> {
            if (busy[0]) return;
            showDate[0] = !showDate[0];
            Settings.LYRICS_SHARE_SHOW_DATE.save(showDate[0]);
            styleOptionChip(dateSwitch, showDate[0], ICON_DATE);
            updateBackdropAndPreview.run();
        });
        optionsRow.addView(dateSwitch, iconOptionLayoutParams(optionsRow.getChildCount() > 0));

        final LinearLayout timeSwitch = createOptionChip(context,
                str("morphe_music_lyrics_share_layer_time"));
        styleOptionChip(timeSwitch, showTimeOfDay[0], ICON_TIME);
        if (timeClockFormat[0]) {
            timeSwitch.setContentDescription(str("morphe_music_lyrics_share_layer_time")
                    + " · " + str("morphe_music_lyrics_share_time_clock"));
        }
        timeSwitch.setOnLongClickListener(v -> {
            if (busy[0]) return true;
            timeClockFormat[0] = !timeClockFormat[0];
            Settings.LYRICS_SHARE_TIME_CLOCK_FORMAT.save(timeClockFormat[0]);
            timeSwitch.setContentDescription(str("morphe_music_lyrics_share_layer_time")
                    + (timeClockFormat[0] ? " · " + str("morphe_music_lyrics_share_time_clock") : ""));
            updateBackdropAndPreview.run();
            return true;
        });
        timeSwitch.setOnClickListener(v -> {
            if (busy[0]) return;
            showTimeOfDay[0] = !showTimeOfDay[0];
            Settings.LYRICS_SHARE_SHOW_TIME_OF_DAY.save(showTimeOfDay[0]);
            styleOptionChip(timeSwitch, showTimeOfDay[0], ICON_TIME);
            updateBackdropAndPreview.run();
        });
        optionsRow.addView(timeSwitch, iconOptionLayoutParams(optionsRow.getChildCount() > 0));

        final LinearLayout guessSwitch = createOptionChip(context,
                str("morphe_music_lyrics_share_guess"));
        styleOptionChip(guessSwitch, guessMode[0], ICON_GUESS);
        guessSwitch.setOnClickListener(v -> {
            if (busy[0]) return;
            guessMode[0] = !guessMode[0];
            Settings.LYRICS_SHARE_GUESS_MODE.save(guessMode[0]);
            styleOptionChip(guessSwitch, guessMode[0], ICON_GUESS);
            updateBackdropAndPreview.run();
        });
        optionsRow.addView(guessSwitch, iconOptionLayoutParams(optionsRow.getChildCount() > 0));

        refreshOptionChips[0] = () -> {
            boolean lyricsMode = cardMode[0] == CardMode.LYRICS;
            boolean storyMode = cardMode[0] == CardMode.STORY;
            if (bgSwitch != null) {
                bgSwitch.setVisibility(lyricsMode ? View.VISIBLE : View.GONE);
                styleOptionChip(bgSwitch, includeBg[0], ICON_BG);
            }
            if (romaSwitch != null) {
                romaSwitch.setVisibility(lyricsMode ? View.VISIBLE : View.GONE);
                styleOptionChip(romaSwitch, includeRoma[0], ICON_ROMAJI);
            }
            if (transSwitch != null) {
                transSwitch.setVisibility(lyricsMode ? View.VISIBLE : View.GONE);
                styleOptionChip(transSwitch, includeTrans[0], ICON_TRANSLATE);
            }
            if (keywordSwitch != null) {
                keywordSwitch.setVisibility(cardMode[0] == CardMode.WORD_CLOUD
                        ? View.GONE : View.VISIBLE);
                styleOptionChip(keywordSwitch, keywordEnabled[0], ICON_KEYWORD);
            }
            if (moodSwitch != null) {
                moodSwitch.setVisibility(storyMode ? View.VISIBLE : View.GONE);
                styleOptionChip(moodSwitch, moodEnabled[0], ICON_MOOD);
            }
            if (storyTextSwitch != null) {
                storyTextSwitch.setVisibility(storyMode ? View.VISIBLE : View.GONE);
                styleOptionChip(storyTextSwitch, storyMode, ICON_STORY_TEXT);
            }
            if (hookSwitch != null) {
                hookSwitch.setVisibility(storyMode ? View.VISIBLE : View.GONE);
                styleOptionChip(hookSwitch, showHook[0], ICON_HOOK);
            }
            if (sourceSwitch != null) {
                sourceSwitch.setVisibility(storyMode ? View.VISIBLE : View.GONE);
                styleOptionChip(sourceSwitch, showSourceLine[0], ICON_SOURCE);
            }
        };

        final java.util.concurrent.atomic.AtomicInteger wordCloudSeq =
                new java.util.concurrent.atomic.AtomicInteger(0);
        final Runnable[] requestWordCloud = new Runnable[1];
        requestWordCloud[0] = () -> {
            if (busy[0]) return;
            if (wordCloud.get() != null) {
                updateBackdropAndPreview.run();
                return;
            }
            if (allLyricLines == null || allLyricLines.isEmpty()) {
                updateBackdropAndPreview.run();
                return;
            }
            final int seq = wordCloudSeq.incrementAndGet();
            previewImage.setImageDrawable(null);
            loadingLabel.setText(str("morphe_music_lyrics_share_word_cloud_loading"));
            loadingPill.setVisibility(View.VISIBLE);
            setBusyState.accept(true);
            Utils.runOnBackgroundThread(() -> {
                List<WeightedWord> weightedResult = null;
                try {
                    java.util.Set<String> stopwords = WordCloudStopwords.parse(
                            Settings.LYRICS_WORD_CLOUD_STOPWORDS.get());
                    java.util.Collection<String> banned = null;
                    if (guessMode[0]) {
                        String title = track != null ? track.title() : (rawTitle != null ? rawTitle : "");
                        String artist = track != null ? track.artist() : (rawArtist != null ? rawArtist : "");
                        java.util.Set<String> set = new java.util.HashSet<>();
                        if (title != null && !title.isEmpty()) set.add(title.toLowerCase(java.util.Locale.ROOT));
                        if (artist != null && !artist.isEmpty()) set.add(artist.toLowerCase(java.util.Locale.ROOT));
                        banned = set;
                    }
                    weightedResult = WordCloudBuilder.build(
                            allLyricLines, 18, stopwords, banned);
                } catch (Throwable ex) {
                    Logger.printException(() -> "Failed to build share-card word cloud", ex);
                }
                final List<WeightedWord> result = weightedResult;
                Utils.runOnMainThread(() -> {
                    if (dismissed[0] || seq != wordCloudSeq.get()) return;
                    setBusyState.accept(false);
                    wordCloud.set(result);
                    updateBackdropAndPreview.run();
                });
            });
        };

        // Fetch only when a story card is requested; clear the preview so a stale card is not
        // mistaken for the result.
        requestStory[0] = () -> {
            if (busy[0]) return;
            if (story[0] != null) {
                updateBackdropAndPreview.run();
                return;
            }
            if (allLyricLines == null || allLyricLines.size() < 4) {
                Utils.showToastShort(str("morphe_music_lyrics_share_story_failed"));
                return;
            }
            final int seq = storySeq.incrementAndGet();
            previewImage.setImageDrawable(null);
            loadingLabel.setText(str("morphe_music_lyrics_share_story_loading"));
            loadingPill.setVisibility(View.VISIBLE);
            setBusyState.accept(true);
            Utils.runOnBackgroundThread(() -> {
                StoryAiResult result = null;
                try {
                    String language = LyricsRequests.translationLanguage();
                    String promptTemplate = Settings.LYRICS_AI_STORY_PROMPT.get();
                    List<String> cappedLines = capKeywordInput(allLyricLines);
                    String cached = LyricsCache.getCardAi(
                            track, "story", language, promptTemplate, cappedLines);
                    if (cached != null) {
                        result = StoryAiResult.fromJson(cached);
                    }
                    if (result == null) {
                        String title = track != null && !track.title().isEmpty()
                                ? track.title() : (rawTitle != null ? rawTitle : "");
                        String artist = track != null && !track.artist().isEmpty()
                                ? track.artist() : (rawArtist != null ? rawArtist : "");
                        StoryFactFetcher.Facts facts =
                                StoryFactFetcher.fetch(track == null
                                        ? new TrackInfo(title, artist, null, 0) : track, language);
                        String factsBlock = facts.text().isEmpty()
                                ? "(No external references available)"
                                : facts.text();
                        String template = promptTemplate.replace("{facts}", factsBlock);
                        String prompt = OpenAIClient.renderPrompt(
                                template, "story", language, title, artist, cappedLines);
                        result = OpenAIClient.parseStoryJson(OpenAIClient.complete(
                                Settings.LYRICS_AI_BASE_URL.get(),
                                Settings.LYRICS_AI_API_TOKEN.get(),
                                Settings.LYRICS_AI_MODEL.get(),
                                prompt), 240);
                        if (result != null) {
                            // No external references: the story is AI interpretation only.
                            String label = facts.sourceLabel().isEmpty()
                                    ? "AI" : facts.sourceLabel();
                            result = new StoryAiResult(
                                    facts.verified(), result.oneLineHook(),
                                    result.background(), result.lyricStory(),
                                    result.keyMetaphor(), result.moodTags(), label);
                            LyricsCache.putCardAi(track, "story", language,
                                    promptTemplate, cappedLines, result.toJson());
                        }
                    }
                } catch (Throwable ex) {
                    Logger.printException(() -> "Failed to build share-card story", ex);
                }
                final StoryAiResult loaded = result;
                Utils.runOnMainThread(() -> {
                    if (dismissed[0] || seq != storySeq.get()) return;
                    setBusyState.accept(false);
                    if (loaded == null) {
                        pendingMood[0] = null;
                        Utils.showToastShort(str("morphe_music_lyrics_share_story_failed"));
                        updateBackdropAndPreview.run();
                        return;
                    }
                    story[0] = loaded;
                    Runnable applyPendingMood = pendingMood[0];
                    pendingMood[0] = null;
                    if (applyPendingMood != null) {
                        applyPendingMood.run();
                    } else {
                        updateBackdropAndPreview.run();
                    }
                });
            });
        };

        final Runnable refreshCardModeTabs = () -> {
            styleSegmentTab(lyricsModeTab, cardMode[0] == CardMode.LYRICS);
            styleSegmentTab(wordCloudModeTab, cardMode[0] == CardMode.WORD_CLOUD);
            if (storyModeTab != null) {
                styleSegmentTab(storyModeTab, cardMode[0] == CardMode.STORY);
            }
        };
        lyricsModeTab.setOnClickListener(v -> {
            if (busy[0]) return;
            if (cardMode[0] == CardMode.STORY) moodEnabled[0] = false;
            cardMode[0] = CardMode.LYRICS;
            refreshCardModeTabs.run();
            refreshOptionChips[0].run();
            updateBackdropAndPreview.run();
        });
        wordCloudModeTab.setOnClickListener(v -> {
            if (busy[0]) return;
            if (cardMode[0] == CardMode.STORY) moodEnabled[0] = false;
            cardMode[0] = CardMode.WORD_CLOUD;
            refreshCardModeTabs.run();
            refreshOptionChips[0].run();
            requestWordCloud[0].run();
        });
        if (storyModeTab != null) {
            storyModeTab.setOnClickListener(v -> {
                if (busy[0]) return;
                cardMode[0] = CardMode.STORY;
                refreshCardModeTabs.run();
                refreshOptionChips[0].run();
                requestStory[0].run();
            });
        }

        final Runnable refreshModeTabs = () -> {
            styleSegmentTab(blurTab, bgMode[0] == BackgroundMode.BLUR_MESH);
            if (coverTab != null) {
                styleSegmentTab(coverTab, bgMode[0] == BackgroundMode.ALBUM_COVER);
            }
            styleSegmentTab(colorTab, bgMode[0] == BackgroundMode.SOLID_COLOR);
            styleSegmentTab(photoTab, bgMode[0] == BackgroundMode.CUSTOM_IMAGE);
            swatchScroll.setVisibility(bgMode[0] == BackgroundMode.SOLID_COLOR ? View.VISIBLE : View.GONE);
        };

        blurTab.setOnClickListener(v -> {
            if (busy[0]) return;
            bgMode[0] = BackgroundMode.BLUR_MESH;
            applyCardBackground(bgMode[0], null);
            refreshModeTabs.run();
            updateBackdropAndPreview.run();
        });
        if (coverTab != null) {
            coverTab.setOnClickListener(v -> {
                if (busy[0]) return;
                bgMode[0] = BackgroundMode.ALBUM_COVER;
                applyCardBackground(bgMode[0], null);
                refreshModeTabs.run();
                updateBackdropAndPreview.run();
            });
        }
        colorTab.setOnLongClickListener(v -> {
            if (busy[0]) return true;
            showTextColorPicker.run();
            return true;
        });
        colorTab.setOnClickListener(v -> {
            if (busy[0]) return;
            bgMode[0] = BackgroundMode.SOLID_COLOR;
            applyCardBackground(bgMode[0], usingCustomSolid[0]
                    ? customSolidColor[0]
                    : solidSwatches.get(solidIndex[0]).swatchColor());
            refreshModeTabs.run();
            updateSwatchBorders.run();
            updateBackdropAndPreview.run();
        });
        photoTab.setOnClickListener(v -> {
            if (busy[0] || dismissed[0]) return;
            launchGalleryImagePicker(
                    context,
                    () -> {
                        if (dismissed[0]) return;
                        loadingLabel.setText(str("morphe_music_lyrics_share_loading_photo"));
                        loadingPill.setVisibility(View.VISIBLE);
                        setBusyState.accept(true);
                    },
                    (picked, failed) -> {
                        if (dismissed[0]) {
                            if (picked != null && !picked.isRecycled()) {
                                picked.recycle();
                            }
                            return;
                        }
                        setBusyState.accept(false);
                        if (failed || picked == null) {
                            if (failed) {
                                Utils.showToastShort(str("morphe_music_lyrics_share_photo_failed"));
                            }
                            return;
                        }
                        Bitmap previousCustom = customPhotoBitmap[0];
                        customPhotoBitmap[0] = picked;
                        bgMode[0] = BackgroundMode.CUSTOM_IMAGE;
                        applyCardBackground(bgMode[0], null);
                        if (onCustomPhotoChanged != null) {
                            onCustomPhotoChanged.accept(picked);
                        }
                        refreshModeTabs.run();
                        updateBackdropAndPreview.run();
                        if (previousCustom != null && !previousCustom.isRecycled()) {
                            previousCustom.recycle();
                        }
                    });
        });

        String displayTitle = track != null && !track.title().isEmpty()
                ? track.title()
                : (rawTitle != null ? rawTitle : "");
        String displayArtist = track != null && !track.artist().isEmpty()
                ? track.artist()
                : (rawArtist != null ? rawArtist : "");
        final String videoId = VideoInformation.getVideoId();

        copyBtn.setOnClickListener(v -> {
            if (busy[0]) return;
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                String plainText = buildCardCopyText(buildOptions.get(), entries,
                        displayTitle, displayArtist,
                        str("morphe_music_lyrics_share_source_line"),
                        str("morphe_music_lyrics_share_source_ai_line"),
                        str("morphe_music_lyrics_share_word_cloud_empty"));
                cm.setPrimaryClip(ClipData.newPlainText("lyrics", plainText));
                Utils.showToastShort(str("morphe_music_lyrics_copied"));
            }
            dialog.dismiss();
            onShared.run();
        });

        final Runnable recycleSnapshotBitmaps = () -> {
            Bitmap blurredMesh = snapshot.blurredMesh();
            if (blurredMesh != null && !blurredMesh.isRecycled()) {
                blurredMesh.recycle();
            }
            if (snapshot.ownsArtwork()) {
                Bitmap ownedArt = snapshot.artwork();
                if (ownedArt != null && !ownedArt.isRecycled()) {
                    ownedArt.recycle();
                }
            }
        };

        final Runnable recycleDialogBitmaps = () -> {
            Bitmap leftover = currentPreview[0];
            currentPreview[0] = null;
            previewImage.setImageDrawable(null);
            if (leftover != null && !leftover.isRecycled()) {
                leftover.recycle();
            }
            Bitmap customLeftover = customPhotoBitmap[0];
            customPhotoBitmap[0] = null;
            if (customLeftover != null && !customLeftover.isRecycled()) {
                customLeftover.recycle();
            }
        };

        shareBtn.setOnClickListener(v -> {
            if (busy[0]) return;
            loadingLabel.setText(str("morphe_music_lyrics_share_preparing"));
            loadingPill.setVisibility(View.VISIBLE);
            setBusyState.accept(true);
            exportInFlight[0] = true;

            ShareRenderOptions finalOpts = buildOptions.get();
            final boolean guess = guessMode[0];
            final String shareMenuText = buildShareCaption(displayTitle, displayArtist, videoId,
                    guess, str("morphe_music_lyrics_share_guess_caption"));
            Context appContext = context.getApplicationContext();
            Utils.runOnBackgroundThread(() -> {
                try {
                    Bitmap exportBmp = LyricsShareCardRenderer.renderRichCardBitmap(
                            entries, track, rawTitle, rawArtist, snapshot, finalOpts,
                            LyricsShareCardRenderer.DEFAULT_CARD_SIZE_PX);
                    File pngFile;
                    try {
                        pngFile = LyricsShareCardRenderer.saveCardPng(appContext, exportBmp);
                    } finally {
                        exportBmp.recycle();
                    }
                    Uri contentUri = LyricsShareImageProvider.uriForFile(appContext, pngFile);
                    Utils.runOnMainThread(() -> {
                        exportInFlight[0] = false;
                        if (dismissed[0]) {
                            Utils.deleteQuietly(pngFile);
                            recycleDialogBitmaps.run();
                            recycleSnapshotBitmaps.run();
                            return;
                        }
                        setBusyState.accept(false);
                        dialog.dismiss();
                        onShared.run();
                        try {
                            Intent sendIntent = new Intent(Intent.ACTION_SEND);
                            sendIntent.setType("image/png");
                            sendIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                            if (!shareMenuText.isEmpty()) {
                                sendIntent.putExtra(Intent.EXTRA_TEXT, shareMenuText);
                            }
                            sendIntent.setClipData(
                                    ClipData.newRawUri("lyrics_card", contentUri));
                            sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                            Intent chooser = Intent.createChooser(
                                    sendIntent, str("morphe_music_lyrics_share_button"));
                            if (!(context instanceof Activity)) {
                                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            }
                            context.startActivity(chooser);
                        } catch (Exception ex) {
                            Logger.printException(() -> "Failed to open share chooser for lyrics card", ex);
                            Utils.showToastShort(str("morphe_music_lyrics_share_failed"));
                        }
                    });
                } catch (Throwable ex) {
                    Logger.printException(() -> "Failed to export share card PNG", ex);
                    Utils.runOnMainThread(() -> {
                        exportInFlight[0] = false;
                        if (dismissed[0]) {
                            recycleDialogBitmaps.run();
                            recycleSnapshotBitmaps.run();
                            return;
                        }
                        setBusyState.accept(false);
                        Utils.showToastShort(str("morphe_music_lyrics_share_failed"));
                    });
                }
            });
        });

        refreshCardModeTabs.run();
        refreshOptionChips[0].run();
        refreshModeTabs.run();
        updateSwatchBorders.run();
        updateBackdropAndPreview.run();

        dialog.setOnDismissListener(d -> {
            dismissed[0] = true;
            if (!exportInFlight[0]) {
                recycleDialogBitmaps.run();
                recycleSnapshotBitmaps.run();
            }
            onFinished.run();
        });

        try {
            dialog.show();
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Failed to show share preview sheet", ex);
            dismissed[0] = true;
            if (!exportInFlight[0]) {
                recycleDialogBitmaps.run();
                recycleSnapshotBitmaps.run();
            }
            onFinished.run();
        }
    }

    /** Guess mode emits only the neutral prompt so the caption cannot leak the answer. */
    static String buildShareCaption(
            String title, String artist, String videoId, boolean guessMode, String neutralCaption) {
        if (guessMode) {
            return neutralCaption != null ? neutralCaption : "";
        }
        String caption = (title == null || title.isEmpty())
                ? ""
                : ((artist == null || artist.isEmpty()) ? title : title + " - " + artist);
        if (videoId == null || videoId.isEmpty()) {
            return caption;
        }
        return (caption.isEmpty() ? "" : caption + '\n')
                + "https://music.youtube.com/watch?v=" + videoId;
    }

    /** Persists the chosen card background so fullscreen picks it up, and refreshes both. */
    private static void applyCardBackground(BackgroundMode mode, @Nullable Integer colorArgb) {
        LyricsBackgroundStyle.saveMode(mode);
        if (colorArgb != null) {
            LyricsBackgroundStyle.saveColor(colorArgb);
        }
        LyricsBackgroundStyle.notifyStyleChanged();
    }

    /** Builds the card's hashtag band from the first {@code limit} weighted words. */
    /** Keeps a multi-word tag unbreakable; only the spaces between tags are wrap points. */
    private static String unbreakable(String text) {
        return text.trim().replaceAll("\\s+", "\u00A0");
    }

    static String buildHashtagLine(@Nullable List<WeightedWord> weighted, int limit) {
        if (weighted == null || weighted.isEmpty() || limit <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(limit, weighted.size()); i++) {
            String word = weighted.get(i).word();
            if (word == null || word.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append('#').append(unbreakable(word));
        }
        return sb.toString();
    }

    static String storyVariantLabel(int variant) {
        if (variant == 1) {
            return str("morphe_music_lyrics_share_layer_background");
        }
        if (variant == 2) {
            return str("morphe_music_lyrics_share_layer_imagery");
        }
        return str("morphe_music_lyrics_share_layer_story_text");
    }

    /** Builds the story card's mood band from the first {@code limit} AI tags. */
    static String buildMoodLine(@Nullable List<String> tags, int limit) {
        if (tags == null || tags.isEmpty() || limit <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String tag : tags) {
            if (count >= limit) {
                break;
            }
            if (tag == null || tag.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                // The dot stays with the previous tag; the plain space after it is the wrap point.
                sb.append("\u00A0· ");
            }
            sb.append(unbreakable(tag));
            count++;
        }
        return sb.toString();
    }

    /** Copies the text actually visible on the card, minus the footer and guess-mode identity. */
    static String buildCardCopyText(
            LyricsShareCardRenderer.ShareRenderOptions opts,
            List<ShareLineEntry> entries,
            String title,
            String artist,
            String sourceTemplate,
            String sourceAiTemplate,
            String cloudEmpty) {
        List<String> parts = new ArrayList<>();
        if (!opts.guessMode()) {
            if (title != null && !title.isEmpty()) parts.add(title);
            if (artist != null && !artist.isEmpty()) parts.add(artist);
        }
        switch (opts.cardMode()) {
            case LYRICS -> {
                String body = buildCopyTextForSelection(entries,
                        opts.includeBgVocals(), opts.includeRomaji(), opts.includeTranslation(),
                        opts.swapTransRoma(), null);
                if (!body.isEmpty()) parts.add(body);
            }
            case WORD_CLOUD -> {
                List<WeightedWord> cloud = opts.wordCloud();
                if (cloud == null || cloud.isEmpty()) {
                    parts.add(cloudEmpty);
                } else {
                    List<WeightedWord> sorted = new ArrayList<>(cloud);
                    sorted.sort(Comparator.comparingInt(WeightedWord::weight).reversed());
                    StringBuilder words = new StringBuilder();
                    for (int i = 0; i < sorted.size(); i++) {
                        if (i > 0) words.append(", ");
                        words.append(sorted.get(i).word());
                    }
                    parts.add(words.toString());
                }
            }
            case STORY -> {
                StoryAiResult story = opts.story();
                if (story != null) {
                    String hook = null;
                    if (opts.showStoryHook() && story.oneLineHook() != null) {
                        String raw = story.oneLineHook().trim();
                        if (!raw.isEmpty()) {
                            hook = raw;
                            parts.add("“" + raw + "”");
                        }
                    }
                    String paragraph = LyricsShareCardRenderer.storyParagraph(
                            story, opts.storyVariant());
                    if (!paragraph.isEmpty() && !paragraph.equals(hook)) {
                        parts.add(paragraph);
                    }
                }
            }
        }
        if (opts.keywordLine() != null && !opts.keywordLine().isEmpty()) {
            parts.add(opts.keywordLine());
        }
        if (opts.story() != null && opts.showSourceLine()) {
            String source = LyricsShareCardRenderer.buildSourceLine(
                    sourceTemplate, sourceAiTemplate, opts.story().sourceLabel());
            if (!source.isEmpty()) {
                parts.add(source);
            }
        }
        return String.join("\n", parts);
    }

    /** Caps the lyric input to stay within the AI endpoint's single-request character budget. */
    private static List<String> capKeywordInput(List<String> lines) {
        final int maxChars = 2800; // OpenAIClient.MAX_CHARS is 3000; leave headroom for the prompt.
        List<String> out = new ArrayList<>();
        int total = 0;
        for (String line : lines) {
            if (line == null) continue;
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (total + t.length() + 1 > maxChars) break;
            out.add(t);
            total += t.length() + 1;
        }
        return out;
    }

    static String buildCopyTextForSelection(
            List<ShareLineEntry> entries,
            boolean includeBg,
            boolean includeRoma,
            boolean includeTrans,
            boolean swapTransRoma,
            String shareCaption) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            ShareLineEntry e = entries.get(i);
            if (i > 0) sb.append('\n');
            if (includeBg && e.bgBeforeText() != null && !e.bgBeforeText().trim().isEmpty()) {
                sb.append(e.bgBeforeText().trim()).append('\n');
            }
            sb.append(e.originalText());
            if (includeBg && e.bgAfterText() != null && !e.bgAfterText().trim().isEmpty()) {
                sb.append('\n').append(e.bgAfterText().trim());
            }
            final String romaText = e.romanizationText();
            final String transText = e.translationText();
            String roma = includeRoma && romaText != null && e.hasRomanization() ? romaText.trim() : null;
            String trans = includeTrans && transText != null && e.hasTranslation() ? transText.trim() : null;
            if (swapTransRoma) {
                if (trans != null) sb.append('\n').append(trans);
                if (roma != null) sb.append('\n').append(roma);
            } else {
                if (roma != null) sb.append('\n').append(roma);
                if (trans != null) sb.append('\n').append(trans);
            }
        }
        if (shareCaption != null && !shareCaption.isEmpty()) {
            sb.append("\n\n").append(shareCaption);
        }
        return sb.toString();
    }

    private static int readTextColor() {
        return LyricsTextColor.resolve(Settings.LYRICS_TEXT_COLOR_SLOT.get(),
                Settings.LYRICS_FULLSCREEN_TEXT_COLOR.get());
    }

    /** Cards keep their original palette for default and auto; only explicit colours override it. */
    @Nullable
    static Integer readCardTextColor() {
        int slot = Settings.LYRICS_TEXT_COLOR_SLOT.get();
        if (slot == LyricsTextColor.DEFAULT_SLOT || slot == LyricsTextColor.AUTO_SLOT) {
            return null;
        }
        int color = readTextColor();
        return color == 0xFFFFFFFF ? null : color;
    }

    /** Keeps palette content reachable on short or narrow screens. */
    private static void addScrollableColorContent(Context context, LinearLayout main, View content) {
        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        int maxHeight = Math.min(Dim.dp(420),
                context.getResources().getDisplayMetrics().heightPixels * 2 / 3);
        main.addView(scroll, main.getChildCount() - 1,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxHeight));
    }

    /** Long-press entry: 0=default white, 1=auto, 2..9=presets, -1=custom. */
    private static void showTextColorDialog(Context context, int currentSlot, int initialColor,
                                            Runnable onChanged) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, Dim.dp8, 0, Dim.dp8);

        ColorPickerView pickerView = new ColorPickerView(context);
        pickerView.setOpacitySliderEnabled(false);
        pickerView.setColor(0xFF000000 | (initialColor & 0x00FFFFFF));

        Pair<Dialog, LinearLayout> colorDialog = CustomDialog.create(
                context,
                str("morphe_music_lyrics_share_text_color"),
                null,
                null,
                str("morphe_settings_save"),
                () -> {
                    Settings.LYRICS_FULLSCREEN_TEXT_COLOR.save("#"
                            + Integer.toHexString(pickerView.getColor() & 0x00FFFFFF));
                    Settings.LYRICS_TEXT_COLOR_SLOT.save(-1);
                    onChanged.run();
                },
                () -> { },
                str("morphe_settings_reset_color"),
                () -> {
                    Settings.LYRICS_TEXT_COLOR_SLOT.resetToDefault();
                    onChanged.run();
                },
                true);

        LinearLayout presetRow = new LinearLayout(context);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView presetScroll = new HorizontalScrollView(context);
        presetScroll.setHorizontalScrollBarEnabled(false);
        presetScroll.addView(presetRow, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        container.addView(presetScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        int[] slots = new int[LyricsShareCardRenderer.TEXT_COLOR_PRESET_COLORS.length + 2];
        slots[0] = LyricsTextColor.DEFAULT_SLOT;
        slots[1] = LyricsTextColor.AUTO_SLOT;
        for (int i = 2; i < slots.length; i++) {
            slots[i] = i;
        }
        for (int slot : slots) {
            int preset = slot == LyricsTextColor.DEFAULT_SLOT
                    ? LyricsTextColor.defaultWhite()
                    : (slot == LyricsTextColor.AUTO_SLOT
                            ? LyricsTextColor.auto()
                            : LyricsShareCardRenderer.textColorPreset(slot));
            FrameLayout target = new FrameLayout(context);
            target.setFocusable(true);
            LyricsSheetChrome.attachInteractiveBackground(target, null, Dim.dp(24), 0x33FFFFFF);
            View disc = new View(context);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(preset);
            boolean active = slot == currentSlot;
            gd.setStroke(active ? Dim.dp(3) : Dim.dp(1), active ? 0xFFFFFFFF : 0x66FFFFFF);
            disc.setBackground(gd);
            target.addView(disc, new FrameLayout.LayoutParams(Dim.dp(32), Dim.dp(32), Gravity.CENTER));
            target.setOnClickListener(v -> {
                Settings.LYRICS_TEXT_COLOR_SLOT.save(slot);
                onChanged.run();
                colorDialog.first.dismiss();
            });
            presetRow.addView(target, new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));
        }

        container.addView(pickerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp(220)));

        addScrollableColorContent(context, colorDialog.second, container);
        try {
            colorDialog.first.show();
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Failed to show lyrics text colour picker", ex);
        }
    }

    private static void showCustomColorDialog(
            Context context, int initialColor, Consumer<Integer> onColorSelected) {
        ColorPickerView pickerView = new ColorPickerView(context);
        pickerView.setOpacitySliderEnabled(false);
        pickerView.setColor(0xFF000000 | (initialColor & 0x00FFFFFF));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, Dim.dp8, 0, Dim.dp8);
        container.addView(pickerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp(240)));

        Pair<Dialog, LinearLayout> colorDialog = CustomDialog.create(
                context,
                str("morphe_music_lyrics_share_custom_color"),
                null,
                null,
                str("morphe_settings_save"),
                () -> onColorSelected.accept(0xFF000000 | (pickerView.getColor() & 0x00FFFFFF)),
                () -> {
                },
                null,
                null,
                true);
        addScrollableColorContent(context, colorDialog.second, container);
        try {
            colorDialog.first.show();
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Failed to show custom color picker dialog", ex);
        }
    }

    interface PhotoPickCallback {
        void onComplete(@Nullable Bitmap bitmap, boolean failed);
    }

    @SuppressWarnings("deprecation")
    private static void launchGalleryImagePicker(
            Context context, Runnable onDecodeStarted, PhotoPickCallback callback) {
        Activity activity = unwrapActivity(context);
        if (activity == null || activity.isFinishing()) {
            callback.onComplete(null, true);
            return;
        }
        FragmentManager fm = null;
        PhotoPickerFragment picker = null;
        try {
            fm = activity.getFragmentManager();
            Fragment existing = fm.findFragmentByTag(PICKER_FRAGMENT_TAG);
            picker = new PhotoPickerFragment();
            picker.setCallback(onDecodeStarted, callback, context.getApplicationContext());
            if (existing != null) {
                fm.beginTransaction().remove(existing).add(picker, PICKER_FRAGMENT_TAG).commitAllowingStateLoss();
            } else {
                fm.beginTransaction().add(picker, PICKER_FRAGMENT_TAG).commitAllowingStateLoss();
            }
            fm.executePendingTransactions();
            picker.launch();
        } catch (Throwable ex) {
            if (fm != null && picker != null && picker.isAdded()) {
                try {
                    fm.beginTransaction().remove(picker).commitAllowingStateLoss();
                } catch (Throwable ignored) {
                }
            }
            Logger.printException(() -> "Failed to start gallery photo picker", ex);
            callback.onComplete(null, true);
        }
    }

    @Nullable
    private static Activity unwrapActivity(@Nullable Context context) {
        while (context instanceof ContextWrapper cw) {
            if (context instanceof Activity act) {
                return act;
            }
            context = cw.getBaseContext();
        }
        return null;
    }

    /**
     * Decodes the picked photo, scaled down to at most {@link #MAX_PICKED_IMAGE_DIM} pixels per side.
     * On Android 9 and later the photo is also turned the way its EXIF orientation says.
     */
    @Nullable
    static Bitmap decodeSampledImage(Context context, Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return decodeWithImageDecoder(context, uri);
            }
            return decodeWithBitmapFactory(context, uri);
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed decoding selected background photo", ex);
            return null;
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private static Bitmap decodeWithImageDecoder(Context context, Uri uri) throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            Size size = info.getSize();
            decoder.setTargetSampleSize(sampleSizeFor(size.getWidth(), size.getHeight()));
            // The card is drawn on a software canvas, which cannot draw hardware bitmaps.
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    @Nullable
    private static Bitmap decodeWithBitmapFactory(Context context, Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight);
        decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            return BitmapFactory.decodeStream(in, null, decodeOpts);
        }
    }

    private static int sampleSizeFor(int width, int height) {
        int sample = 1;
        while (width / sample > MAX_PICKED_IMAGE_DIM || height / sample > MAX_PICKED_IMAGE_DIM) {
            sample *= 2;
        }
        return sample;
    }

    @SuppressWarnings("deprecation")
    public static class PhotoPickerFragment extends Fragment {
        private static final int REQ_CODE = 0xC492;
        @Nullable
        private Runnable onDecodeStarted;
        @Nullable
        private PhotoPickCallback callback;
        @Nullable
        private Context appContext;

        void setCallback(
                Runnable onDecodeStarted,
                PhotoPickCallback callback,
                Context appContext) {
            this.onDecodeStarted = onDecodeStarted;
            this.callback = callback;
            this.appContext = appContext;
        }

        void launch() {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, str("morphe_music_lyrics_share_bg_photo")), REQ_CODE);
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            Runnable started = this.onDecodeStarted;
            PhotoPickCallback cb = this.callback;
            Context ctx = this.appContext;
            this.onDecodeStarted = null;
            this.callback = null;
            this.appContext = null;
            try {
                if (getActivity() != null && isAdded()) {
                    getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
                }
            } catch (Throwable ignored) {
            }
            if (requestCode != REQ_CODE || cb == null || ctx == null) {
                return;
            }
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                // User cancelled gallery chooser cleanly without failure toast.
                cb.onComplete(null, false);
                return;
            }
            if (started != null) {
                started.run();
            }
            Uri uri = data.getData();
            Utils.runOnBackgroundThread(() -> {
                Bitmap decoded = decodeSampledImage(ctx, uri);
                Utils.runOnMainThread(() -> cb.onComplete(decoded, decoded == null));
            });
        }
    }

    private static TextView createSegmentTab(Context context, String label) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER);
        tv.setMinimumHeight(Dim.dp(44));
        tv.setFocusable(true);
        return tv;
    }

    private static LinearLayout.LayoutParams segmentTabParams() {
        return new LinearLayout.LayoutParams(0, Dim.dp(44), 1f);
    }

    private static void styleSegmentTab(TextView tab, boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(Dim.dp(22));
        if (active) {
            bg.setColor(0xFFFFFFFF);
            tab.setTextColor(0xFF141418);
        } else {
            bg.setColor(Color.TRANSPARENT);
            tab.setTextColor(0xFFD2D2DC);
        }
        LyricsSheetChrome.attachInteractiveBackground(tab, bg, Dim.dp(22), active ? 0x22000000 : 0x2AFFFFFF);
    }

    private static LinearLayout createOptionChip(Context context, String label) {
        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(Dim.dp8, 0, Dim.dp8, 0);
        chip.setMinimumHeight(Dim.dp(40));
        chip.setContentDescription(label);
        chip.setFocusable(true);

        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        chip.addView(icon, new LinearLayout.LayoutParams(Dim.dp(18), Dim.dp(18)));

        TextView text = new TextView(context);
        text.setText(label);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        text.setTypeface(null, Typeface.BOLD);
        text.setSingleLine(true);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        textParams.leftMargin = Dim.dp4;
        chip.addView(text, textParams);
        return chip;
    }

    private static LinearLayout.LayoutParams iconOptionLayoutParams(boolean leftSpace) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Dim.dp(40));
        if (leftSpace) lp.leftMargin = Dim.dp8;
        return lp;
    }

    private static void styleOptionChip(LinearLayout chip, boolean active, String iconName) {
        ImageView icon = (ImageView) chip.getChildAt(0);
        TextView text = (TextView) chip.getChildAt(1);
        Drawable drawable = ResourceUtils.getDrawable(iconName);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setTint(active ? 0xFF141418 : 0xFFD2D2DC);
            icon.setImageDrawable(drawable);
        }
        text.setTextColor(active ? 0xFF141418 : 0xFFD2D2DC);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(Dim.dp(20));
        bg.setColor(active ? 0xFFFFFFFF : Color.TRANSPARENT);
        LyricsSheetChrome.attachInteractiveBackground(chip, bg, Dim.dp(20),
                active ? 0x22000000 : 0x2AFFFFFF);
    }

    private static View createActionButton(
            Context context, String text, String iconName, boolean primary) {
        LinearLayout btn = new LinearLayout(context);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER);
        btn.setMinimumHeight(Dim.dp(44));
        btn.setFocusable(true);

        ImageView iconView = new ImageView(context);
        Drawable icon = ResourceUtils.getDrawable(iconName);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(primary ? 0xFF0F0F14 : 0xFFD2D2DC);
            iconView.setImageDrawable(icon);
        }
        btn.addView(iconView, new LinearLayout.LayoutParams(Dim.dp20, Dim.dp20));

        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        label.setTypeface(null, Typeface.BOLD);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(primary ? 0xFF141418 : 0xFFD2D2DC);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMarginStart(Dim.dp8);
        btn.addView(label, labelParams);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        float radius = Dim.dp(22);
        bg.setCornerRadius(radius);
        bg.setColor(primary ? 0xFFFFFFFF : Color.TRANSPARENT);
        LyricsSheetChrome.attachInteractiveBackground(btn, bg, radius, primary ? 0x22000000 : 0x2AFFFFFF);
        return btn;
    }

    /** Custom color wheel + plus icon drawn without literal text characters. */
    private static final class CustomPaletteDrawable extends Drawable {
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint plusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CustomPaletteDrawable() {
            fillPaint.setColor(0xFF282832);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(Dim.dp(2.5f));
            ringPaint.setColor(0xFFD6D6E2);
            plusPaint.setStyle(Paint.Style.STROKE);
            plusPaint.setStrokeCap(Paint.Cap.ROUND);
            plusPaint.setStrokeWidth(Dim.dp(2f));
            plusPaint.setColor(0xFFFFFFFF);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float r = Math.min(b.width(), b.height()) * 0.44f;
            canvas.drawCircle(cx, cy, r, fillPaint);
            canvas.drawCircle(cx, cy, r, ringPaint);
            float arm = r * 0.46f;
            canvas.drawLine(cx - arm, cy, cx + arm, cy, plusPaint);
            canvas.drawLine(cx, cy - arm, cx, cy + arm, plusPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            fillPaint.setAlpha(alpha);
            ringPaint.setAlpha(alpha);
            plusPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            fillPaint.setColorFilter(colorFilter);
            ringPaint.setColorFilter(colorFilter);
            plusPaint.setColorFilter(colorFilter);
        }

        @Deprecated
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
