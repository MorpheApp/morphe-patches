/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.parsers;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.LyricsPresentation;
import app.morphe.extension.music.patches.lyrics.model.CreditInfo;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.model.LyricsTextFormatter;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.settings.Settings;

/**
 * Writes lyrics as Apple Music compatible TTML2 with a morphe namespace.
 * Output is minified for compact storage.
 */
public final class TTMLGenerator {

    private static final String NS_DEFAULT = "http://www.w3.org/ns/ttml";
    private static final String NS_ITUNES = "http://music.apple.com/lyric-ttml-internal";
    private static final String NS_TTM = "http://www.w3.org/ns/ttml#metadata";
    private static final String NS_TTS = "http://www.w3.org/ns/ttml#styling";
    private static final String NS_MORPHE = "https://morphe.software/ttml";

    private static final Pattern LRC_META = Pattern.compile("\\[([^]]+):\\s*([^]]*)]");
    private static final Pattern COPYRIGHT_LABEL = Pattern.compile("(?i)^copyright\\s*[:：]\\s*");

    private static final String LRC_FORMAT_TYPE = "lrc";
    private static final String DEFAULT_AGENT = "v1";
    private static final String DEFAULT_LANGUAGE = "en";
    private static final String ROMAJI_LANGUAGE = "romaji";

    private final StringBuilder sb = new StringBuilder(8192);
    private final Lyrics lyrics;
    private final List<LyricsLine> lines;
    @Nullable
    private final TrackInfo track;
    @Nullable
    private final CreditInfo creditInfo;
    @Nullable
    private final List<String> translatedLines;
    @Nullable
    private final List<LyricsLine> romanizedLines;
    private final String translatedLang;
    private final String romanizedLang;
    private final boolean stripPunctuation = Settings.LYRICS_STRIP_TRAILING_PUNCTUATION.get();
    private final int hanMode = LyricsPresentation.hanMode();

    private TTMLGenerator(Lyrics lyrics, @Nullable TrackInfo track, @Nullable CreditInfo creditInfo,
                          @Nullable List<String> translatedLines,
                          @Nullable List<LyricsLine> romanizedLines,
                          @Nullable String translatedLang, @Nullable String romanizedLang) {
        this.lyrics = lyrics;
        this.lines = lyrics.lines();
        this.track = track;
        this.creditInfo = creditInfo;
        this.translatedLines = translatedLines;
        this.romanizedLines = romanizedLines;
        this.translatedLang = translatedLang != null ? translatedLang : DEFAULT_LANGUAGE;
        this.romanizedLang = romanizedLang != null ? romanizedLang : ROMAJI_LANGUAGE;
    }

    @Nullable
    public static String generate(@Nullable Lyrics lyrics, @Nullable TrackInfo track,
                                  @Nullable CreditInfo creditInfo) {
        return generate(lyrics, track, creditInfo, null, null, null, null);
    }

    @Nullable
    public static String generate(@Nullable Lyrics lyrics, @Nullable TrackInfo track,
                                  @Nullable CreditInfo creditInfo,
                                  @Nullable List<String> translatedLines,
                                  @Nullable List<LyricsLine> romanizedLines,
                                  @Nullable String translatedLang,
                                  @Nullable String romanizedLang) {
        if (lyrics == null || lyrics.isEmpty()) {
            return null;
        }
        return new TTMLGenerator(lyrics, track, creditInfo, translatedLines, romanizedLines,
                translatedLang, romanizedLang).build();
    }

    private String build() {
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<tt xmlns=\"").append(NS_DEFAULT).append('"');
        sb.append(" xmlns:itunes=\"").append(NS_ITUNES).append('"');
        sb.append(" xmlns:ttm=\"").append(NS_TTM).append('"');
        sb.append(" xmlns:tts=\"").append(NS_TTS).append('"');
        sb.append(" xmlns:morphe=\"").append(NS_MORPHE).append('"');
        if (!lyrics.synced()) {
            sb.append(" itunes:timing=\"None\"");
        }
        sb.append(" xml:lang=\"").append(DEFAULT_LANGUAGE).append("\">");

