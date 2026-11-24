/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.utils.requests.CaptionTrackRequest;

/**
 * Pulls the video's caption track (Innertube preferred, direct timedtext as fallback),
 * parses the JSON3 payload into line-level segments, merges those into sentence-sized
 * chunks suitable for TTS, and delegates translation to {@link TranscriptTranslator}
 * when the source language differs from the user's target.
 */
final class TranscriptFetcher {

    /** Language code detected from the video's own caption track. Updated on every successful fetch. */
    static volatile String lastSourceLang = "en";

    /**
     * Fetches and, if needed, translates the transcript. The returned list is immediately
     * usable; when translation spans multiple batches, later batches are published
     * asynchronously through {@code onUpdate} (see {@link TranscriptTranslator#translate}).
     * {@code cancelled} is polled before each background batch so translation of an
     * abandoned video stops early.
     */
    static List<TranscriptSegment> fetch(String videoId, Consumer<List<TranscriptSegment>> onUpdate,
                                         BooleanSupplier cancelled) {
        List<TranscriptSegment> segments = fetchEnglishSegments(videoId);

        if (!segments.isEmpty()) {
            String targetLangCode = VoiceOverTranslationPatch.resolveTargetLang();
            if (isSpokenLanguageDifferent(targetLangCode, lastSourceLang)) {
                segments = TranscriptTranslator.translate(videoId, segments, targetLangCode, onUpdate, cancelled);
            }
        }

        return segments;
    }

    /**
     * @return true if {@code source} needs translation to reach {@code target}. Portuguese
     * is region-sensitive (pt-BR != pt-PT); other languages match on the base subtag.
     */
    public static boolean isSpokenLanguageDifferent(String target, String source) {
        if (target == null || source == null) return true;
        if (target.equalsIgnoreCase(source)) return false;
        if (target.startsWith("pt-")) return true;
        return !VoiceCatalog.getIso639(target).equalsIgnoreCase(VoiceCatalog.getIso639(source));
    }

