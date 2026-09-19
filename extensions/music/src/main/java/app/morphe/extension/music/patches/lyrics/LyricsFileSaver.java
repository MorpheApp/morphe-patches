/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.ResourceUtils;

/**
 * Saves raw lyrics text to the device's Downloads directory via MediaStore.
 * No storage permissions required on API 29+.
 */
public final class LyricsFileSaver {

    private LyricsFileSaver() {
    }

    @Nullable
    public static String save(Context context, TrackInfo track, Lyrics lyrics) {
        String content = lyrics.rawFormat();
        String formatType = lyrics.formatType();

        if (content == null || content.isEmpty()) {
            if (lyrics.lines() == null || lyrics.lines().isEmpty()) {
                return null;
            }

            if ("krc".equals(formatType)) {
                content = rebuildKrc(lyrics.lines());
            } else if ("lrc".equals(formatType)) {
                content = rebuildLrc(lyrics.lines());
            } else if ("lyl".equals(formatType)) {
                content = rebuildLyricifyLines(lyrics.lines());
            } else if ("lys".equals(formatType)) {
                content = rebuildLyricifySyllable(lyrics.lines());
            } else {
                content = rebuildPlainText(lyrics.lines());
                formatType = "txt";
            }
        }

        if (formatType == null || formatType.isEmpty()) {
            formatType = "txt";
        }

        String fileName = sanitizeFileName(track.artist() + " - " + track.title())
                + "." + formatType;

        // MediaStore.Downloads arrived in Android 10 and there is no legacy path here,
        // so saving is unavailable on older releases rather than failing at the field.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }

        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, getMimeType(formatType));
        String directoryName = ResourceUtils.getString("morphe_custom_branding_name_entry_2");
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + directoryName);

        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri insertUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (insertUri == null) {
            return null;
        }

        try (OutputStream out = resolver.openOutputStream(insertUri)) {
            if (out == null) {
                resolver.delete(insertUri, null, null);
                return null;
            }
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return Environment.DIRECTORY_DOWNLOADS + "/" + directoryName + "/" + fileName;
        } catch (Exception ex) {
            resolver.delete(insertUri, null, null);
            return null;
        } finally {
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(insertUri, values, null, null);
        }
    }

    private static String rebuildKrc(List<LyricsLine> lines) {
        StringBuilder sb = new StringBuilder(50 * lines.size());
        for (LyricsLine line : lines) {
            final long lineDuration = line.endTimeMs() - line.startTimeMs();
            sb.append('[').append(line.startTimeMs()).append(',').append(lineDuration).append(']');
            List<Word> words = line.words();
            if (words != null && !words.isEmpty()) {
                for (int i = 0, size = words.size(); i < size; i++) {
                    Word word = words.get(i);
                    final long offset = word.startMs() - line.startTimeMs();
                    final long wordDuration = word.endMs() - word.startMs();
                    sb.append('<').append(offset).append(',').append(wordDuration).append(",0>").append(word.text());
                }
            } else {
                sb.append(line.text());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String rebuildLrc(List<LyricsLine> lines) {
        StringBuilder sb = new StringBuilder(50 * lines.size());
        for (LyricsLine line : lines) {
            final long totalMs = line.startTimeMs();
            final long min = totalMs / 60000;
            final long sec = (totalMs % 60000) / 1000;
            final long ms = totalMs % 1000;
            sb.append('[')
              .append(String.format(Locale.US, "%02d:%02d.%02d", min, sec, ms / 10))
              .append(']')
              .append(line.text())
              .append('\n');
        }
        return sb.toString();
    }

    private static String rebuildLyricifyLines(List<LyricsLine> lines) {
        StringBuilder sb = new StringBuilder();
        sb.append("[type:LyricifyLines]\n");
        for (LyricsLine line : lines) {
            sb.append('[').append(line.startTimeMs())
              .append(',').append(line.endTimeMs())
              .append(']').append(line.text())
              .append('\n');
        }
        return sb.toString();
    }

    private static String rebuildLyricifySyllable(List<LyricsLine> lines) {
        StringBuilder sb = new StringBuilder();
        sb.append("[from:AppleSyllable]\n");
        for (int i = 0; i < lines.size(); i++) {
            LyricsLine line = lines.get(i);
            sb.append('[').append(i + 1).append(']');
            if (line.hasWords()) {
                List<Word> words = line.words();
                for (int j = 0; j < words.size(); j++) {
                    Word word = words.get(j);
                    long durMs = word.endMs() - word.startMs();
                    sb.append(word.text())
                      .append('(').append(word.startMs())
                      .append(',').append(durMs).append(')');
                }
            } else {
                sb.append(line.text());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String rebuildPlainText(List<LyricsLine> lines) {
        StringBuilder sb = new StringBuilder(50 * lines.size());
        for (int i = 0, size = lines.size(); i < size; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i).text());
        }
        return sb.toString();
    }

    private static String sanitizeFileName(String name) {
        // Remove characters illegal in file names on most filesystems.
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
    }

    private static String getMimeType(String formatType) {
        return switch (formatType) {
            case "ttml" -> "application/ttml+xml";
            case "lyricsfile.yaml" -> "text/yaml";
            case "json", "mxm.json", "sp.json", "dzr.json", "ytm.json" -> "application/json";
            case "plain", "txt" -> "text/plain";
            default -> "application/octet-stream";
        };
    }
}
