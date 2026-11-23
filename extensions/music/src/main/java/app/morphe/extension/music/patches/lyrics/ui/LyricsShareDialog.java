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
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
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
import android.widget.ImageButton;
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
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.ui.LyricsShareCardRenderer.BackgroundMode;
import app.morphe.extension.music.patches.lyrics.ui.LyricsShareCardRenderer.CardPalette;
import app.morphe.extension.music.patches.lyrics.ui.LyricsShareCardRenderer.CardSwatch;
import app.morphe.extension.music.patches.lyrics.ui.LyricsShareCardRenderer.ShareLineEntry;
import app.morphe.extension.music.patches.lyrics.ui.LyricsShareCardRenderer.ShareRenderOptions;
import app.morphe.extension.music.settings.Settings;
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
    private static final String PICKER_FRAGMENT_TAG = "MorpheSharePhotoPickerFragment";
    private static final int STAGE_GROUND_COLOR = 0xFF0D0D10;
    private static final int SEGMENT_TRACK_COLOR = 0xFF222229;
    private static final String ICON_ROMAJI = "morphe_yt_romanize";
    private static final String ICON_TRANSLATE = "yt_outline_experimental_translate_vd_theme_24";
    private static final String ICON_BG = "morphe_share_bg_tag";
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
            @Nullable TrackInfo track,
            @Nullable String rawTitle,
            @Nullable String rawArtist,
            DynamicBackgroundView.ShareSnapshot snapshot,
            boolean initialRomajiOn,
            boolean initialTranslateOn,
            boolean swapTransRoma,
            boolean forceCenter,
            Runnable onShared,
            Runnable onFinished) {
        final CardPalette albumPalette = snapshot.palette() != null
                ? snapshot.palette()
                : CardPalette.DEFAULT;
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

        final BackgroundMode[] bgMode = {BackgroundMode.BLUR_MESH};
        final int[] solidIndex = {0};
        final int[] customSolidColor = {albumPalette.backgroundColor()};
        final boolean[] usingCustomSolid = {false};
        final Bitmap[] customPhotoBitmap = {null};
        final boolean[] showBranding = {Settings.LYRICS_SHARE_SHOW_BRANDING.get()};
        final boolean[] includeBg = {hasBgOption};
        final boolean[] includeRoma = {hasRomaOption && initialRomajiOn};
        final boolean[] includeTrans = {hasTransOption && initialTranslateOn};
        final Bitmap[] currentPreview = {null};
        final boolean[] busy = {false};
        final boolean[] dismissed = {false};
        final boolean[] exportInFlight = {false};

        LinearLayout sheet = SheetBottomDialog.createMainLayout(context, STAGE_GROUND_COLOR);
        Dialog dialog = SheetBottomDialog.createSlideDialog(context, sheet, SLIDE_ANIMATION_MILLIS);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(STAGE_GROUND_COLOR);

        // Top studio header (>= 48dp accessible back button + high-contrast title)
        LinearLayout topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Dim.dp8, Dim.dp8, Dim.dp16, Dim.dp4);

        ImageView backBtn = new ImageView(context);
        backBtn.setImageDrawable(new BackArrowDrawable());
        backBtn.setScaleType(ImageView.ScaleType.CENTER);
        backBtn.setContentDescription(str("morphe_music_lyrics_share_back"));
        backBtn.setFocusable(true);
        attachInteractiveBackground(backBtn, null, Dim.dp(24), 0x33FFFFFF);
        backBtn.setOnClickListener(v -> dialog.dismiss());
        topBar.addView(backBtn, new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));

        TextView titleView = new TextView(context);
        titleView.setText(str("morphe_music_lyrics_share_preview_title"));
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        titleView.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = Dim.dp8;
        topBar.addView(titleView, titleParams);
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
            return new ShareRenderOptions(
                    bgMode[0],
                    activePalette,
                    customPhotoBitmap[0],
                    showBranding[0],
                    includeBg[0],
                    includeRoma[0],
                    includeTrans[0],
                    swapTransRoma,
                    forceCenter);
        };

        final Runnable updateBackdropAndPreview = () -> {
            ShareRenderOptions opts = buildOptions.get();
            int baseColor = opts.backgroundMode() == BackgroundMode.SOLID_COLOR
                    ? opts.palette().backgroundColor()
                    : albumPalette.backgroundColor();
            int stageColor = computeStageBackdropColor(baseColor);
            root.setBackgroundColor(stageColor);

            Bitmap bmp = LyricsShareCardRenderer.renderRichCardBitmap(
                    entries, track, rawTitle, rawArtist, snapshot, opts, PREVIEW_WIDTH_PX);
            Bitmap old = currentPreview[0];
            currentPreview[0] = bmp;
            previewImage.setImageBitmap(bmp);
            if (old != null && !old.isRecycled()) {
                old.recycle();
            }
        };

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
            attachInteractiveBackground(hitBox, null, Dim.dp(24), 0x33FFFFFF);

            View disc = new View(context);
            FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(discPx, discPx, Gravity.CENTER);
            hitBox.addView(disc, dp);

            hitBox.setOnClickListener(v -> {
                if (busy[0]) return;
                usingCustomSolid[0] = false;
                solidIndex[0] = idx;
                bgMode[0] = BackgroundMode.SOLID_COLOR;
                updateSwatchBorders.run();
                updateBackdropAndPreview.run();
            });
            swatchTargets.add(hitBox);
            swatchDiscs.add(disc);
            swatchRow.addView(hitBox, new LinearLayout.LayoutParams(touchPx, touchPx));
        }

        customColorTarget.setFocusable(true);
        customColorTarget.setContentDescription(str("morphe_music_lyrics_share_custom_color"));
        attachInteractiveBackground(customColorTarget, null, Dim.dp(24), 0x33FFFFFF);
        customColorTarget.addView(customColorDisc, new FrameLayout.LayoutParams(discPx, discPx, Gravity.CENTER));
        customColorTarget.setOnClickListener(v -> {
            if (busy[0]) return;
            showCustomColorDialog(context, customSolidColor[0], chosenColor -> {
                customSolidColor[0] = chosenColor;
                usingCustomSolid[0] = true;
                bgMode[0] = BackgroundMode.SOLID_COLOR;
                updateSwatchBorders.run();
                updateBackdropAndPreview.run();
            });
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

        // Checkable layer options + distinct watermark toggle, all rendered as circular icons
        final ImageButton bgSwitch = hasBgOption
                ? createOptionIcon(context,
                        str("morphe_music_lyrics_share_layer_bg")) : null;
        final ImageButton romaSwitch = hasRomaOption
                ? createOptionIcon(context,
                        str("morphe_music_lyrics_share_layer_romaji")) : null;
        final ImageButton transSwitch = hasTransOption
                ? createOptionIcon(context,
                        str("morphe_music_lyrics_share_layer_translate")) : null;
        final ImageButton brandSwitch = createOptionIcon(context,
                str("morphe_music_lyrics_share_branding_logo"));

        if (bgSwitch != null) {
            styleOptionIcon(bgSwitch, includeBg[0], ICON_BG);
            bgSwitch.setOnClickListener(v -> {
                if (busy[0]) return;
                includeBg[0] = !includeBg[0];
                styleOptionIcon(bgSwitch, includeBg[0], ICON_BG);
                updateBackdropAndPreview.run();
            });
            optionsRow.addView(bgSwitch, iconOptionLayoutParams(optionsRow.getChildCount() > 0));
        }
        if (romaSwitch != null) {
            styleOptionIcon(romaSwitch, includeRoma[0], ICON_ROMAJI);
            romaSwitch.setOnClickListener(v -> {
                if (busy[0]) return;
                includeRoma[0] = !includeRoma[0];
                styleOptionIcon(romaSwitch, includeRoma[0], ICON_ROMAJI);
                updateBackdropAndPreview.run();
            });
            optionsRow.addView(romaSwitch, iconOptionLayoutParams(optionsRow.getChildCount() > 0));
        }
        if (transSwitch != null) {
            styleOptionIcon(transSwitch, includeTrans[0], ICON_TRANSLATE);
            transSwitch.setOnClickListener(v -> {
                if (busy[0]) return;
                includeTrans[0] = !includeTrans[0];
                styleOptionIcon(transSwitch, includeTrans[0], ICON_TRANSLATE);
                updateBackdropAndPreview.run();
            });
            optionsRow.addView(transSwitch, iconOptionLayoutParams(optionsRow.getChildCount() > 0));
        }
        styleOptionIcon(brandSwitch, showBranding[0], ICON_BRANDING);
        brandSwitch.setOnClickListener(v -> {
            if (busy[0]) return;
            showBranding[0] = !showBranding[0];
            Settings.LYRICS_SHARE_SHOW_BRANDING.save(showBranding[0]);
            styleOptionIcon(brandSwitch, showBranding[0], ICON_BRANDING);
            updateBackdropAndPreview.run();
        });
        optionsRow.addView(brandSwitch, iconOptionLayoutParams(optionsRow.getChildCount() > 0));

        final Runnable refreshModeTabs = () -> {
            styleSegmentTab(blurTab, bgMode[0] == BackgroundMode.BLUR_MESH);
            if (coverTab != null) {
                styleSegmentTab(coverTab, bgMode[0] == BackgroundMode.ALBUM_COVER);
            }
            styleSegmentTab(colorTab, bgMode[0] == BackgroundMode.SOLID_COLOR);
            styleSegmentTab(photoTab, bgMode[0] == BackgroundMode.CUSTOM_IMAGE);
            swatchScroll.setVisibility(bgMode[0] == BackgroundMode.SOLID_COLOR ? View.VISIBLE : View.GONE);
        };

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

        blurTab.setOnClickListener(v -> {
            if (busy[0]) return;
            bgMode[0] = BackgroundMode.BLUR_MESH;
            refreshModeTabs.run();
            updateBackdropAndPreview.run();
        });
        if (coverTab != null) {
            coverTab.setOnClickListener(v -> {
                if (busy[0]) return;
                bgMode[0] = BackgroundMode.ALBUM_COVER;
                refreshModeTabs.run();
                updateBackdropAndPreview.run();
            });
        }
        colorTab.setOnClickListener(v -> {
            if (busy[0]) return;
            bgMode[0] = BackgroundMode.SOLID_COLOR;
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
        String shareCaption = displayTitle.isEmpty()
                ? ""
                : (displayArtist.isEmpty() ? displayTitle : displayTitle + " - " + displayArtist);

        copyBtn.setOnClickListener(v -> {
            if (busy[0]) return;
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                String plainText = buildCopyTextForSelection(
                        entries, includeBg[0], includeRoma[0], includeTrans[0], swapTransRoma, shareCaption);
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
                            if (!shareCaption.isEmpty()) {
                                sendIntent.putExtra(Intent.EXTRA_TEXT, shareCaption);
                            }
                            sendIntent.setClipData(ClipData.newRawUri("lyrics_card", contentUri));
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
        LinearLayout main = colorDialog.second;
        main.addView(container, main.getChildCount() - 1,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
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
        attachInteractiveBackground(tab, bg, Dim.dp(22), active ? 0x22000000 : 0x2AFFFFFF);
    }

    private static ImageButton createOptionIcon(Context context, String description) {
        ImageButton button = new ImageButton(context);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setContentDescription(description);
        button.setFocusable(true);
        return button;
    }

    private static LinearLayout.LayoutParams iconOptionLayoutParams(boolean leftSpace) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Dim.dp(40), Dim.dp(40));
        if (leftSpace) lp.leftMargin = Dim.dp8;
        return lp;
    }

    private static void styleOptionIcon(ImageButton button, boolean active, String iconName) {
        Drawable icon = ResourceUtils.getDrawable(iconName);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(active ? 0xFF141418 : 0xFFD2D2DC);
            button.setImageDrawable(icon);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(Dim.dp(20));
        bg.setColor(active ? 0xFFFFFFFF : Color.TRANSPARENT);
        attachInteractiveBackground(button, bg, Dim.dp(20), active ? 0x22000000 : 0x2AFFFFFF);
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
        attachInteractiveBackground(btn, bg, radius, primary ? 0x22000000 : 0x2AFFFFFF);
        return btn;
    }

    private static void attachInteractiveBackground(
            View view, @Nullable Drawable baseDrawable, float focusCornerRadiusPx, int rippleColor) {
        GradientDrawable focusOverlay = new GradientDrawable();
        focusOverlay.setShape(GradientDrawable.RECTANGLE);
        focusOverlay.setCornerRadius(focusCornerRadiusPx);
        focusOverlay.setColor(Color.TRANSPARENT);
        focusOverlay.setStroke(Dim.dp(2), 0xFFFFFFFF);

        StateListDrawable states = new StateListDrawable();
        if (baseDrawable != null) {
            states.addState(new int[]{android.R.attr.state_focused},
                    new LayerDrawable(new Drawable[]{baseDrawable, focusOverlay}));
            states.addState(new int[0], baseDrawable);
        } else {
            states.addState(new int[]{android.R.attr.state_focused}, focusOverlay);
            states.addState(new int[0], new GradientDrawable());
        }

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(focusCornerRadiusPx);
        mask.setColor(0xFFFFFFFF);

        view.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), states, mask));
    }

    /** Crisp 2dp stroke back arrow drawable matching Android Material metrics. */
    private static final class BackArrowDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        BackArrowDrawable() {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(0xFFFFFFFF);
            paint.setStrokeWidth(Math.max(3f, Dim.dp(2.2f)));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float s = Dim.dp(8);
            path.reset();
            path.moveTo(cx + s, cy);
            path.lineTo(cx - s, cy);
            path.moveTo(cx - s * 0.1f, cy - s * 0.85f);
            path.lineTo(cx - s, cy);
            path.lineTo(cx - s * 0.1f, cy + s * 0.85f);
            canvas.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Deprecated
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
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