    private static List<TranscriptSegment> fetchEnglishSegments(String videoId) {
        try {
            CaptionTrackRequest.Track track = findBestTrack(CaptionTrackRequest.fetchCaptions(videoId).tracks());
            if (track != null) {
                String detectedLang = track.languageCode().split("-")[0];
                List<TranscriptSegment> segments = toSegments(
                        CaptionTrackRequest.fetchLines(track.url()), detectedLang);
                if (!segments.isEmpty()) {
                    lastSourceLang = detectedLang;
                    return segments;
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Caption fetch failed, trying direct", ex);
        }

        return fetchDirect(videoId);
    }

    /**
     * Returns the best caption track.
     * Prefers a non-gemini track in the target language; falls back to a non-gemini
     * English track, then the first non-gemini track, then the first available.
     */
    @Nullable
    private static CaptionTrackRequest.Track findBestTrack(List<CaptionTrackRequest.Track> tracks) {
        String targetLang = VoiceCatalog.getIso639(VoiceOverTranslationPatch.resolveTargetLang());
        CaptionTrackRequest.Track firstNonGemini = null;
        CaptionTrackRequest.Track englishTrack = null;

        for (CaptionTrackRequest.Track track : tracks) {
            if (track.gemini()) continue;

            String trackLang = track.languageCode().split("-")[0];
            if (targetLang.equals(trackLang)) return track;
            if (englishTrack == null && "en".equals(trackLang)) englishTrack = track;
            if (firstNonGemini == null) firstNonGemini = track;
        }

        if (englishTrack != null) return englishTrack;
        if (firstNonGemini != null) return firstNonGemini;
        return tracks.isEmpty() ? null : tracks.get(0);
    }

    private static List<TranscriptSegment> fetchDirect(String videoId) {
        for (String srcLang : List.of("en", "en-US", "en-GB")) {
            try {
                String url = "https://www.youtube.com/api/timedtext?v=" + videoId
                        + "&lang=" + srcLang + "&kind=asr";
                List<TranscriptSegment> segments = toSegments(CaptionTrackRequest.fetchLines(url), "en");
                if (!segments.isEmpty()) {
                    lastSourceLang = "en";
                    return segments;
                }
            } catch (Exception ex) {
                final String langFinal = srcLang;
                Logger.printDebug(() -> "Direct caption fetch failed lang: " + langFinal, ex);
            }
        }
        Logger.printDebug(() -> "No captions available for video: " + videoId);
        return new ArrayList<>();
    }

    // Flush a merged sentence when it grows past this size, to keep TTS utterances manageable.
    private static final int MAX_SENTENCE_CHARS = 300;
    // A silence gap longer than this between lines starts a new utterance even mid-sentence.
    private static final long MAX_SENTENCE_GAP_MS = 1_500;
    // Small gaps between segments are closed when they are below this threshold.
    private static final long CLOSE_GAP_THRESHOLD_MS = 2_500;
    // Minimum duration for a merged segment to avoid being skipped by the playback polling.
    private static final long MIN_SEGMENT_DURATION_MS = 2_000;

    private static final Pattern BRACKETS_PATTERN = Pattern.compile("\\[[^]]*]");
    private static final Pattern PARENTHESES_PATTERN = Pattern.compile("\\([^)]*\\)");

    // Heuristics for old ASR tracks that have no punctuation at all.
    // A pause this long is treated as a sentence boundary on its own.
    private static final long UNPUNCTUATED_GAP_MS = 700;
    // A shorter pause counts as a boundary only when the next line starts with a capital.
    private static final long UNPUNCTUATED_SOFT_GAP_MS = 250;
    // Tighter length cap so unpunctuated chunks stay short and re-sync with the video often.
    private static final int MAX_UNPUNCTUATED_CHARS = 200;

    private static List<TranscriptSegment> toSegments(List<CaptionTrackRequest.Line> captionLines,
                                                      String sourceLang) {
        List<TranscriptSegment> lines = new ArrayList<>();
        for (CaptionTrackRequest.Line line : captionLines) {
            String textStr = line.text()
                    .replace(">>", "")
                    .replace("♪", "")
                    .replace("♫", "");

            // Drop sound effect markers such as [Applause] or (music) - they should not be spoken.
            textStr = BRACKETS_PATTERN.matcher(textStr).replaceAll("");
            textStr = PARENTHESES_PATTERN.matcher(textStr).replaceAll("");
            textStr = textStr.trim();

            if (!textStr.isEmpty()) {
                lines.add(new TranscriptSegment(line.startMs(),
                        line.startMs() + line.durationMs(), textStr, sourceLang));
            }
        }

        // dDurationMs is display time: with ASR a line stays on screen while the next
        // line is already spoken, so ranges overlap. Clamp each end to the next start
        // so segments represent actual speech time instead of caption visibility.
        // Small gaps are closed so TTS flows without pauses between segments.
        for (int i = 0, last = lines.size() - 1; i < last; i++) {
            TranscriptSegment cur = lines.get(i);
            TranscriptSegment next = lines.get(i + 1);

            final long curEndMs = cur.endMs;
            final long nextStartMs = next.startMs;
            if (curEndMs > nextStartMs) {
                lines.set(i, new TranscriptSegment(cur.startMs, nextStartMs, cur.text, cur.lang));
            } else if (nextStartMs - curEndMs <= CLOSE_GAP_THRESHOLD_MS) {
                final long mid = (curEndMs + nextStartMs) / 2;
                lines.set(i, new TranscriptSegment(cur.startMs, mid, cur.text, cur.lang));
                lines.set(i + 1, new TranscriptSegment(mid, next.endMs, next.text, next.lang));
            }
        }

        return mergeIntoSentences(lines);
    }

    /**
     * Merges caption lines into sentence-sized segments so TTS speaks whole sentences
     * without pauses at line breaks. For punctuated transcripts a sentence ends on
     * terminal punctuation; old ASR tracks have no punctuation at all, so boundaries
     * are approximated from speech pauses and capitalization instead.
     */
    private static List<TranscriptSegment> mergeIntoSentences(List<TranscriptSegment> lines) {
        final boolean punctuated = detectPunctuation(lines);
        List<TranscriptSegment> sentences = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        long startMs = 0;
        long endMs;
        String sentenceLang = "";

        for (int i = 0, size = lines.size(); i < size; i++) {
            TranscriptSegment line = lines.get(i);
            if (Utils.isEmpty(text)) {
                startMs = line.startMs;
                sentenceLang = line.lang;
            } else {
                text.append(' ');
            }
            text.append(line.text);
            endMs = line.endMs;

            boolean flush;
            if (i == size - 1) {
                flush = true;
            } else {
                final long gap = lines.get(i + 1).startMs - endMs;
                if (punctuated) {
                    flush = endsSentence(text)
                            || text.length() >= MAX_SENTENCE_CHARS
                            || (gap > MAX_SENTENCE_GAP_MS && text.length() > 80);
                } else {
                    flush = gap > UNPUNCTUATED_GAP_MS
                            || (gap > UNPUNCTUATED_SOFT_GAP_MS
                            && startsWithUpperCase(lines.get(i + 1).text))
                            || text.length() >= MAX_UNPUNCTUATED_CHARS;
                }

                if (flush && endMs - startMs < MIN_SEGMENT_DURATION_MS && gap <= 0) {
                    flush = false;
                }
            }

            if (flush) {
                String fullText = text.toString();
                if (i < size - 1) {
                    final int lastEnd = findLastSentenceEnd(fullText);
                    final int fullTextLength = fullText.length();
                    if (lastEnd != -1 && lastEnd < fullTextLength - 1) {
                        String head = fullText.substring(0, lastEnd + 1).trim();
                        String tail = fullText.substring(lastEnd + 1).trim();

                        final long splitMs = startMs + (long) ((endMs - startMs)
                                * ((double) head.length() / fullTextLength));
                        sentences.add(new TranscriptSegment(startMs, splitMs, head, sentenceLang));

                        text.setLength(0);
                        text.append(tail);
                        startMs = splitMs;
                        continue;
                    }
                }
                sentences.add(new TranscriptSegment(startMs, endMs, fullText, sentenceLang));
                text.setLength(0);
            }
        }
        return sentences;
    }

    /**
     * Returns true when a meaningful share of lines contains terminal punctuation.
     * Old auto-generated tracks contain none, so punctuation can't be trusted there
     * as a sentence boundary signal.
     */
    private static boolean detectPunctuation(List<TranscriptSegment> lines) {
        int punctuatedLines = 0;
        for (TranscriptSegment line : lines) {
            String t = line.text;
            for (int i = 0, len = t.length(); i < len; i++) {
                final char c = t.charAt(i);
                if (c == '.' || c == '!' || c == '?') {
                    punctuatedLines++;
                    break;
                }
            }
        }
        // At least ~10% of lines must carry punctuation - the occasional "$5.99"
        // in an otherwise unpunctuated track should not flip the mode.
        return punctuatedLines * 10 >= lines.size();
    }

    private static boolean startsWithUpperCase(String text) {
        for (int i = 0, len = text.length(); i < len; i++) {
            final char c = text.charAt(i);
            if (Character.isLetter(c)) return Character.isUpperCase(c);
        }
        return false;
    }

    private static boolean endsSentence(CharSequence text) {
        if (Utils.isEmpty(text)) return false;
        final char c = text.charAt(text.length() - 1);
        if (c != '.' && c != '!' && c != '?' && c != '…') return false;

        // Check for common abbreviations that end with a period but don't end a sentence.
        if (c == '.') {
            final String s = text.toString();
            if (s.endsWith("Mr.") || s.endsWith("Ms.") || s.endsWith("Dr.") || s.endsWith("St.")
                    || s.endsWith("Inc.") || s.endsWith("Ltd.") || s.endsWith("Jr.")
                    || s.endsWith("Sr.") || s.endsWith("vs.")) {
                return false;
            }
            // Heuristic for initials: "A.", "B.", etc.
            final int sLength = s.length();
            return sLength < 3 || s.charAt(sLength - 2) != ' '
                    || !Character.isUpperCase(s.charAt(sLength - 3));
        }
        return true;
    }

    private static int findLastSentenceEnd(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                if (endsSentence(text.substring(0, i + 1))) {
                    return i;
                }
            }
        }
        return -1;
    }
}
