/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import app.morphe.extension.music.patches.lyrics.LyricsDirectories;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Manages user-uploaded custom {@code .ttf} / {@code .otf} fonts for the lyrics panel
 * (such as SF Pro Bold, Inter Bold, or Inter ExtraBold).
 *
 * <p>Inspects SFNT binary tables ({@code OS/2}, {@code name}, {@code fvar}) so static bold or
 * extra-bold weights keep their crisp native outlines without synthetic double-bolding, and chains
 * the uploaded font ahead of the system {@code sans-serif} fallback family on Android 10+ so
 * missing CJK, Kana, Hangul, or emoji glyphs still render cleanly.
 */
public final class LyricsFontManager {

    private static final String STORED_FONT_BASENAME = "custom_lyrics_font";
    private static final String TEMP_FONT_NAME = "custom_lyrics_font.tmp";
    private static final int MAX_FONT_BYTES = 48 * 1024 * 1024; // 48 MB safety cap

    private static final int TAG_OTTO = 0x4F54544F; // 'OTTO'
    private static final int TAG_TRUE = 0x74727565; // 'true'
    private static final int TAG_TTCF = 0x74746366; // 'ttcf'
    private static final int TAG_OS2 = 0x4F532F32;  // 'OS/2'
    private static final int TAG_NAME = 0x6E616D65; // 'name'
    private static final int TAG_FVAR = 0x66766172; // 'fvar'
    private static final int TAG_WGHT = 0x77676874; // 'wght'
    private static final int TAG_WDTH = 0x77647468; // 'wdth'

    private static volatile boolean initialized;
    @Nullable
    private static volatile Typeface cachedMainTypeface;
    @Nullable
    private static volatile Typeface cachedRegularTypeface;
    @Nullable
    private static volatile FontMetadata cachedMetadata;

    private LyricsFontManager() {
    }

    /** Parsed SFNT binary properties for an uploaded font file. */
    public record FontMetadata(
            boolean valid,
            String displayName,
            int weight,
            boolean italic,
            boolean hasWeightAxis,
            boolean hasWidthAxis
    ) {
        static final FontMetadata INVALID = new FontMetadata(false, "", 400, false, false, false);
    }

    /** Result of importing a user-picked font URI. */
    public record FontImportResult(boolean success, String displayLabel) {
    }

