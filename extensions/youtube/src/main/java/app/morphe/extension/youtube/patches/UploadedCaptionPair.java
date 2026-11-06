/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4376
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Native timelines of the uploaded tracks of the primary and the secondary language.
 * A language without an uploaded track uses the fallback text, such as a translation.
 */
record UploadedCaptionPair(@Nullable StableCaptionBlocks.Timeline first,
                           @Nullable StableCaptionBlocks.Timeline second) {
    /**
     * @param firstKey  Start of the shown caption of the primary language. Changes when the caption changes.
     * @param secondKey Start of the shown caption of the secondary language.
     */
    record Frame(String primary, String secondary, long firstKey, long secondKey) {}

    Frame at(long time, String fallbackFirst, String fallbackSecond, long fallbackKey) {
        String a = first == null ? fallbackFirst : text(first, time);
        String b = second == null ? fallbackSecond : text(second, time);
        return new Frame(a, b, first == null ? fallbackKey : key(first, time, a),
                second == null ? fallbackKey : key(second, time, b));
    }

    private static String text(StableCaptionBlocks.Timeline timeline, long time) {
        StringBuilder text = new StringBuilder();
        for (Object item : timeline.patch_getOriginalCaptions(time)) {
            if (item instanceof StableCaptionBlocks.Cue cue) {
                CharSequence value = cue.patch_getOriginalCaptionVisible();
                if (value != null) text.append(value).append(' ');
            }
        }
        return text.toString().replaceAll("\\s+", " ").trim();
    }

    private static long key(StableCaptionBlocks.Timeline timeline, long time, String text) {
        if (text.isEmpty()) return Long.MIN_VALUE;
        List<Long> events = timeline.patch_getCaptionEventTimes();
        int index = Collections.binarySearch(events, time);
        if (index < 0) index = -index - 2;
        return index < 0 ? Long.MIN_VALUE : events.get(index);
    }
}
