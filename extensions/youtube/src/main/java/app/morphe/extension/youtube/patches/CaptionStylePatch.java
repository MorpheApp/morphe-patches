/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.graphics.Typeface;
import android.view.accessibility.CaptioningManager.CaptionStyle;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Overrides the look of captions.
 * <p>
 * YouTube reads the system caption style ({@link CaptionStyle}) to draw captions.
 * The patch hooks every read of the style fields, so no hidden Android API or
 * object construction is needed.
 */
@SuppressWarnings("unused")
public class CaptionStylePatch {

    /**
     * Pen id that is never declared by a timed text document.
     * Looking it up returns the default pen, which uses the user caption style.
     * Same value as used by {@link TranslatedCaptionPensPatch}.
     */
    private static final int DEFAULT_PEN_ID = Integer.MIN_VALUE;

    private static final int DEFAULT_TEXT_COLOR = 0xFFFFFFFF;
    private static final int DEFAULT_BACKGROUND_COLOR = 0xBF000000;
    private static final int DEFAULT_WINDOW_COLOR = 0x00000000;
    private static final int DEFAULT_EDGE_COLOR = 0xFF000000;

    public enum CaptionFontFamily {
        /** Keep the font YouTube normally uses. */
        DEFAULT(null),
        SANS_SERIF("sans-serif"),
        SANS_SERIF_CONDENSED("sans-serif-condensed"),
        SANS_SERIF_MEDIUM("sans-serif-medium"),
        SERIF("serif"),
        MONOSPACE("monospace"),
        CASUAL("casual"),
        CURSIVE("cursive"),
        SMALL_CAPS("sans-serif-smallcaps");

        /**
         * Android system font family name.
         */
        @Nullable
        public final String family;

        CaptionFontFamily(@Nullable String family) {
            this.family = family;
        }
    }

    public enum CaptionEdgeType {
        NONE(CaptionStyle.EDGE_TYPE_NONE),
        OUTLINE(CaptionStyle.EDGE_TYPE_OUTLINE),
        DROP_SHADOW(CaptionStyle.EDGE_TYPE_DROP_SHADOW),
        RAISED(CaptionStyle.EDGE_TYPE_RAISED),
        DEPRESSED(CaptionStyle.EDGE_TYPE_DEPRESSED);

        /**
         * {@link CaptionStyle} edge type constant.
         */
        public final int edgeType;

        CaptionEdgeType(int edgeType) {
            this.edgeType = edgeType;
        }
    }

    private static CaptionFontFamily cachedFamily;
    private static Typeface cachedTypeface;

    private static boolean isEnabled() {
        return Settings.CAPTION_STYLE_ENABLED.get();
    }

    /**
     * Injection point. Replaces a read of {@code CaptionStyle.foregroundColor}.
     */
    public static int getForegroundColor(int original) {
        try {
            if (isEnabled()) {
                return CaptionStyleUtils.parseColor(Settings.CAPTION_TEXT_COLOR.get(), DEFAULT_TEXT_COLOR);
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
            if (isEnabled()) {
                return CaptionStyleUtils.parseColor(Settings.CAPTION_BACKGROUND_COLOR.get(), DEFAULT_BACKGROUND_COLOR);
            }
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
            if (isEnabled()) {
                return CaptionStyleUtils.parseColor(Settings.CAPTION_WINDOW_COLOR.get(), DEFAULT_WINDOW_COLOR);
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
            if (isEnabled()) {
                return CaptionStyleUtils.parseColor(Settings.CAPTION_EDGE_COLOR.get(), DEFAULT_EDGE_COLOR);
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
            if (isEnabled()) {
                return Settings.CAPTION_EDGE_TYPE.get().edgeType;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getEdgeType failure", ex);
        }
        return original;
    }

    /**
     * Injection point. Replaces the result of {@code CaptionStyle.hasXXX()}.
     * With a custom style every color and the edge are always specified.
     */
    public static boolean isStylePropertySpecified(boolean original) {
        try {
            return original || isEnabled();
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
            if (isEnabled()) {
                CaptionFontFamily family = Settings.CAPTION_FONT_FAMILY.get();
                if (family.family != null) {
                    if (family != cachedFamily || cachedTypeface == null) {
                        cachedTypeface = Typeface.create(family.family, Typeface.NORMAL);
                        cachedFamily = family;
                    }
                    return cachedTypeface;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getTypeface failure", ex);
        }
        return original;
    }

    /**
     * Injection point. Replaces the result of {@code CaptioningManager.getFontScale()}.
     */
    public static float getFontScale(float original) {
        try {
            if (isEnabled()) {
                return CaptionStyleUtils.scaleFontScale(original, Settings.CAPTION_FONT_SCALE.get());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getFontScale failure", ex);
        }
        return original;
    }

    /**
     * Injection point. Called with the pen id of a timed text element.
     * <p>
     * Pens carry the text style that a channel defines for its captions (colors, background).
     * Ignoring the pens shows every caption with the caption style of the user.
     *
     * @param penId Pen id of a timed text element.
     * @return The pen id to use.
     */
    public static int getPenId(int penId) {
        try {
            if (Settings.CAPTION_IGNORE_CREATOR_STYLE.get()) {
                return DEFAULT_PEN_ID;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getPenId failure", ex);
        }
        return penId;
    }
}
