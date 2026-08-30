/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class VideoFormat {

    /**
     * Interface to use obfuscated methods.
     */
    public interface FormatInterface {
        // Methods are added during patching.
        String patch_getMimeType();
        int patch_getWidth();
        int patch_getHeight();
    }

    /**
     * Injection point.
     */
    public static List<FormatInterface> hookAdaptiveFormats(@Nullable String videoId, @NonNull List<FormatInterface> adaptiveFormats) {
        return Utils.isNotEmpty(videoId) && !"zzzzzzzzzzz".equals(videoId) && !adaptiveFormats.isEmpty()
                ? hookAdaptiveFormats(adaptiveFormats)
                : adaptiveFormats;
    }

    private static List<FormatInterface> hookAdaptiveFormats(List<FormatInterface> adaptiveFormats) {
        // Code added during patching.
        return adaptiveFormats;
    }
}
