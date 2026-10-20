/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.utils;

import android.os.Environment;

import androidx.annotation.Nullable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Download folders and the subfolder and file name pattern.
 * <p>
 * The part of a pattern after the last separator is the file name, and the part before it are subfolders,
 * such as "{subreddit}/{author}_{date}". A pattern that ends with a separator keeps Reddit's file names.
 */
public final class DownloadPathPattern {

    public static final String TOKEN_ACCOUNT = "{account}";
    public static final String TOKEN_SUBREDDIT = "{subreddit}";
    public static final String TOKEN_AUTHOR = "{author}";
    public static final String TOKEN_TYPE = "{type}";
    public static final String TOKEN_ORIGINAL = "{original}";
    public static final String TOKEN_DATE = "{date}";
    public static final String TOKEN_TIME = "{time}";
    public static final String[] TOKENS = {
            TOKEN_ACCOUNT, TOKEN_SUBREDDIT, TOKEN_AUTHOR, TOKEN_TYPE, TOKEN_ORIGINAL, TOKEN_DATE, TOKEN_TIME
    };

    private static final String UNKNOWN_TOKEN_VALUE = "unknown";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HHmmss", Locale.US);

    /**
     * Shared folders where the media store allows both images and videos without storage permissions.
     */
    public static final String[] ROOT_FOLDERS = {
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_DCIM
    };

    /**
     * Null values are replaced with {@link #UNKNOWN_TOKEN_VALUE}.
     */
    public record Values(@Nullable String account, @Nullable String subreddit, @Nullable String author,
                         @Nullable String type, @Nullable String original, LocalDateTime time) {
    }

    /**
     * @param subfolder Empty if the pattern has no subfolder.
     * @param fileName  Empty if the pattern keeps Reddit's file names. It has no extension.
     */
    public record Expanded(String subfolder, String fileName) {
    }

    private DownloadPathPattern() {
    }

    public static Expanded expand(String pattern, Values values) {
        final int separatorIndex = pattern.lastIndexOf('/');
        return new Expanded(
                expandPath(pattern.substring(0, Math.max(separatorIndex, 0)), values),
                expandFileName(pattern.substring(separatorIndex + 1), values)
        );
    }

    /**
     * @return Reddit's file name for a time, without an extension.
     */
    public static String getRedditFileName(LocalDateTime time) {
        return "RDT_" + time.format(DATE_FORMAT) + "_" + time.format(TIME_FORMAT);
    }

    /**
     * @return The folder in a root folder with normalized separators and the system's root folder name,
     *         or null if it's not in one of the {@link #ROOT_FOLDERS}.
     */
    @Nullable
    public static String normalizeFolder(String folder) {
        StringBuilder normalized = new StringBuilder();
        for (String segment : folder.split("/")) {
            segment = sanitizeName(segment);
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) return null;

            if (normalized.length() == 0) {
                segment = getRootFolder(segment);
                if (segment == null) return null;
            } else {
                normalized.append('/');
            }

            normalized.append(segment);
        }

        return normalized.length() == 0 ? null : normalized.toString();
    }

    /**
     * Replaces characters that file systems of shared storage don't allow.
     */
    private static String sanitizeName(String name) {
        return name.replaceAll("[\\\\:*?\"<>|\\p{Cntrl}]", "_").trim();
    }

    private static String expandPath(String pattern, Values values) {
        StringBuilder path = new StringBuilder();
        for (String segment : expandTokens(pattern, values).split("/")) {
            segment = sanitizeName(segment);
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) continue;

            if (path.length() > 0) path.append('/');
            path.append(segment);
        }
        return path.toString();
    }

    private static String expandFileName(String pattern, Values values) {
        String fileName = sanitizeName(expandTokens(pattern, values).replace('/', '_'));
        return fileName.equals(".") || fileName.equals("..") ? "" : fileName;
    }

    private static String expandTokens(String pattern, Values values) {
        return pattern
                .replace(TOKEN_ACCOUNT, getTokenValue(values.account))
                .replace(TOKEN_SUBREDDIT, getTokenValue(values.subreddit))
                .replace(TOKEN_AUTHOR, getTokenValue(values.author))
                .replace(TOKEN_TYPE, getTokenValue(values.type))
                .replace(TOKEN_ORIGINAL, getTokenValue(values.original))
                .replace(TOKEN_DATE, values.time.format(DATE_FORMAT))
                .replace(TOKEN_TIME, values.time.format(TIME_FORMAT));
    }

    private static String getTokenValue(@Nullable String value) {
        if (value == null || value.isEmpty()) return UNKNOWN_TOKEN_VALUE;
        // Token values must not create subfolders.
        return value.replace('/', '_');
    }

    @Nullable
    private static String getRootFolder(String segment) {
        for (String root : ROOT_FOLDERS) {
            if (root.equalsIgnoreCase(segment)) {
                return root;
            }
        }
        return null;
    }
}