    /**
     * Validates an SFNT ({@code .ttf}, {@code .otf}, {@code .ttc}) byte array and extracts its
     * display name, native weight class, and variable-font axis support.
     */
    public static FontMetadata parseFontMetadata(@Nullable byte[] data) {
        if (data == null || data.length < 12) {
            return FontMetadata.INVALID;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int sfntOffset = 0;
        int magic = buf.getInt(0);
        if (magic == TAG_TTCF) {
            if (data.length < 16) {
                return FontMetadata.INVALID;
            }
            int numFonts = buf.getInt(8);
            if (numFonts <= 0) {
                return FontMetadata.INVALID;
            }
            long firstOffset = buf.getInt(12) & 0xFFFFFFFFL;
            if (firstOffset < 12 || firstOffset + 12 > data.length) {
                return FontMetadata.INVALID;
            }
            sfntOffset = (int) firstOffset;
            magic = buf.getInt(sfntOffset);
        }
        if (magic != 0x00010000 && magic != TAG_OTTO && magic != TAG_TRUE) {
            return FontMetadata.INVALID;
        }

        int numTables = buf.getShort(sfntOffset + 4) & 0xFFFF;
        long dirEnd = (long) sfntOffset + 12L + (long) numTables * 16L;
        if (numTables <= 0 || numTables > 256 || dirEnd > data.length) {
            return FontMetadata.INVALID;
        }

        int os2Offset = -1, os2Len = 0;
        int nameOffset = -1, nameLen = 0;
        int fvarOffset = -1, fvarLen = 0;

        for (int i = 0; i < numTables; i++) {
            int rec = sfntOffset + 12 + i * 16;
            int tag = buf.getInt(rec);
            long tOff = buf.getInt(rec + 8) & 0xFFFFFFFFL;
            long tLen = buf.getInt(rec + 12) & 0xFFFFFFFFL;
            if (tOff < 0 || tLen < 0 || tOff + tLen > data.length) {
                continue;
            }
            if (tag == TAG_OS2) {
                os2Offset = (int) tOff;
                os2Len = (int) tLen;
            } else if (tag == TAG_NAME) {
                nameOffset = (int) tOff;
                nameLen = (int) tLen;
            } else if (tag == TAG_FVAR) {
                fvarOffset = (int) tOff;
                fvarLen = (int) tLen;
            }
        }

        int weight = 700;
        boolean italic = false;
        if (os2Offset >= 0 && os2Len >= 64) {
            int w = buf.getShort(os2Offset + 4) & 0xFFFF;
            if (w >= 100 && w <= 1000) {
                weight = w;
            }
            int fsSelection = buf.getShort(os2Offset + 62) & 0xFFFF;
            italic = (fsSelection & 0x01) != 0;
        }

        boolean hasWeightAxis = false;
        boolean hasWidthAxis = false;
        if (fvarOffset >= 0 && fvarLen >= 16) {
            int axesArrayOffset = buf.getShort(fvarOffset + 4) & 0xFFFF;
            int axisCount = buf.getShort(fvarOffset + 8) & 0xFFFF;
            int axisSize = buf.getShort(fvarOffset + 10) & 0xFFFF;
            if (axisSize >= 20 && axisCount > 0 && axisCount <= 32) {
                for (int a = 0; a < axisCount; a++) {
                    long axPos = (long) fvarOffset + axesArrayOffset + (long) a * axisSize;
                    if (axPos + 4 > fvarOffset + fvarLen || axPos + 4 > data.length) {
                        break;
                    }
                    int axTag = buf.getInt((int) axPos);
                    if (axTag == TAG_WGHT) {
                        hasWeightAxis = true;
                    } else if (axTag == TAG_WDTH) {
                        hasWidthAxis = true;
                    }
                }
            }
        }

        String displayName = "";
        if (nameOffset >= 0 && nameLen >= 6) {
            displayName = extractDisplayName(buf, data, nameOffset, nameLen);
        }

        return new FontMetadata(true, displayName, weight, italic, hasWeightAxis, hasWidthAxis);
    }

    private static String extractDisplayName(ByteBuffer buf, byte[] data, int nameOffset, int nameLen) {
        int count = buf.getShort(nameOffset + 2) & 0xFFFF;
        int stringStorageOffset = buf.getShort(nameOffset + 4) & 0xFFFF;
        if (count <= 0 || count > 2048) {
            return "";
        }
        String fullName = null;
        String family = null;
        String subfamily = null;
        String typographicFamily = null;
        String typographicSubfamily = null;

        for (int i = 0; i < count; i++) {
            long rec = (long) nameOffset + 6L + (long) i * 12L;
            if (rec + 12 > nameOffset + nameLen || rec + 12 > data.length) {
                break;
            }
            int platformId = buf.getShort((int) rec) & 0xFFFF;
            int encodingId = buf.getShort((int) rec + 2) & 0xFFFF;
            int languageId = buf.getShort((int) rec + 4) & 0xFFFF;
            int nameId = buf.getShort((int) rec + 6) & 0xFFFF;
            int strLen = buf.getShort((int) rec + 8) & 0xFFFF;
            int strOff = buf.getShort((int) rec + 10) & 0xFFFF;

            if (nameId != 1 && nameId != 2 && nameId != 4 && nameId != 16 && nameId != 17) {
                continue;
            }
            long absStr = (long) nameOffset + stringStorageOffset + strOff;
            if (strLen <= 0 || absStr < 0 || absStr + strLen > nameOffset + nameLen || absStr + strLen > data.length) {
                continue;
            }

            String decoded = null;
            boolean preferredEnglish = false;
            if (platformId == 3 && (encodingId == 1 || encodingId == 10)) {
                decoded = new String(data, (int) absStr, strLen, StandardCharsets.UTF_16BE).trim();
                preferredEnglish = (languageId == 0x0409);
            } else if (platformId == 0) {
                decoded = new String(data, (int) absStr, strLen, StandardCharsets.UTF_16BE).trim();
                preferredEnglish = (languageId == 0);
            } else if (platformId == 1 && encodingId == 0) {
                decoded = new String(data, (int) absStr, strLen, StandardCharsets.ISO_8859_1).trim();
                preferredEnglish = (languageId == 0);
            }
            if (decoded == null || decoded.isEmpty()) {
                continue;
            }

            switch (nameId) {
                case 4 -> {
                    if (fullName == null || preferredEnglish) fullName = decoded;
                }
                case 1 -> {
                    if (family == null || preferredEnglish) family = decoded;
                }
                case 2 -> {
                    if (subfamily == null || preferredEnglish) subfamily = decoded;
                }
                case 16 -> {
                    if (typographicFamily == null || preferredEnglish) typographicFamily = decoded;
                }
                case 17 -> {
                    if (typographicSubfamily == null || preferredEnglish)
                        typographicSubfamily = decoded;
                }
                default -> {
                }
            }
        }

        if (typographicFamily != null && !typographicFamily.isEmpty()) {
            if (typographicSubfamily != null && !typographicSubfamily.isEmpty()
                    && !typographicFamily.toLowerCase(Locale.ROOT).contains(typographicSubfamily.toLowerCase(Locale.ROOT))) {
                return typographicFamily + " " + typographicSubfamily;
            }
            return typographicFamily;
        }
        if (fullName != null && !fullName.isEmpty()) {
            return fullName;
        }
        if (family != null && !family.isEmpty()) {
            if (subfamily != null && !subfamily.isEmpty()
                    && !family.toLowerCase(Locale.ROOT).contains(subfamily.toLowerCase(Locale.ROOT))) {
                return family + " " + subfamily;
            }
            return family;
        }
        return "";
    }

    static FontMetadata parseFontMetadata(File file) {
        if (!file.isFile()) {
            return FontMetadata.INVALID;
        }
        long len = file.length();
        if (len < 12 || len > MAX_FONT_BYTES) {
            return FontMetadata.INVALID;
        }
        try {
            byte[] bytes = new byte[(int) len];
            try (InputStream in = new FileInputStream(file)) {
                int offset = 0;
                while (offset < bytes.length) {
                    int r = in.read(bytes, offset, bytes.length - offset);
                    if (r < 0) break;
                    offset += r;
                }
                if (offset < 12) {
                    return FontMetadata.INVALID;
                }
                return parseFontMetadata(bytes);
            }
        } catch (Throwable ex) {
            return FontMetadata.INVALID;
        }
    }

    /** Returns the primary bold/display typeface used for lyric lines, romaji, ruby, and share cards. */
    public static Typeface mainTypeface() {
        ensureLoaded();
        Typeface custom = cachedMainTypeface;
        return custom != null ? custom : Typeface.DEFAULT_BOLD;
    }

    /** Returns the regular/footer typeface, or the custom typeface when active. */
    public static Typeface regularOrMainTypeface() {
        ensureLoaded();
        Typeface custom = cachedRegularTypeface;
        return custom != null ? custom : Typeface.create("sans-serif", Typeface.NORMAL);
    }

    /**
     * Optional OpenType variation settings for the active font, or {@code null} when static or
     * using the platform default.
     */
    @Nullable
    public static String mainFontVariation() {
        ensureLoaded();
        FontMetadata meta = cachedMetadata;
        if (cachedMainTypeface != null && meta != null && meta.hasWeightAxis()) {
            int targetWeight = Math.max(700, meta.weight());
            return "'wght' " + targetWeight;
        }
        return null;
    }

    /** {@return true when a valid user-uploaded custom lyrics font is currently active} */
    public static boolean isCustomFontActive() {
        ensureLoaded();
        return cachedMainTypeface != null;
    }

    /** {@return whether the currently active lyrics font supports the OpenType 'wdth' axis} */
    public static boolean activeFontSupportsWidthAxis() {
        ensureLoaded();
        FontMetadata meta = cachedMetadata;
        if (cachedMainTypeface != null && meta != null) {
            return meta.hasWidthAxis();
        }
        return true;
    }

    /** {@return human-readable label for the active custom font, or empty string if default} */
    public static String activeFontSummaryName() {
        String raw = Settings.LYRICS_CUSTOM_FONT.get();
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        int sep = raw.indexOf('|');
        return sep >= 0 ? raw.substring(sep + 1).trim() : raw.trim();
    }

    @Nullable
    private static String storedFileNameFromSetting() {
        String raw = Settings.LYRICS_CUSTOM_FONT.get();
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        int sep = raw.indexOf('|');
        String filePart = sep >= 0 ? raw.substring(0, sep).trim() : raw.trim();
        return filePart.isEmpty() ? null : filePart;
    }

    private static void ensureLoaded() {
        if (initialized) {
            return;
        }
        Context ctx = Utils.getContext();
        if (ctx != null) {
            if (Utils.isCurrentlyOnMainThread()) {
                initialized = true;
                Context appContext = ctx.getApplicationContext();
                Utils.runOnBackgroundThread(() -> {
                    reload(appContext);
                    if (cachedMainTypeface != null) {
                        Utils.runOnMainThread(LyricsPanelInstaller::onCustomFontLoaded);
                    }
                });
            } else {
                reload(ctx);
            }
        }
    }

    /** Reloads the active custom font from disk (or clears caches when reset to default). */
    public static synchronized void reload(@Nullable Context context) {
        initialized = true;
        cachedMainTypeface = null;
        cachedRegularTypeface = null;
        cachedMetadata = null;

        if (context == null) {
            return;
        }
        String storedFileName = storedFileNameFromSetting();
        if (storedFileName == null) {
            return;
        }
        File dir = LyricsDirectories.directory(context, LyricsDirectories.FONT);
        if (dir == null) {
            return;
        }
        File fontFile = new File(dir, storedFileName);
        if (!fontFile.isFile()) {
            Logger.printInfo(() -> "Custom lyrics font file missing, resetting setting: " + fontFile);
            Settings.LYRICS_CUSTOM_FONT.resetToDefault();
            return;
        }

        FontMetadata meta = parseFontMetadata(fontFile);
        if (!meta.valid()) {
            Logger.printInfo(() -> "Custom lyrics font invalid SFNT header, resetting: " + fontFile);
            Settings.LYRICS_CUSTOM_FONT.resetToDefault();
            return;
        }

        Typeface mainTf = buildAndroidTypeface(fontFile, meta, true);
        if (mainTf == null) {
            Logger.printInfo(() -> "Android Typeface could not load custom lyrics font: " + fontFile);
            Settings.LYRICS_CUSTOM_FONT.resetToDefault();
            return;
        }
        Typeface regTf = buildAndroidTypeface(fontFile, meta, false);
        cachedMetadata = meta;
        cachedMainTypeface = mainTf;
        cachedRegularTypeface = regTf != null ? regTf : mainTf;
    }

    @Nullable
    private static Typeface buildAndroidTypeface(File fontFile, FontMetadata meta, boolean boldRole) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int targetWeight = meta.hasWeightAxis()
                        ? (boldRole ? Math.max(700, meta.weight()) : 500)
                        : meta.weight();
                targetWeight = Math.max(100, Math.min(1000, targetWeight));
                Font.Builder fb = new Font.Builder(fontFile)
                        .setWeight(targetWeight)
                        .setSlant(meta.italic()
                                ? FontStyle.FONT_SLANT_ITALIC
                                : FontStyle.FONT_SLANT_UPRIGHT);
                if (meta.hasWeightAxis()) {
                    fb.setFontVariationSettings("'wght' " + targetWeight);
                }
                Font font = fb.build();
                FontFamily family = new FontFamily.Builder(font).build();
                FontStyle style = new FontStyle(
                        targetWeight,
                        meta.italic() ? FontStyle.FONT_SLANT_ITALIC : FontStyle.FONT_SLANT_UPRIGHT);
                return new Typeface.CustomFallbackBuilder(family)
                        .setSystemFallback("sans-serif")
                        .setStyle(style)
                        .build();
            }
            return new Typeface.Builder(fontFile).build();
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not construct custom lyrics Typeface from " + fontFile, ex);
            try {
                return Typeface.createFromFile(fontFile);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /**
     * Copies a picked {@code .ttf} / {@code .otf} document URI into the lyrics font directory,
     * validates both SFNT headers and Android {@link Typeface} decoding, and activates it.
     */
    public static synchronized FontImportResult importFontFile(
            Context context,
            Uri uri,
            @Nullable String pickedDisplayName) {
        File dir = LyricsDirectories.directory(context, LyricsDirectories.FONT);
        if (dir == null || (!dir.exists() && !dir.mkdirs())) {
            return new FontImportResult(false, "");
        }
        String safePicked = pickedDisplayName == null ? "" : pickedDisplayName.trim();
        String lower = safePicked.toLowerCase(Locale.ROOT);
        String ext = lower.endsWith(".otf") ? ".otf" : (lower.endsWith(".ttc") ? ".ttc" : ".ttf");
        File tempFile = new File(dir, TEMP_FONT_NAME);

        try {
            long totalBytes = 0L;
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tempFile)) {
                if (in == null) {
                    return new FontImportResult(false, "");
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    totalBytes += read;
                    if (totalBytes > MAX_FONT_BYTES) {
                        return new FontImportResult(false, "");
                    }
                    out.write(buffer, 0, read);
                }
            }

            FontMetadata meta = parseFontMetadata(tempFile);
            if (!meta.valid()) {
                return new FontImportResult(false, "");
            }
            Typeface testTypeface = buildAndroidTypeface(tempFile, meta, true);
            if (testTypeface == null) {
                return new FontImportResult(false, "");
            }

            String targetName = STORED_FONT_BASENAME + ext;
            File targetFile = new File(dir, targetName);
            Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            File[] existing = dir.listFiles();
            if (existing != null) {
                for (File f : existing) {
                    if (!f.equals(tempFile) && !f.equals(targetFile)) {
                        Utils.deleteQuietly(f);
                    }
                }
            }

            String label = !meta.displayName().isEmpty()
                    ? meta.displayName()
                    : (!safePicked.isEmpty() ? safePicked : targetName);
            label = label.replace('|', ' ').trim();

            Settings.LYRICS_CUSTOM_FONT.save(targetName + "|" + label);
            reload(context);
            LyricsFit.invalidateFontCache();
            return new FontImportResult(true, label);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to import custom lyrics font", ex);
            return new FontImportResult(false, "");
        } finally {
            Utils.deleteQuietly(tempFile);
        }
    }

    /** Deletes the uploaded custom font and restores the default lyrics font. */
    public static synchronized void clearCustomFont(Context context) {
        File dir = LyricsDirectories.directory(context, LyricsDirectories.FONT);
        if (dir != null) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    Utils.deleteQuietly(f);
                }
            }
        }
        Settings.LYRICS_CUSTOM_FONT.resetToDefault();
        reload(context);
        LyricsFit.invalidateFontCache();
    }
}
