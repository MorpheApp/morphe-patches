/*
 * Caption-style hook prototype adapted from Glassto:
 * https://github.com/MorpheApp/morphe-patches/pull/4059
 *
 * Portions Copyright (C) 2023-2024 Anil Beesetti (Next Player)
 * https://github.com/anilbeesetti/nextplayer
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 *
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.youtube.settings.Settings.CAPTION_STYLE_PRESET;
import static app.morphe.extension.youtube.settings.Settings.CAPTION_FONT_FAMILY;
import static app.morphe.extension.youtube.settings.Settings.CAPTION_TEXT_BOLD;
import static app.morphe.extension.youtube.settings.Settings.CAPTION_BACKGROUND;
import static app.morphe.extension.youtube.settings.Settings.CAPTION_APPLY_EMBEDDED_STYLES;

import android.graphics.Typeface;
import android.view.accessibility.CaptioningManager.CaptionStyle;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.Setting;
import java.util.List;

/**
 * Overrides the look of YouTube captions to match Next Player subtitle styling,
 * while retaining an opt-in screenshot reference preset (bold italic).
 * <p>
 * Upstream Next Player behaviors and defaults (dev.anilbeesetti.nextplayer):
 * - Subtitle font: DEFAULT, MONOSPACE, SANS_SERIF, SERIF (default: DEFAULT)
 * - Text bold: true (default: true -> Typeface.BOLD, false -> Typeface.NORMAL)
 * - Background: false (default: false -> TRANSPARENT, true -> BLACK)
 * - Foreground color: WHITE (0xFFFFFFFF)
 * - Edge type: DROP_SHADOW (CaptionStyle.EDGE_TYPE_DROP_SHADOW), edge color: BLACK (0xFF000000)
 * - Window color: TRANSPARENT (0x00000000)
 * - Embedded styles: true (default: true -> keep creator styles; false -> bypass timed text pens)
 * - Text size: uses the existing native caption percentage-size setting.
 * <p>
 * Hooks caption-style field reads, typeface results, and timed-text pen lookup.
 * Renderer coverage must be validated against each supported YouTube version.
 */
@SuppressWarnings("unused")
public class CaptionStylePatch {

    /**
     * Pen id that is never declared by a timed text document.
     * Looking it up returns the default pen, which uses the user caption style.
     * Same value as used by {@link TranslatedCaptionPensPatch}.
     */
    public static final int DEFAULT_PEN_ID = Integer.MIN_VALUE;

    public enum CaptionStylePreset {
        /**
         * System default caption style. Leaves YouTube caption styling untouched.
         */
        DEFAULT,
        /**
         * Next Player subtitle appearance with configurable font family, bold text,
         * background toggle, size scale, and embedded styles toggle.
         */
        NEXT_PLAYER,
        /**
         * Reference preset matching the reference screenshot:
         * White bold italic text, dark drop shadow, transparent background and window.
         */
        REFERENCE
    }

    /**
     * Font families supported by Next Player (dev.anilbeesetti.nextplayer.core.model.Font).
     */
    public enum CaptionFontFamily {
        DEFAULT,
        MONOSPACE,
        SANS_SERIF,
        SERIF;

        @Nullable
        public Typeface toTypeface() {
            return switch (this) {
                case DEFAULT -> Typeface.DEFAULT;
                case MONOSPACE -> Typeface.MONOSPACE;
                case SANS_SERIF -> Typeface.SANS_SERIF;
                case SERIF -> Typeface.SERIF;
            };
        }
    }

    public static class NextPlayerStyleAvailability implements Setting.Availability {
        @Override public boolean isAvailable() { return isNextPlayerActive(); }
        @Override public List<Setting<?>> getParentSettings() { return List.of(CAPTION_STYLE_PRESET); }
    }

    private static CaptionStylePreset cachedPreset;
    private static CaptionFontFamily cachedFont;
    private static boolean cachedBold;
    private static Typeface cachedTypeface;

    public static boolean isStylingActive() {
        return CAPTION_STYLE_PRESET.get() != CaptionStylePreset.DEFAULT;
    }

    public static boolean isReferenceActive() {
        return CAPTION_STYLE_PRESET.get() == CaptionStylePreset.REFERENCE;
    }