        buildHead();
        buildBody();

        sb.append("</tt>");
        return sb.toString();
    }

    private void buildHead() {
        sb.append("<head><metadata>");

        // The track is what is playing, so the tags of the lyrics only fill what it lacks.
        final Map<String, String> tags = lrcTags();
        appendElement("title", track != null && hasText(track.title())
                ? track.title() : tags.get("ti"));
        appendElement("artist", track != null && hasText(track.artist())
                ? track.artist() : tags.get("ar"));
        appendElement("album", track != null && hasText(track.album())
                ? track.album() : tags.get("al"));

        final Set<String> credits = new LinkedHashSet<>();
        String copyright = null;
        if (lyrics.credits() != null) {
            for (String credit : lyrics.credits()) {
                if (copyright == null && isCopyright(credit)) {
                    copyright = COPYRIGHT_LABEL.matcher(credit).replaceFirst("").trim();
                } else if (hasText(credit)) {
                    credits.add(credit);
                }
            }
        }
        appendElement("copyright", copyright);
        appendElement("desc", lyrics.about());

        final List<String> songwriters = lyrics.songwriters();
        if (songwriters != null && !songwriters.isEmpty()) {
            sb.append("<songwriters>");
            for (String songwriter : songwriters) {
                appendElement("songwriter", songwriter);
            }
            sb.append("</songwriters>");
        }

        for (Map.Entry<String, String> tag : tags.entrySet()) {
            final String key = tag.getKey();
            final String morpheTag = mapLrcTag(key);
            if (morpheTag != null) {
                appendElement("morphe:" + morpheTag, tag.getValue());
            } else if (LRCParser.CREDIT_META_KEYS.contains(key)
                    && !credits.contains(key + ":" + tag.getValue())) {
                // No standard TTML field for uploader/submitter.
                credits.add(tag.getValue());
            }
        }
        for (String credit : credits) {
            appendElement("morphe:credits", credit);
        }

        appendElement("morphe:source", lyrics.providerName());
        appendElement("morphe:sourceUrl", lyrics.sourceUrl());
        if (creditInfo != null) {
            appendElement("morphe:translator", creditInfo.translator());
            appendElement("morphe:romanizer", creditInfo.romanizer());
        }

        buildItunesMetadata();

        sb.append("</metadata>");
        buildAgents();
        sb.append("</head>");
    }

    /** The first value of each tag of LRC lyrics; other formats hold no tags to read. */
    private Map<String, String> lrcTags() {
        final Map<String, String> tags = new LinkedHashMap<>();
        if (!LRC_FORMAT_TYPE.equals(lyrics.formatType()) || lyrics.rawFormat() == null) {
            return tags;
        }
        final Matcher matcher = LRC_META.matcher(lyrics.rawFormat());
        while (matcher.find()) {
            final String value = Objects.requireNonNull(matcher.group(2)).trim();
            if (!value.isEmpty()) {
                tags.putIfAbsent(Objects.requireNonNull(matcher.group(1)).trim().toLowerCase(Locale.ROOT), value);
            }
        }
        return tags;
    }

    @Nullable
    private static String mapLrcTag(String key) {
        return switch (key) {
            case "length", "re", "kana", "ve", "ao" -> key;
            case "language", "lang" -> "language";
            default -> null;
        };
    }

    private static boolean isCopyright(String credit) {
        return COPYRIGHT_LABEL.matcher(credit).find() || credit.contains("©");
    }

    private void buildAgents() {
        final Map<String, String> agentNames = lyrics.agentNames();
        final Set<String> agents = new LinkedHashSet<>();
        for (LyricsLine line : lines) {
            agents.add(agentOf(line));
        }
        for (String agent : agents) {
            sb.append("<agent xml:id=\"").append(escapeAttr(agent))
                    .append("\" ttm:type=\"person\">");
            appendElement("ttm:name", agentNames != null ? agentNames.get(agent) : null);
            sb.append("</agent>");
        }
    }

    private static String agentOf(LyricsLine line) {
        return hasText(line.agentId()) ? line.agentId() : DEFAULT_AGENT;
    }

    private void buildItunesMetadata() {
        boolean hasTranslations = false;
        boolean hasTransliterations = false;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBG()) {
                hasTranslations |= translationAt(i) != null;
                hasTransliterations |= wordsWithRomaji(i) != null || romanizationAt(i) != null;
            }
        }
        if (!hasTranslations && !hasTransliterations) {
            return;
        }

        sb.append("<iTunesMetadata>");
        if (hasTranslations) {
            sb.append("<translations><translation xml:lang=\"")
                    .append(escapeAttr(translatedLang)).append("\">");
            int key = 0;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).isBG()) continue;
                key++;
                final String translation = translationAt(i);
                if (translation != null) {
                    appendLineText(key, translation.trim());
                }
            }
            sb.append("</translation></translations>");
        }
        if (hasTransliterations) {
            sb.append("<transliterations><transliteration>");
            int key = 0;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).isBG()) continue;
                key++;
                final List<Word> words = wordsWithRomaji(i);
                if (words != null) {
                    appendTimedRomaji(key, words);
                } else {
                    final String romanization = romanizationAt(i);
                    if (romanization != null) {
                        appendLineText(key, romanization.trim());
                    }
                }
            }
            sb.append("</transliteration></transliterations>");
        }
        sb.append("</iTunesMetadata>");
    }

    private void appendLineText(int key, String text) {
        sb.append("<text for=\"L").append(key).append("\">")
                .append(escapeText(formatLine(text))).append("</text>");
    }

    private void appendTimedRomaji(int key, List<Word> words) {
        sb.append("<text for=\"L").append(key).append("\">");
        for (Word word : words) {
            final String romaji = romajiOf(word);
            if (romaji != null) {
                sb.append("<span").append(timing(word.startMs(), word.endMs())).append('>')
                        .append(escapeText(convertHan(romaji))).append("</span>");
            }
        }
        sb.append("</text>");
    }

    /** The panel's translation of a line, null when it has none. */
    @Nullable
    private String translationAt(int index) {
        if (translatedLines == null || index >= translatedLines.size()) {
            return null;
        }
        final String translation = translatedLines.get(index);
        return hasText(translation) ? translation : null;
    }

    /** The whole-line reading of a line: the panel's, else the one the lyrics shipped with. */
    @Nullable
    private String romanizationAt(int index) {
        final String panel = textAt(romanizedLines, index);
        return panel != null ? panel : textAt(lyrics.romanization(), index);
    }

    @Nullable
    private static String textAt(@Nullable List<LyricsLine> aligned, int index) {
        if (aligned == null || index >= aligned.size()) {
            return null;
        }
        final LyricsLine line = aligned.get(index);
        return line != null && hasText(line.text()) ? line.text() : null;
    }

    /** The words of the panel's reading of a line, null when none of them has a reading. */
    @Nullable
    private List<Word> wordsWithRomaji(int index) {
        if (romanizedLines == null || index >= romanizedLines.size()) {
            return null;
        }
        final LyricsLine line = romanizedLines.get(index);
        return line != null && !joinRomaji(line.words()).isEmpty() ? line.words() : null;
    }

    /** A reading that only repeats the ruby, such as pinyin used as both, says nothing new. */
    @Nullable
    private static String romajiOf(Word word) {
        final String romaji = word.romaji();
        return hasText(romaji) && !romaji.equals(word.ruby()) ? romaji.trim() : null;
    }

    private static String joinRomaji(List<Word> words) {
        final List<String> parts = new ArrayList<>(words.size());
        for (Word word : words) {
            final String romaji = romajiOf(word);
            if (romaji != null) {
                parts.add(romaji);
            }
        }
        return String.join(" ", parts);
    }

    private void buildBody() {
        sb.append("<body>");

        record DivRange(@Nullable String songPart, int startIdx, int endIdx, long beginMs,
                        long endMs) {
        }
        final List<DivRange> divs = new ArrayList<>();
        // A section is often named only on the line that opens it, so a line without a name of
        // its own stays in the section before it.
        String currentPart = null;
        int divStartIdx = -1;
        long divBeginMs = 0;
        long divEndMs = 0;
        int lastMainIdx = -1;

        for (int i = 0; i < lines.size(); i++) {
            final LyricsLine line = lines.get(i);
            if (line.isBG()) continue;

            final String songPart = line.songPart() != null ? line.songPart() : currentPart;
            if (divStartIdx < 0 || (songPart != null && !songPart.equals(currentPart))) {
                if (divStartIdx >= 0) {
                    divs.add(new DivRange(currentPart, divStartIdx, lastMainIdx, divBeginMs, divEndMs));
                }
                currentPart = songPart;
                divStartIdx = i;
                divBeginMs = line.startTimeMs();
                divEndMs = line.endTimeMs();
            } else {
                divEndMs = Math.max(divEndMs, line.endTimeMs());
            }
            lastMainIdx = i;
        }
        if (divStartIdx >= 0) {
            divs.add(new DivRange(currentPart, divStartIdx, lastMainIdx, divBeginMs, divEndMs));
        }

        int key = 0;
        for (DivRange div : divs) {
            sb.append("<div");
            if (div.songPart() != null) {
                sb.append(" itunes:songPart=\"").append(escapeAttr(div.songPart())).append('"');
            }
            sb.append(timing(div.beginMs(), div.endMs())).append('>');

            for (int i = div.startIdx(); i <= div.endIdx(); i++) {
                if (!lines.get(i).isBG()) {
                    buildPElement(++key, i);
                }
            }

            sb.append("</div>");
        }

        sb.append("</body>");
    }

    private void buildPElement(int key, int index) {
        final LyricsLine line = lines.get(index);
        sb.append("<p itunes:key=\"L").append(key).append('"')
                .append(timing(line.startTimeMs(), line.endTimeMs()))
                .append(" ttm:agent=\"").append(escapeAttr(agentOf(line))).append("\">");

        // The words of a word-synced reading carry the text of the line too.
        final List<Word> readingWords = wordsWithRomaji(index);
        final List<Word> words = readingWords != null ? readingWords : line.words();
        appendWordsOrText(words, line.text());

        final Set<String> writtenLanguages = buildInlineTranslations(index, false);
        final String translation = translationAt(index);
        if (translation != null && !writtenLanguages.contains(translatedLang)) {
            appendRoleSpan("x-translation", translatedLang, translation);
        }

        final String wordRomaji = joinRomaji(words);
        if (!wordRomaji.isEmpty()) {
            appendRoleSpan("x-roman", ROMAJI_LANGUAGE, wordRomaji);
        } else {
            final String shipped = textAt(lyrics.romanization(), index);
            if (shipped != null) {
                appendRoleSpan("x-roman", ROMAJI_LANGUAGE, shipped.trim());
            }
            buildInlineRomanizations(index, false);
            final String panel = textAt(romanizedLines, index);
            if (panel != null) {
                appendRoleSpan("x-roman", romanizedLang, panel.trim());
            }
        }

        for (int bgIndex = index + 1; bgIndex < lines.size() && lines.get(bgIndex).isBG(); bgIndex++) {
            buildBgSpan(bgIndex);
        }

        sb.append("</p>");
    }

    private void buildBgSpan(int index) {
        final LyricsLine line = lines.get(index);
        sb.append("<span ttm:role=\"x-bg\"").append(timing(line.startTimeMs(), line.endTimeMs()))
                .append(" ttm:agent=\"").append(escapeAttr(agentOf(line))).append("\">");

        appendWordsOrText(line.words(), line.text());
        buildInlineTranslations(index, true);

        final String wordRomaji = joinRomaji(line.words());
        if (!wordRomaji.isEmpty()) {
            appendRoleSpan("x-roman", ROMAJI_LANGUAGE, wordRomaji);
        }
        buildInlineRomanizations(index, true);

        sb.append("</span>");
    }

    private void appendWordsOrText(List<Word> words, @Nullable String text) {
        if (!words.isEmpty()) {
            for (Word word : words) {
                buildWordSpan(word);
            }
        } else if (hasText(text)) {
            sb.append(escapeText(formatLine(text.trim())));
        }
    }

    private void buildWordSpan(Word word) {
        final String timing = timing(word.startMs(), word.endMs());
        final String rawText = word.text() != null ? word.text() : "";
        final String text = convertHan(rawText.trim());
        final String ruby = word.ruby() != null ? convertHan(word.ruby().trim()) : "";

        if (ruby.isEmpty()) {
            sb.append("<span").append(timing).append('>')
                    .append(escapeText(text)).append("</span>");
        } else {
            sb.append("<span tts:ruby=\"container\"").append(timing).append('>')
                    .append("<span tts:ruby=\"base\">").append(escapeText(text)).append("</span>")
                    .append("<span tts:ruby=\"textContainer\"><span tts:ruby=\"text\">")
                    .append(escapeText(ruby)).append("</span></span></span>");
        }
        if (word.endsWithSpace() || rawText.endsWith(" ")) {
            sb.append(' ');
        }
    }

    /** @return The languages written, without the marker of the background track. */
    private Set<String> buildInlineTranslations(int index, boolean background) {
        final Set<String> written = new HashSet<>();
        if (lyrics.translations() == null) {
            return written;
        }
        for (Map.Entry<String, List<LyricsLine>> entry : lyrics.translations().entrySet()) {
            final String language = languageOf(entry.getKey(), background);
            final String text = textAt(entry.getValue(), index);
            if (language != null && text != null) {
                appendRoleSpan("x-translation", language, text);
                written.add(language);
            }
        }
        return written;
    }

    private void buildInlineRomanizations(int index, boolean background) {
        if (lyrics.romanizations() == null) {
            return;
        }
        for (Map.Entry<String, List<LyricsLine>> entry : lyrics.romanizations().entrySet()) {
            final String language = languageOf(entry.getKey(), background);
            final String text = textAt(entry.getValue(), index);
            if (language != null && text != null) {
                appendRoleSpan("x-roman", language, text.trim());
            }
        }
    }

    /** The language a track key names, or null when the track is not for this kind of line. */
    @Nullable
    private static String languageOf(String trackKey, boolean background) {
        final String prefix = LyricsMerge.BACKGROUND_TRACK_PREFIX;
        if (trackKey.startsWith(prefix) != background) {
            return null;
        }
        return background ? trackKey.substring(prefix.length()) : trackKey;
    }

    private void appendRoleSpan(String role, String language, String text) {
        sb.append("<span ttm:role=\"").append(role).append("\" xml:lang=\"")
                .append(escapeAttr(language)).append("\">")
                .append(escapeText(formatLine(text))).append("</span>");
    }

    private void appendElement(String tag, @Nullable String text) {
        if (hasText(text)) {
            sb.append('<').append(tag).append('>').append(escapeText(text))
                    .append("</").append(tag).append('>');
        }
    }

    /** The timing attributes of an element. Unsynced lyrics have none to give. */
    private String timing(long beginMs, long endMs) {
        return lyrics.synced()
                ? " begin=\"" + formatTime(beginMs) + "\" end=\"" + formatTime(endMs) + '"'
                : "";
    }

    private static String formatTime(long ms) {
        final long clamped = Math.max(ms, 0);
        final long totalSeconds = clamped / 1000;
        return String.format(Locale.US, "%02d:%02d.%03d",
                totalSeconds / 60, totalSeconds % 60, clamped % 1000);
    }

    private static boolean hasText(@Nullable String text) {
        return text != null && !text.isEmpty();
    }

    private static String escapeText(String text) {
        final StringBuilder escaped = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static String escapeAttr(String text) {
        return escapeText(text).replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** Line-level text goes through the same filters the panel shows it with. */
    private String formatLine(String text) {
        return LyricsTextFormatter.format(text, false, stripPunctuation, hanMode).text();
    }

    /** A word keeps its punctuation, which only the end of a line can lose. */
    private String convertHan(String text) {
        return LyricsTextFormatter.format(text, false, false, hanMode).text();
    }
}
