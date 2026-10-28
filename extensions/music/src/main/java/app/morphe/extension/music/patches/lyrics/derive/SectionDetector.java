/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Asks the AI which part of the song each lyric line belongs to, for lyrics that ship without any
 * section of their own. A label the provider published beats one inferred from the text, so only
 * lyrics that carry none at all are asked about.
 *
 * <p>The answer arrives one entry per line, with only the line opening a section named, so the
 * list maps straight onto {@link LyricsLine#songPart()}.
 */
public final class SectionDetector {

    private static final ExecutorService executor = Executors.newCachedThreadPool();

    private static final String SKIP = "SKIP";

    public interface Callback {
        void onSections(@Nullable List<String> sections);
    }

    private SectionDetector() {
    }

    public static boolean needsDetection(@Nullable Lyrics lyrics) {
        if (lyrics == null || lyrics.isEmpty() || lyrics == Lyrics.NOT_FOUND) {
            return false;
        }
        for (LyricsLine line : lyrics.lines()) {
            String part = line.songPart();
            if (part != null && !part.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static void detect(TrackInfo track, Lyrics lyrics, BooleanSupplier stillWanted,
            Callback callback) {
        List<LyricsLine> lines = lyrics.lines();
        List<String> texts = new ArrayList<>(lines.size());
        for (LyricsLine line : lines) {
            texts.add(line.text());
        }
        String source = lyrics.providerName();

        executor.execute(() -> {
            try {
                if (!stillWanted.getAsBoolean()) {
                    Utils.runOnMainThread(() -> callback.onSections(null));
                    return;
                }
                List<String> cached = LyricsCache.getSectionAI(track, source, texts);
                if (cached != null) {
                    List<String> result = cached;
                    Utils.runOnMainThread(() -> callback.onSections(result));
                    return;
                }

                String baseUrl = Settings.LYRICS_AI_BASE_URL.get();
                String apiToken = Settings.LYRICS_AI_API_TOKEN.get();
                String model = Settings.LYRICS_AI_MODEL.get();
                List<String> detected = aiDetect(texts, track.title(), track.artist(),
                        baseUrl, apiToken, model);
                if (detected != null) {
                    LyricsCache.putSectionAI(track, source, texts, detected);
                }
                List<String> result = detected;
                Utils.runOnMainThread(() -> callback.onSections(result));
            } catch (Exception ex) {
                Logger.printException(() -> "detect failure", ex);
                Utils.runOnMainThread(() -> callback.onSections(null));
            }
        });
    }

    @Nullable
    private static List<String> aiDetect(List<String> lines, String title, String artist,
            String baseUrl, String apiToken, String model) {
        String prompt = OpenAIClient.renderPrompt(Settings.LYRICS_AI_SECTION_PROMPT.get(),
                "section", "", title, artist, lines);
        return OpenAIClient.mapLines(baseUrl, apiToken, model, prompt, lines);
    }

    /** Only the lines the AI named keep a label, which is how the view already reads a section. */
    @Nullable
    public static Lyrics applySections(Lyrics lyrics, @Nullable List<String> sections) {
        if (sections == null) {
            return null;
        }
        List<LyricsLine> lines = lyrics.lines();
        List<LyricsLine> out = new ArrayList<>(lines.size());
        boolean changed = false;
        for (int i = 0; i < lines.size(); i++) {
            LyricsLine line = lines.get(i);
            String label = getLabel(sections, i);
            if (Objects.equals(label, line.songPart())) {
                out.add(line);
                continue;
            }
            changed = true;
            out.add(new LyricsLine(line.startTimeMs(), line.endTimeMs(), line.text(),
                    line.words(), line.agentId(), line.isDuet(), line.isBG(), label));
        }
        if (!changed) {
            return null;
        }
        return new Lyrics(out, lyrics.providerName(), lyrics.synced(),
                lyrics.romanization(), lyrics.translations(), lyrics.romanizations(),
                lyrics.songwriters(), lyrics.rawFormat(), lyrics.formatType(),
                lyrics.sourceUrl(), lyrics.about());
    }

    @Nullable
    private static String getLabel(@NonNull List<String> sections, int i) {
        String label = i < sections.size() ? sections.get(i) : null;
        if (label != null) {
            label = label.trim();
            // mapLines already turns a skipped line into an empty entry; SKIP is accepted here
            // as well so an answer that reached this method another way cannot be read as a
            // section literally named "SKIP".
            if (label.isEmpty() || SKIP.equalsIgnoreCase(label)) {
                label = null;
            }
        }
        return label;
    }
}
