/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import androidx.annotation.Nullable;

/**
 * Video id helpers shared by the VideoInformation classes of YouTube and YouTube Music.
 */
public abstract class BaseVideoInformation {

    /**
     * Video ids are 11 characters of the URL safe Base64 alphabet.
     */
    public static final int VIDEO_ID_LENGTH = 11;

    /**
     * @return If the text is a video id.
     */
    public static boolean isVideoId(@Nullable String text) {
        if (text == null || text.length() != VIDEO_ID_LENGTH) {
            return false;
        }

        for (int i = 0; i < VIDEO_ID_LENGTH; i++) {
            if (!isVideoIdCharacter(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return If the {@link #VIDEO_ID_LENGTH} bytes at the start index are a video id.
     *         The bytes after the video id are not checked.
     */
    public static boolean isVideoId(byte[] bytes, int start) {
        if (start < 0 || start > bytes.length - VIDEO_ID_LENGTH) {
            return false;
        }

        for (int i = start, end = start + VIDEO_ID_LENGTH; i < end; i++) {
            if (!isVideoIdCharacter(bytes[i])) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean isVideoIdCharacter(int c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '-' || c == '_';
    }
}
