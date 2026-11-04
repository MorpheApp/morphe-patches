/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4376
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Keeps native styled cues and gives each language its own window identifiers. */
final class NativeCaptionPair {
    final UploadedCaptionPair tracks;
    @Nullable private final StableCaptionBlocks.Timeline source;
    private final List<Map<Integer, Integer>> ids = List.of(new HashMap<>(), new HashMap<>());

    NativeCaptionPair(UploadedCaptionPair tracks, @Nullable StableCaptionBlocks.Timeline source) {
        this.tracks = tracks;
        this.source = source;
    }

    synchronized List<?> at(long time, StableCaptionBlocks.State state) {
        List<Object> cues = new ArrayList<>();
        append(cues, tracks.first(), time, state, 0);
        append(cues, tracks.second(), time, state, 1);
        CaptionSession translation = state.translation;
        StableCaptionBlockPolicy.Block block = translation != null && translation.isActive()
                ? StableCaptionBlockPolicy.at(translation.source, time) : null;
        if (source != null && block != null) {
            for (Object item : source.patch_getOriginalCaptions(time)) {
                if (!(item instanceof StableCaptionBlocks.Cue cue) || !cue.patch_isCaptionVisible()) continue;
                CharSequence text = cue.patch_getOriginalCaptionVisible();
                if (text == null || text.length() == 0) continue;
                int language = tracks.first() == null ? 0 : 1;
                Object copy = cue.patch_copyBlockFrame(
                        new StableCaptionBlocks.Frame(state, time, block.start, language, false));
                if (copy != null) cues.add(copy);
                break;
            }
        }
        return cues;
    }

    private void append(List<Object> cues, @Nullable StableCaptionBlocks.Timeline timeline, long time,
                        StableCaptionBlocks.State state, int language) {
        if (timeline == null) return;
        Map<Integer, Integer> mapping = ids.get(language);
        for (Object item : timeline.patch_getOriginalCaptions(time)) {
            if (!(item instanceof StableCaptionBlocks.Cue cue)) continue;
            int id = mapping.computeIfAbsent(cue.patch_getCaptionId(), ignored -> mapping.size() * 2 + language);
            Object copy = cue.patch_copyBlockFrame(new StableCaptionBlocks.Frame(state, time, id, language, true));
            if (copy != null) cues.add(copy);
        }
    }
    /** The native windows already show uploaded slots; the overlay only draws missing slots. */
    UploadedCaptionPair.Frame fallback(CaptionSession.Snapshot translated) {
        return new UploadedCaptionPair.Frame(tracks.first() == null ? translated.primary : "",
                tracks.second() == null ? translated.secondary : "", translated.start, translated.start);
    }
}
