/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.parsers.CharactersConverter;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;

/**
 * Drops the lines a lyrics lookup should not show: the credit block a provider pastes at the
 * end, and whatever {@link LyricsFilterDefaults#LYRICS_TEXT_FILTER} names.
 *
 * <p>Every method is static and holds no state beyond the variant cache, so filtering the
 * same lyrics twice costs nothing beyond re-running the rules. Metadata itself is cleaned
 * separately by {@link MetadataCleaner}.
 */
public final class LyricsFilter {

    private LyricsFilter() {
    }

    private static final int MAX_ARTIST_SONG_LINE_LENGTH = 80;

    private static final Pattern ARTIST_SONG_PATTERN =
            Pattern.compile("^[^\\-]+\\s*-\\s*[^\\-]+$");

    private static final int CREDIT_LINE_GAP_TOLERANCE = 3;

    @Nullable
    private static volatile String cachedCreditMarkers;
    @Nullable
    private static volatile List<String> cachedCreditVariants;

    public static Lyrics filterCreditLines(Lyrics lyrics, TrackInfo track) {
        if (lyrics == null || lyrics == Lyrics.NOT_FOUND || lyrics.isEmpty()) {
            return lyrics;
        }

        List<LyricsLine> original = lyrics.lines();
        int size = original.size();

        boolean[] isCredit = new boolean[size];
        for (int i = 0; i < size; i++) {
            String text = original.get(i).text().trim();
            isCredit[i] = isCreditLine(text, track);
        }

        int startBlockEnd = -1;
        int consecutiveNonCredit = 0;
        for (int i = 0; i < size; i++) {
            if (isCredit[i]) {
                startBlockEnd = i;
                consecutiveNonCredit = 0;
            } else {
                if (++consecutiveNonCredit > CREDIT_LINE_GAP_TOLERANCE) {
                    break;
                }
            }
        }

        int endBlockStart = size;
        consecutiveNonCredit = 0;
        for (int i = size - 1; i >= 0; i--) {
            if (isCredit[i]) {
                endBlockStart = i;
                consecutiveNonCredit = 0;
            } else {
                if (++consecutiveNonCredit > CREDIT_LINE_GAP_TOLERANCE) {
                    break;
                }
            }
        }

        boolean filterFirstLine = false;
        if (startBlockEnd >= 1 && !isCredit[0]) {
            filterFirstLine = true;
            for (int i = 1; i <= startBlockEnd; i++) {
                if (!isCredit[i]) {
                    filterFirstLine = false;
                    break;
                }
            }
        }

        boolean[] kept = new boolean[size];
        List<String> creditLines = new ArrayList<>();
        List<LyricsLine> filteredLines = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            LyricsLine line = original.get(i);
            String text = line.text().trim();
            final boolean inStartBlock = i <= startBlockEnd;
            final boolean inEndBlock = i >= endBlockStart;
            final boolean shouldFilter = (inStartBlock || inEndBlock) && isCredit[i]
                    || (inStartBlock && i == 0 && filterFirstLine);

            if (shouldFilter) {
                if (!text.isEmpty()) {
                    creditLines.add(text);
                }
            } else {
                kept[i] = true;
                filteredLines.add(line);
            }
        }

        if (filteredLines.size() == size) {
            return lyrics;
        }

        List<LyricsLine> romanization = filterAlignedList(lyrics.romanization(), kept);
        Map<String, List<LyricsLine>> translations = filterAlignedMap(lyrics.translations(), kept);
        Map<String, List<LyricsLine>> romanizations = filterAlignedMap(lyrics.romanizations(), kept);

        List<String> songwriters = new ArrayList<>();
        for (String cl : creditLines) {
            if (!songwriters.contains(cl)) {
                songwriters.add(cl);
            }
        }
        List<String> existingSongwriters = lyrics.songwriters();
        if (existingSongwriters != null) {
            for (String sw : existingSongwriters) {
                if (!songwriters.contains(sw)) {
                    songwriters.add(sw);
                }
            }
        }