    public static boolean isNextPlayerActive() {
        CaptionStylePreset preset = CAPTION_STYLE_PRESET.get();
        return preset == CaptionStylePreset.NEXT_PLAYER;
    }

    /**
     * Injection point. Replaces a read of {@code CaptionStyle.foregroundColor}.
     */
    public static int getForegroundColor(int original) {
        try {
            if (isStylingActive()) {
                return CaptionStyleUtils.COLOR_WHITE;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getForegroundColor failure", ex);
        }
        return original;
    }

    /**
     * Injection point. Replaces a read of {@code CaptionStyle.backgroundColor}.
     */
    public static int getBackgroundColor(int original) {
        try {
            return CaptionStyleUtils.backgroundColor(original, isStylingActive(),
                    isReferenceActive(), CAPTION_BACKGROUND.get());
        } catch (Exception ex) {
            Logger.printException(() -> "getBackgroundColor failure", ex);
        }
        return original;
    }

    /**
     * Injection point. Replaces a read of {@code CaptionStyle.windowColor}.
     */
    public static int getWindowColor(int original) {
        try {
            if (isStylingActive()) {
                return CaptionStyleUtils.COLOR_TRANSPARENT;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getWindowColor failure", ex);
        }
        return original;
    }

    /**
     * Injection point. Replaces a read of {@code CaptionStyle.edgeColor}.
     */
    public static int getEdgeColor(int original) {
        try {
            if (isStylingActive()) {
                return CaptionStyleUtils.COLOR_BLACK;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getEdgeColor failure", ex);
        }
        return original;
    }

    /**
     * Injection point. Replaces a read of {@code CaptionStyle.edgeType}.
     */
    public static int getEdgeType(int original) {
        try {
            if (isStylingActive()) {
                return CaptionStyleUtils.EDGE_TYPE_DROP_SHADOW;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getEdgeType failure", ex);
        }
        return original;
    }

    /**
     * Injection point. Replaces the result of {@code CaptionStyle.hasXXX()}.
     * With active styling every color and edge property is treated as explicitly specified.
     */
    public static boolean isStylePropertySpecified(boolean original) {
        try {
            return original || isStylingActive();
        } catch (Exception ex) {
            Logger.printException(() -> "isStylePropertySpecified failure", ex);
            return original;
        }
    }

    /**
     * Injection point. Replaces the result of {@code CaptionStyle.getTypeface()}.
     */
    @Nullable
    public static Typeface getTypeface(@Nullable Typeface original) {
        try {
            if (isReferenceActive()) {
                if (cachedPreset != CaptionStylePreset.REFERENCE || cachedTypeface == null) {
                    cachedTypeface = Typeface.create(Typeface.DEFAULT, CaptionStyleUtils.fontStyle(true, true));
                    cachedPreset = CaptionStylePreset.REFERENCE;
                }
                return cachedTypeface;
            } else if (isNextPlayerActive()) {
                CaptionFontFamily font = CAPTION_FONT_FAMILY.get();
                boolean bold = CAPTION_TEXT_BOLD.get();
                CaptionStylePreset preset = CAPTION_STYLE_PRESET.get();
                if (preset != cachedPreset || font != cachedFont || bold != cachedBold || cachedTypeface == null) {
                    int style = CaptionStyleUtils.fontStyle(false, bold);
                    Typeface base = font.toTypeface();
                    cachedTypeface = Typeface.create(base, style);
                    cachedPreset = preset;
                    cachedFont = font;
                    cachedBold = bold;
                }
                return cachedTypeface;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getTypeface failure", ex);
        }
        return original;
    }

    /**
     * Injection point. Called with the pen id of a timed text element.
     * <p>
     * Video captions can define pens carrying creator text/background styles.
     * When embedded styles are turned off (or when reference preset is used),
     * returning {@link #DEFAULT_PEN_ID} tells the renderer to use default pen
     * styles that follow the user caption style.
     *
     * @param penId Pen id of a timed text element.
     * @return The pen id to use.
     */
    public static int getPenId(int penId) {
        try {
            if (CaptionStyleUtils.shouldBypassPen(isStylingActive(), isReferenceActive(),
                    CAPTION_APPLY_EMBEDDED_STYLES.get())) {
                return DEFAULT_PEN_ID;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getPenId failure", ex);
        }
        return penId;
    }
}
