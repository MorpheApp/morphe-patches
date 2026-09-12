/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
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
            } else {
                content = rebuildPlainText(lyrics.lines());
                formatType = "txt";
            }
        }
        
        if (formatType == null || formatType.isEmpty()) {
            formatType = "txt";
        }

        final String fileName = sanitizeFileName(track.artist() + " - " + track.title())
                + "." + formatType;

        final ContentResolver resolver = context.getContentResolver();
        final ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, getMimeType(formatType));
        final String directoryName = ResourceUtils.getString("morphe_custom_branding_name_entry_2");
        values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + directoryName);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Downloads.IS_PENDING, 1);
        }

        final Uri insertUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(insertUri, values, null, null);
            }
        }
    }

    private static String rebuildKrc(List<LyricsLine> lines) {
        StringBuilder sb = new StringBuilder();
        for (LyricsLine line : lines) {
            long lineDuration = line.endTimeMs() - line.startTimeMs();
            sb.append('[').append(line.startTimeMs()).append(',').append(lineDuration).append(']');
            if (line.words() != null && !line.words().isEmpty()) {
                for (int i = 0; i < line.words().size(); i++) {
                    Word word = line.words().get(i);
                    long offset = word.startMs() - line.startTimeMs();
                    long wordDuration = word.endMs() - word.startMs();
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
        StringBuilder sb = new StringBuilder();
        for (LyricsLine line : lines) {
            long totalMs = line.startTimeMs();
            long min = totalMs / 60000;
            long sec = (totalMs % 60000) / 1000;
            long ms = totalMs % 1000;
            sb.append('[')
              .append(String.format(Locale.US, "%02d:%02d.%02d", min, sec, ms / 10))
              .append(']')
              .append(line.text())
              .append('\n');
        }
        return sb.toString();
    }

    private static String rebuildPlainText(List<LyricsLine> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
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
        switch (formatType) {
            case "lrc":
                return "application/octet-stream";
            case "krc":
                return "application/octet-stream";
            case "yrc":
                return "application/octet-stream";
            case "qrc":
                return "application/octet-stream";
            case "ttml":
                return "application/ttml+xml";
            case "lyricsfile.yaml":
                return "text/yaml";
            case "json":
                return "application/json";
            case "json3":
                return "application/octet-stream";
            case "mxm.json":
                return "application/json";
            case "sp.json":
                return "application/json";
            case "plain":
                return "text/plain";
            case "txt":
                return "text/plain";
            default:
                return "application/octet-stream";
        }
    }
}