        return new Lyrics(filteredLines, lyrics.providerName(), lyrics.synced(),
                romanization, translations, romanizations,
                songwriters, lyrics.rawFormat(), lyrics.formatType(), lyrics.sourceUrl(),
                lyrics.about());
    }

    private static List<LyricsLine> filterAlignedList(List<LyricsLine> aligned, boolean[] kept) {
        if (aligned == null || aligned.isEmpty()) {
            return aligned;
        }
        List<LyricsLine> result = new ArrayList<>(aligned.size());
        for (int i = 0; i < aligned.size() && i < kept.length; i++) {
            if (kept[i]) {
                result.add(aligned.get(i));
            }
        }
        return result;
    }

    private static Map<String, List<LyricsLine>> filterAlignedMap(
            Map<String, List<LyricsLine>> map, boolean[] kept) {
        if (map == null || map.isEmpty()) {
            return map;
        }
        Map<String, List<LyricsLine>> result = new HashMap<>(2 * map.size());
        for (Map.Entry<String, List<LyricsLine>> entry : map.entrySet()) {
            result.put(entry.getKey(), filterAlignedList(entry.getValue(), kept));
        }
        return result;
    }

    private static int countSlashes(String text) {
        int count = 0;
        for (int i = 0, length = text.length(); i < length; i++) {
            if (text.charAt(i) == '/') {
                count++;
            }
        }
        return count;
    }

    private static boolean isCreditLine(String text, TrackInfo track) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        text = text.replaceAll("\\s{2,}", " ");
        if (text.length() <= MAX_ARTIST_SONG_LINE_LENGTH
                && ARTIST_SONG_PATTERN.matcher(text.trim()).matches()
                && isArtistSongLine(text, track)) {
            return true;
        }
        if (countSlashes(text) > 5) {
            return true;
        }
        String setting = MetadataCleaner.resolveSetting(LyricsFilterDefaults.LYRICS_CREDIT_LINE_REGEX.get());
        if (setting.trim().isEmpty()) {
            return false;
        }

        String normalized = CharactersConverter.normalize(text);

        String[] markers = setting.split(",");
        List<String> allVariants = getCachedCreditVariants(markers);

        for (String variant : allVariants) {
            if (variant.length() >= 2 && normalized.startsWith(variant)) {
                int end = variant.length();
                if (end >= normalized.length()
                        || isCreditLabelBoundary(normalized, end, allVariants, variant)) {
                    return true;
                }
            }
        }

        String normalizedWithSpaces = normalized;

        normalized = normalized
                .replace('|', ':')
                .replace('｜', ':')
                .replace('—', ':')
                .replace('－', ':')
                .replace(';', ':')
                .replace('；', ':')
                .replace(',', ':')
                .replace('，', ':')
                .replace('~', ':')
                .replace('～', ':')
                .replace(' ', ':')
                .replace('：', ':')
                .replace('·', ':')
                .replace('@', ':')
                .replace('/', ':')
                .replace('\\', ':')
                .replace('&', ':')
                .replaceAll("\\s+:", ":");

        int sepIdx = normalized.indexOf(':');
        if (sepIdx >= 0) {
            if (normalized.substring(sepIdx + 1).trim().isEmpty()) {
                return false;
            }
        }
        String beforeSep = sepIdx >= 0 ? normalized.substring(0, sepIdx) : normalized;

        boolean hasRealSeparator = hasRealSeparator(sepIdx, normalizedWithSpaces);

        for (String variant : allVariants) {
            if (variant.isEmpty()) {
                continue;
            }
            if (hasRealSeparator && beforeSep.equals(variant)) {
                return true;
            }
            if (sepIdx >= 0 && beforeSep.endsWith(variant)) {
                int startIdx = beforeSep.length() - variant.length();
                if (startIdx > 0) {
                    char prev = beforeSep.charAt(startIdx - 1);
                    if (!Character.isLetterOrDigit(prev) && prev != '\'') {
                        return true;
                    }
                }
            }
            if (variant.length() >= 2 && LyricsRequests.isCjk(variant.charAt(0))
                    && beforeSep.startsWith(variant)) {
                int end = variant.length();
                if (end >= beforeSep.length() || isCreditLabelBoundary(beforeSep, end, allVariants, variant)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasRealSeparator(int sepIdx, String normalizedWithSpaces) {
        if (sepIdx < 0) return false;
        int realSepIdx = -1;
        int spaceSepIdx = normalizedWithSpaces.indexOf(' ');
        int colonSepIdx = normalizedWithSpaces.indexOf(':');
        if (colonSepIdx >= 0) {
            realSepIdx = colonSepIdx;
        }
        for (char c : new char[]{'|', '｜', '—', '－', '-', ';', '；', ',', '，', '~', '～',
                '：', '·', '@', '/', '\\', '&'}) {
            int idx = normalizedWithSpaces.indexOf(c);
            if (idx >= 0 && (realSepIdx < 0 || idx < realSepIdx)) {
                realSepIdx = idx;
            }
        }
        return realSepIdx >= 0 && (spaceSepIdx < 0 || realSepIdx < spaceSepIdx);
    }

    private static List<String> getCachedCreditVariants(String[] markers) {
        String markerKey = String.join(",", markers);
        List<String> cached = cachedCreditVariants;
        if (cached != null && markerKey.equals(cachedCreditMarkers)) {
            return cached;
        }
        Set<String> allVariantsSet = new LinkedHashSet<>();
        for (String marker : markers) {
            marker = marker.trim();
            if (!marker.isEmpty()) {
                allVariantsSet.addAll(CharactersConverter.variants(marker));
            }
        }
        List<String> result = new ArrayList<>(allVariantsSet);
        cachedCreditMarkers = markerKey;
        cachedCreditVariants = result;
        return result;
    }

    private static boolean isCreditLabelBoundary(String text, int pos, List<String> allVariants, String currentVariant) {
        if (pos >= text.length()) {
            return true;
        }
        char c = text.charAt(pos);
        if (c == '/' || c == '&' || c == '|'
                || c == '·' || c == '~' || c == '@' || c == '\\'
                || c == '－' || c == '—' || c == '-' || c == ':'
                || c == '、' || c == '；' || c == '，' || c == ','
                || c == ';' || c == '＋' || c == '+') {
            if (c == '-' || c == '－' || c == '—') {
                char before = pos > 0 ? text.charAt(pos - 1) : 0;
                char after = pos + 1 < text.length() ? text.charAt(pos + 1) : 0;
                if (isLatinLetterOrDigit(before) && isLatinLetterOrDigit(after)) {
                    return false;
                }
                return !text.regionMatches(true, pos + 1, currentVariant, 0, currentVariant.length());
            }
            return true;
        }
        if (Character.isWhitespace(c)) {
            if (pos > 0 && (LyricsRequests.isCjk(text.charAt(pos - 1))
                    || currentVariant.indexOf(' ') >= 0)) {
                return true;
            }
        }
        if (c == '和' || c == '与' || c == '及') {
            return true;
        }
        if (pos > 0) {
            char prev = text.charAt(pos - 1);
            if (LyricsRequests.isCjk(prev) != LyricsRequests.isCjk(c) && Character.isLetterOrDigit(c)) {
                return true;
            }
        }
        String remainder = text.substring(pos);
        for (String v : allVariants) {
            if (v.equals(currentVariant)) {
                continue;
            }
            if (v.length() >= 2 && remainder.startsWith(v)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLatinLetterOrDigit(char c) {
        return Character.isLetterOrDigit(c) && !LyricsRequests.isCjk(c);
    }

    private static boolean isArtistSongLine(String text, TrackInfo track) {
        if (track == null) {
            return false;
        }
        String artist = track.artist();
        String title = track.title();
        if (artist == null || artist.trim().isEmpty() || title == null || title.trim().isEmpty()) {
            return false;
        }
        int dashIdx = text.indexOf('-');
        if (dashIdx <= 0 || dashIdx >= text.length() - 1) {
            return false;
        }
        List<String> artistVariants = CharactersConverter.variants(artist.trim());
        if (artistVariants.isEmpty()) {
            return false;
        }
        List<String> titleVariants = CharactersConverter.variants(title.trim());
        if (titleVariants.isEmpty()) {
            return false;
        }
        List<String> leftVariants = CharactersConverter.variants(text.substring(0, dashIdx).trim());
        List<String> rightVariants = CharactersConverter.variants(text.substring(dashIdx + 1).trim());
        return (containsAnyVariant(leftVariants, artistVariants) && containsAnyVariant(rightVariants, titleVariants))
                || (containsAnyVariant(leftVariants, titleVariants) && containsAnyVariant(rightVariants, artistVariants));
    }

    private static boolean containsAnyVariant(List<String> haystacks, List<String> needles) {
        for (String haystack : haystacks) {
            for (String needle : needles) {
                if (haystack.contains(needle) || needle.contains(haystack)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Applies {@link LyricsFilterDefaults#LYRICS_TEXT_FILTER} to the original lyrics text only.
     * Translations and romanizations are not run through the filter, but when the filter
     * drops a line they drop with it so a translation stays under the line it belongs to.
     * A line the filter does not fire on keeps its provider text rather than a normalized
     * copy, so fullwidth punctuation is not folded to ASCII.
     */
    public static Lyrics filterLyricsText(Lyrics lyrics) {
        if (lyrics == null || lyrics == Lyrics.NOT_FOUND) {
            return lyrics;
        }
        String filter = MetadataCleaner.resolveSetting(LyricsFilterDefaults.LYRICS_TEXT_FILTER.get());
        if (filter.trim().isEmpty()) {
            return lyrics;
        }

        List<LyricsLine> original = lyrics.lines();
        List<LyricsLine> filtered = new ArrayList<>(original.size());
        boolean[] kept = new boolean[original.size()];
        boolean anyDropped = false;
        for (int i = 0; i < original.size(); i++) {
            LyricsLine line = original.get(i);
            String text = MetadataCleaner.applyRegexPreserveOriginal(line.text(), filter)
                    .trim().replaceAll("\\s+", " ");
            if (text.isEmpty()) {
                anyDropped = true;
                continue;
            }
            kept[i] = true;
            if (text.equals(line.text().trim().replaceAll("\\s+", " "))) {
                filtered.add(line);
                continue;
            }
            if (line.hasWords()) {
                List<Word> words = new ArrayList<>(line.words().size());
                for (Word w : line.words()) {
                    String wt = MetadataCleaner.applyRegexPreserveOriginal(w.text(), filter)
                            .trim().replaceAll("\\s+", " ");
                    if (!wt.isEmpty()) {
                        words.add(new Word(w.startMs(), w.endMs(), wt,
                                w.romaji(), w.endsWithSpace()));
                    }
                }
                // Only the section label is carried over. The shorter constructors this replaces also reset
                // the end time and the agent/duet/background flags, and keeping that behavior means
                // a text filter cannot change what an export reports for the line.
                filtered.add(new LyricsLine(line.startTimeMs(), LyricsLine.NO_TIME, text,
                        words, null, false, false, line.songPart()));
            } else {
                filtered.add(new LyricsLine(line.startTimeMs(), LyricsLine.NO_TIME, text,
                        List.of(), null, false, false, line.songPart()));
            }
        }

        if (!anyDropped) {
            return lyrics;
        }

        // The aligned lists drop with the lyrics so a translation stays under the line it
        // belongs to, rather than being shifted up onto the next one.
        return new Lyrics(filtered, lyrics.providerName(), lyrics.synced(),
                filterAlignedList(lyrics.romanization(), kept),
                filterAlignedMap(lyrics.translations(), kept),
                filterAlignedMap(lyrics.romanizations(), kept),
                lyrics.songwriters(), lyrics.rawFormat(), lyrics.formatType(),
                lyrics.sourceUrl(), lyrics.about());
    }
}
