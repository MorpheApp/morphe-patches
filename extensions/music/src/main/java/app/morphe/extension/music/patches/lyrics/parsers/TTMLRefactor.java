/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.parsers;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.model.CreditInfo;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsTextFormatter;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.settings.Settings;

/**
 * Reorganizes lyrics into Apple Music compatible TTML2 with a morphe namespace.
 * Output is minified for compact storage.
 */
public final class TTMLRefactor {

    private static final String NS_DEFAULT = "http://www.w3.org/ns/ttml";
    private static final String NS_ITUNES = "http://music.apple.com/lyric-ttml-internal";
    private static final String NS_TTM = "http://www.w3.org/ns/ttml#metadata";
    private static final String NS_TTS = "http://www.w3.org/ns/ttml#styling";
    private static final String NS_MORPHE = "http://morphe.app/lyrics";

    private static final Pattern LRC_META = Pattern.compile("\\[([^\\]]+):\\s*([^\\]]*)\\]");

    private TTMLRefactor() {
    }

    @Nullable
    public static String generate(Lyrics lyrics, TrackInfo track,
                                  @Nullable CreditInfo creditInfo) {
        return generate(lyrics, track, creditInfo, null, null, null, null);
    }

    @Nullable
    public static String generate(Lyrics lyrics, TrackInfo track,
                                  @Nullable CreditInfo creditInfo,
                                  @Nullable List<String> translatedLines,
                                  @Nullable List<LyricsLine> romanizedLines,
                                  @Nullable String translatedLang,
                                  @Nullable String romanizedLang) {
        if (lyrics == null || lyrics.isEmpty() || lyrics == Lyrics.NOT_FOUND) {
            return null;
        }

        final StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<tt xmlns=\"").append(NS_DEFAULT).append("\"");
        sb.append(" xmlns:itunes=\"").append(NS_ITUNES).append("\"");
        sb.append(" xmlns:ttm=\"").append(NS_TTM).append("\"");
        sb.append(" xmlns:tts=\"").append(NS_TTS).append("\"");
        sb.append(" xmlns:morphe=\"").append(NS_MORPHE).append("\"");
        sb.append(" xml:lang=\"en\">");

        buildHead(sb, lyrics, track, creditInfo,
                translatedLines, romanizedLines, translatedLang, romanizedLang);
        buildBody(sb, lyrics, translatedLines, romanizedLines, translatedLang, romanizedLang);

        sb.append("</tt>");
        return sb.toString();
    }

    private static void buildHead(StringBuilder sb, Lyrics lyrics, TrackInfo track,
                                  @Nullable CreditInfo creditInfo,
                                  @Nullable List<String> translatedLines,
                                  @Nullable List<LyricsLine> romanizedLines,
                                  @Nullable String translatedLang,
                                  @Nullable String romanizedLang) {
        sb.append("<head><metadata>");

        if (track != null) {
            if (track.title() != null && !track.title().isEmpty()) {
                sb.append("<title>").append(escapeText(track.title())).append("</title>");
            }
            if (track.artist() != null && !track.artist().isEmpty()) {
                sb.append("<artist>").append(escapeText(track.artist())).append("</artist>");
            }
            if (track.album() != null && !track.album().isEmpty()) {
                sb.append("<album>").append(escapeText(track.album())).append("</album>");
            }
        }

        String copyright = null;
        List<String> songwriters = new ArrayList<>();
        if (lyrics.songwriters() != null) {
            for (String sw : lyrics.songwriters()) {
                if (sw == null) continue;
                String lower = sw.toLowerCase();
                if (lower.startsWith("copyright") || sw.contains("©")) {
                    if (copyright == null) {
                        copyright = sw.replaceFirst("(?i)^copyright\\s*[:：]\\s*", "").trim();
                        if (copyright.isEmpty()) copyright = sw.trim();
                    }
                } else {
                    songwriters.add(sw);
                }
            }
        }

        if (copyright != null && !copyright.isEmpty()) {
            sb.append("<copyright>").append(escapeText(copyright)).append("</copyright>");
        }
        if (lyrics.about() != null && !lyrics.about().isEmpty()) {
            sb.append("<desc>").append(escapeText(lyrics.about())).append("</desc>");
        }
        if (!songwriters.isEmpty()) {
            sb.append("<songwriters>");
            for (String sw : songwriters) {
                sb.append("<songwriter>").append(escapeText(sw)).append("</songwriter>");
            }
            sb.append("</songwriters>");
        }

        // ti/ar/al fill standard fields only when TrackInfo lacks them.
        List<String> lrcCredits = new ArrayList<>();
        if (lyrics.rawFormat() != null && !lyrics.rawFormat().isEmpty()) {
            extractLrcMetadata(sb, lyrics.rawFormat(), track, lrcCredits);
        }

        List<String> allCredits = new ArrayList<>();
        if (lyrics.credits() != null) {
            for (String c : lyrics.credits()) {
                if (c != null && !c.isEmpty() && !allCredits.contains(c)) {
                    allCredits.add(c);
                }
            }
        }
        for (String c : lrcCredits) {
            if (c != null && !c.isEmpty() && !allCredits.contains(c)) {
                allCredits.add(c);
            }
        }
        for (String credit : allCredits) {
            sb.append("<morphe:credits>").append(escapeText(credit))
                    .append("</morphe:credits>");
        }

        sb.append("<morphe:source>").append(escapeText(lyrics.providerName()))
                .append("</morphe:source>");
        if (lyrics.sourceUrl() != null && !lyrics.sourceUrl().isEmpty()) {
            sb.append("<morphe:sourceUrl>").append(escapeText(lyrics.sourceUrl()))
                    .append("</morphe:sourceUrl>");
        }
        if (creditInfo != null) {
            if (creditInfo.translator() != null && !creditInfo.translator().isEmpty()) {
                sb.append("<morphe:translator>").append(escapeText(creditInfo.translator()))
                        .append("</morphe:translator>");
            }
            if (creditInfo.romanizer() != null && !creditInfo.romanizer().isEmpty()) {
                sb.append("<morphe:romanizer>").append(escapeText(creditInfo.romanizer()))
                        .append("</morphe:romanizer>");
            }
        }

        buildItunesMetadata(sb, lyrics, translatedLines, romanizedLines,
                translatedLang, romanizedLang);

        sb.append("</metadata>");
        buildAgents(sb, lyrics);
        sb.append("</head>");
    }

    private static void buildAgents(StringBuilder sb, Lyrics lyrics) {
        final Map<String, Boolean> seenAgents = new LinkedHashMap<>();
        for (LyricsLine line : lyrics.lines()) {
            String agentId = line.agentId();
            if (agentId != null && !agentId.isEmpty() && !seenAgents.containsKey(agentId)) {
                seenAgents.put(agentId, Boolean.TRUE);
                sb.append("<agent xml:id=\"").append(escapeAttr(agentId))
                        .append("\" ttm:type=\"person\">");
                final Map<String, String> agentNames = lyrics.agentNames();
                if (agentNames != null) {
                    final String name = agentNames.get(agentId);
                    if (name != null && !name.isEmpty()) {
                        sb.append("<ttm:name>").append(escapeText(name))
                                .append("</ttm:name>");
                    }
                }
                sb.append("</agent>");
            }
        }
        if (seenAgents.isEmpty()) {
            sb.append("<agent xml:id=\"v1\" ttm:type=\"person\"/>");
        }
    }

    private static void buildItunesMetadata(StringBuilder sb, Lyrics lyrics,
                                            @Nullable List<String> translatedLines,
                                            @Nullable List<LyricsLine> romanizedLines,
                                            @Nullable String translatedLang,
                                            @Nullable String romanizedLang) {
        final List<LyricsLine> lines = lyrics.lines();

        boolean hasTranslations = false;
        if (translatedLines != null) {
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).isBG()) continue;
                if (i >= translatedLines.size()) break;
                String text = translatedLines.get(i);
                if (text != null && !text.isEmpty()) {
                    hasTranslations = true;
                    break;
                }
            }
        }

        boolean hasTransliterations = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isBG()) continue;
            if (romanizedLines != null && i < romanizedLines.size()) {
                LyricsLine romaLine = romanizedLines.get(i);
                if (romaLine != null) {
                    if (romaLine.text() != null && !romaLine.text().isEmpty()) {
                        hasTransliterations = true;
                        break;
                    }
                    if (romaLine.hasWords()) {
                        for (Word w : romaLine.words()) {
                            if (w.romaji() != null && !w.romaji().isEmpty()) {
                                hasTransliterations = true;
                                break;
                            }
                        }
                        if (hasTransliterations) break;
                    }
                }
            }
            if (!hasTransliterations && lyrics.romanization() != null
                    && i < lyrics.romanization().size()) {
                LyricsLine romaLine = lyrics.romanization().get(i);
                if (romaLine != null && romaLine.text() != null && !romaLine.text().isEmpty()) {
                    hasTransliterations = true;
                    break;
                }
            }
        }

        if (!hasTranslations && !hasTransliterations) return;

        sb.append("<iTunesMetadata>");

        if (hasTranslations) {
            String lang = translatedLang != null ? translatedLang : "en";
            sb.append("<translations><translation xml:lang=\"")
                    .append(escapeAttr(lang)).append("\">");
            int mainLineNum = 0;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).isBG()) continue;
                mainLineNum++;
                if (translatedLines == null || i >= translatedLines.size()) continue;
                String text = translatedLines.get(i);
                if (text == null || text.isEmpty()) continue;
                sb.append("<text for=\"L").append(mainLineNum).append("\">")
                        .append(escapeText(formatLineText(text.trim()))).append("</text>");
            }
            sb.append("</translation></translations>");
        }

        if (hasTransliterations) {
            sb.append("<transliterations><transliteration>");
            int mainLineNum = 0;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).isBG()) continue;
                mainLineNum++;

                List<Word> perWordRomaji = null;
                if (romanizedLines != null && i < romanizedLines.size()) {
                    LyricsLine romaLine = romanizedLines.get(i);
                    if (romaLine != null && romaLine.hasWords()) {
                        boolean hasRomaji = false;
                        for (Word w : romaLine.words()) {
                            if (w.romaji() != null && !w.romaji().isEmpty()) {
                                hasRomaji = true;
                                break;
                            }
                        }
                        if (hasRomaji) perWordRomaji = romaLine.words();
                    }
                }

                if (perWordRomaji != null && !perWordRomaji.isEmpty()) {
                    sb.append("<text for=\"L").append(mainLineNum).append("\">");
                    for (Word word : perWordRomaji) {
                        String romaji = word.romaji();
                        String ruby = word.ruby();
                        if (romaji != null && romaji.equals(ruby)) {
                            romaji = null;
                        }
                        if (romaji != null && !romaji.isEmpty()) {
                            sb.append("<span begin=\"").append(formatTime(word.startMs()))
                                    .append("\" end=\"").append(formatTime(word.endMs()))
                                    .append("\">")
                                    .append(escapeText(convertHan(romaji.trim()))).append("</span>");
                        }
                    }
                    sb.append("</text>");
                } else {
                    String romajiText = null;
                    if (romanizedLines != null && i < romanizedLines.size()) {
                        LyricsLine romaLine = romanizedLines.get(i);
                        if (romaLine != null && romaLine.text() != null
                                && !romaLine.text().isEmpty()) {
                            romajiText = romaLine.text();
                        }
                    }
                    if (romajiText == null && lyrics.romanization() != null
                            && i < lyrics.romanization().size()) {
                        LyricsLine romaLine = lyrics.romanization().get(i);
                        if (romaLine != null && romaLine.text() != null
                                && !romaLine.text().isEmpty()) {
                            romajiText = romaLine.text();
                        }
                    }
                    if (romajiText != null && !romajiText.isEmpty()) {
                        sb.append("<text for=\"L").append(mainLineNum).append("\">")
                                .append(escapeText(formatLineText(romajiText.trim()))).append("</text>");
                    }
                }
            }
            sb.append("</transliteration></transliterations>");
        }

        sb.append("</iTunesMetadata>");
    }

    private static void extractLrcMetadata(StringBuilder sb, String rawFormat,
                                           @Nullable TrackInfo track,
                                           List<String> extraCredits) {
        final Matcher m = LRC_META.matcher(rawFormat);
        while (m.find()) {
            final String key = m.group(1).trim().toLowerCase();
            final String value = m.group(2).trim();
            if (value.isEmpty()) continue;

            if (key.equals("ti")) {
                // Fill <title> only when TrackInfo did not supply one.
                if (track == null || track.title() == null || track.title().isEmpty()) {
                    sb.append("<title>").append(escapeText(value)).append("</title>");
                }
                continue;
            }
            if (key.equals("ar")) {
                if (track == null || track.artist() == null || track.artist().isEmpty()) {
                    sb.append("<artist>").append(escapeText(value)).append("</artist>");
                }
                continue;
            }
            if (key.equals("al")) {
                if (track == null || track.album() == null || track.album().isEmpty()) {
                    sb.append("<album>").append(escapeText(value)).append("</album>");
                }
                continue;
            }
            if (key.equals("au") || key.equals("by")) {
                // No standard TTML field for uploader/submitter; goes to <morphe:credits>.
                extraCredits.add(value);
                continue;
            }

            final String morpheTag = mapLrcTag(key);
            if (morpheTag != null) {
                sb.append("<morphe:").append(morpheTag).append(">")
                        .append(escapeText(value))
                        .append("</morphe:").append(morpheTag).append(">");
            }
        }
    }

    @Nullable
    private static String mapLrcTag(String key) {
        return switch (key) {
            case "length" -> "length";
            case "re" -> "re";
            case "kana" -> "kana";
            case "language", "lang" -> "language";
            case "ve" -> "ve";
            case "ao" -> "ao";
            default -> null;
        };
    }

    private static void buildBody(StringBuilder sb, Lyrics lyrics,
                                  @Nullable List<String> translatedLines,
                                  @Nullable List<LyricsLine> romanizedLines,
                                  @Nullable String translatedLang,
                                  @Nullable String romanizedLang) {
        sb.append("<body>");

        final List<LyricsLine> lines = lyrics.lines();

        record DivRange(String songPart, int startIdx, int endIdx, long beginMs, long endMs) {
        }
        List<DivRange> divs = new ArrayList<>();
        String currentPart = null;
        int divStartIdx = -1;
        long divBeginMs = 0;
        long divEndMs = 0;
        int lastNonBgIdx = -1;

        for (int i = 0; i < lines.size(); i++) {
            LyricsLine line = lines.get(i);
            if (line.isBG()) continue;

            final String songPart = line.songPart() != null ? line.songPart() : "Default";
            if (!songPart.equals(currentPart)) {
                if (divStartIdx >= 0) {
                    divs.add(new DivRange(currentPart, divStartIdx, lastNonBgIdx, divBeginMs, divEndMs));
                }
                currentPart = songPart;
                divStartIdx = i;
                divBeginMs = line.startTimeMs() != LyricsLine.NO_TIME ? line.startTimeMs() : 0;
                divEndMs = line.endTimeMs() != LyricsLine.NO_TIME ? line.endTimeMs() : divBeginMs;
            } else if (line.endTimeMs() != LyricsLine.NO_TIME && line.endTimeMs() > divEndMs) {
                divEndMs = line.endTimeMs();
            }
            lastNonBgIdx = i;
        }
        if (divStartIdx >= 0) {
            divs.add(new DivRange(currentPart, divStartIdx, lastNonBgIdx, divBeginMs, divEndMs));
        }

        for (DivRange div : divs) {
            sb.append("<div itunes:songPart=\"").append(escapeAttr(div.songPart()))
                    .append("\" begin=\"").append(formatTime(div.beginMs()))
                    .append("\" end=\"").append(formatTime(div.endMs())).append("\">");

            for (int i = div.startIdx(); i <= div.endIdx(); i++) {
                LyricsLine line = lines.get(i);
                if (line.isBG()) continue;
                final int mainLineIndex = countMainLines(lines, i);
                buildPElement(sb, line, mainLineIndex, lyrics, lines, i,
                        translatedLines, romanizedLines, translatedLang, romanizedLang);
            }

            sb.append("</div>");
        }

        sb.append("</body>");
    }

    private static int countMainLines(List<LyricsLine> lines, int upTo) {
        int count = 0;
        for (int i = 0; i <= upTo; i++) {
            if (!lines.get(i).isBG()) count++;
        }
        return count - 1;
    }

    private static void buildPElement(StringBuilder sb, LyricsLine line, int lineIndex,
                                      Lyrics lyrics, List<LyricsLine> allLines, int linePos,
                                      @Nullable List<String> translatedLines,
                                      @Nullable List<LyricsLine> romanizedLines,
                                      @Nullable String translatedLang,
                                      @Nullable String romanizedLang) {
        sb.append("<p itunes:key=\"L").append(lineIndex + 1).append("\"");

        sb.append(" begin=\"").append(formatTime(line.startTimeMs())).append("\"");
        sb.append(" end=\"").append(formatTime(line.endTimeMs())).append("\"");

        String agentId = line.agentId();
        if (agentId == null || agentId.isEmpty()) agentId = "v1";
        sb.append(" ttm:agent=\"").append(escapeAttr(agentId)).append("\"");

        sb.append(">");

        final List<Word> displayWords = getDisplayWords(line, linePos, romanizedLines);
        final boolean hasPerWordRomaji = displayWords != null;

        if (displayWords != null && !displayWords.isEmpty()) {
            for (Word word : displayWords) {
                buildWordSpan(sb, word);
            }
        } else if (line.text() != null && !line.text().isEmpty()) {
            sb.append(escapeText(formatLineText(line.text().trim())));
        }

        final java.util.Set<String> outputTransLangs = buildInlineTranslations(sb, lyrics, linePos, false);

        if (translatedLines != null && linePos < translatedLines.size()) {
            String transText = translatedLines.get(linePos);
            if (transText != null && !transText.isEmpty()) {
                String lang = translatedLang != null ? translatedLang : "en";
                if (!outputTransLangs.contains(lang)) {
                    sb.append("<span ttm:role=\"x-translation\" xml:lang=\"")
                            .append(escapeAttr(lang)).append("\">")
                            .append(escapeText(formatLineText(transText))).append("</span>");
                }
            }
        }

        if (hasPerWordRomaji) {
            List<String> romajiParts = new ArrayList<>();
            for (Word word : displayWords) {
                String romaji = word.romaji();
                String ruby = word.ruby();
                // Skip romaji when it duplicates ruby (e.g. Chinese pinyin used as both)
                if (romaji != null && romaji.equals(ruby)) {
                    romaji = null;
                }
                if (romaji != null && !romaji.isEmpty()) {
                    romajiParts.add(romaji.trim());
                }
            }
            if (!romajiParts.isEmpty()) {
                String combinedRomaji = String.join(" ", romajiParts);
                sb.append("<span ttm:role=\"x-roman\" xml:lang=\"romaji\">")
                        .append(escapeText(formatLineText(combinedRomaji))).append("</span>");
            }
        } else {
            if (lyrics.romanization() != null && linePos < lyrics.romanization().size()) {
                LyricsLine romaLine = lyrics.romanization().get(linePos);
                if (romaLine != null && romaLine.text() != null && !romaLine.text().isEmpty()) {
                    sb.append("<span ttm:role=\"x-roman\" xml:lang=\"romaji\">")
                            .append(escapeText(formatLineText(romaLine.text().trim()))).append("</span>");
                }
            }
            if (lyrics.romanizations() != null) {
                for (Map.Entry<String, List<LyricsLine>> entry : lyrics.romanizations().entrySet()) {
                    String lang = entry.getKey();
                    List<LyricsLine> romaLines = entry.getValue();
                    if (linePos < romaLines.size()) {
                        LyricsLine romaLine = romaLines.get(linePos);
                        if (romaLine != null && romaLine.text() != null && !romaLine.text().isEmpty()) {
                            sb.append("<span ttm:role=\"x-roman\" xml:lang=\"")
                                    .append(escapeAttr(lang)).append("\">")
                                    .append(escapeText(formatLineText(romaLine.text().trim()))).append("</span>");
                        }
                    }
                }
            }
            if (romanizedLines != null && linePos < romanizedLines.size()) {
                LyricsLine romaLine = romanizedLines.get(linePos);
                if (romaLine != null && romaLine.text() != null && !romaLine.text().isEmpty()) {
                    String lang = romanizedLang != null ? romanizedLang : "romaji";
                    sb.append("<span ttm:role=\"x-roman\" xml:lang=\"")
                            .append(escapeAttr(lang)).append("\">")
                            .append(escapeText(formatLineText(romaLine.text().trim()))).append("</span>");
                }
            }
        }

        int bgIdx = linePos + 1;
        while (bgIdx < allLines.size() && allLines.get(bgIdx).isBG()) {
            LyricsLine bgLine = allLines.get(bgIdx);
            buildBgSpan(sb, bgLine, lyrics, bgIdx);
            bgIdx++;
        }

        sb.append("</p>");
    }

    /** Returns per-word romaji words when available, otherwise the line's own words, or null. */
    @Nullable
    private static List<Word> getDisplayWords(LyricsLine line, int linePos,
                                              @Nullable List<LyricsLine> romanizedLines) {
        if (romanizedLines != null && linePos < romanizedLines.size()) {
            LyricsLine romaLine = romanizedLines.get(linePos);
            if (romaLine != null && romaLine.hasWords()) {
                boolean hasRomaji = false;
                for (Word w : romaLine.words()) {
                    if (w.romaji() != null && !w.romaji().isEmpty()) {
                        hasRomaji = true;
                        break;
                    }
                }
                if (hasRomaji) return romaLine.words();
            }
        }
        if (line.hasWords()) return line.words();
        return null;
    }

    private static void buildWordSpan(StringBuilder sb, Word word) {
        final StringBuilder attrs = new StringBuilder();
        attrs.append("begin=\"").append(formatTime(word.startMs())).append("\"");
        attrs.append(" end=\"").append(formatTime(word.endMs())).append("\"");

        String romaji = word.romaji();
        String ruby = word.ruby();
        // Skip romaji when it duplicates ruby (e.g. Chinese pinyin used as both)
        if (romaji != null && romaji.equals(ruby)) {
            romaji = null;
        }
        String text = word.text() != null ? convertHan(trimCjkSpace(word.text())) : "";
        if (ruby != null) ruby = convertHan(ruby.trim());

        if (ruby != null && !ruby.isEmpty()) {
            sb.append("<span tts:ruby=\"container\"");
            if (attrs.length() > 0) sb.append(" ").append(attrs);
            sb.append(">");
            sb.append("<span tts:ruby=\"base\">").append(escapeText(text)).append("</span>");
            sb.append("<span tts:ruby=\"textContainer\">");
            sb.append("<span tts:ruby=\"text\">").append(escapeText(ruby)).append("</span>");
            sb.append("</span>");
            sb.append("</span>");
        } else {
            sb.append("<span");
            if (attrs.length() > 0) sb.append(" ").append(attrs);
            sb.append(">");
            sb.append(escapeText(text));
            sb.append("</span>");
        }
        if (word.endsWithSpace()) {
            sb.append(" ");
        }
    }

    private static void buildBgSpan(StringBuilder sb, LyricsLine bgLine,
                                    Lyrics lyrics, int bgIdx) {
        sb.append("<span ttm:role=\"x-bg\"");

        sb.append(" begin=\"").append(formatTime(bgLine.startTimeMs())).append("\"");
        sb.append(" end=\"").append(formatTime(bgLine.endTimeMs())).append("\"");

        String bgAgentId = bgLine.agentId();
        if (bgAgentId == null || bgAgentId.isEmpty()) bgAgentId = "v1";
        sb.append(" ttm:agent=\"").append(escapeAttr(bgAgentId)).append("\"");

        sb.append(">");

        if (bgLine.hasWords()) {
            for (Word word : bgLine.words()) {
                buildWordSpan(sb, word);
            }
        } else if (bgLine.text() != null && !bgLine.text().isEmpty()) {
            sb.append(escapeText(formatLineText(bgLine.text().trim())));
        }

        buildInlineTranslations(sb, lyrics, bgIdx, true);

        if (bgLine.hasWords()) {
            List<String> bgRomajiParts = new ArrayList<>();
            for (Word word : bgLine.words()) {
                String romaji = word.romaji();
                String ruby = word.ruby();
                if (romaji != null && romaji.equals(ruby)) {
                    romaji = null;
                }
                if (romaji != null && !romaji.isEmpty()) {
                    bgRomajiParts.add(romaji.trim());
                }
            }
            if (!bgRomajiParts.isEmpty()) {
                String combinedRomaji = String.join(" ", bgRomajiParts);
                sb.append("<span ttm:role=\"x-roman\" xml:lang=\"romaji\">")
                        .append(escapeText(formatLineText(combinedRomaji))).append("</span>");
            }
        }

        if (lyrics.romanizations() != null) {
            for (Map.Entry<String, List<LyricsLine>> entry : lyrics.romanizations().entrySet()) {
                String lang = entry.getKey();
                if (!lang.startsWith("bg:")) continue;
                String baseLang = lang.substring(3);
                List<LyricsLine> romaLines = entry.getValue();
                int bgRomaIndex = bgIdx;
                if (bgRomaIndex < romaLines.size()) {
                    LyricsLine romaLine = romaLines.get(bgRomaIndex);
                    if (romaLine != null && romaLine.text() != null && !romaLine.text().isEmpty()) {
                        sb.append("<span ttm:role=\"x-roman\" xml:lang=\"")
                                .append(escapeAttr(baseLang)).append("\">")
                                .append(escapeText(formatLineText(romaLine.text().trim()))).append("</span>");
                    }
                }
            }
        }

        sb.append("</span>");
    }

    private static java.util.Set<String> buildInlineTranslations(StringBuilder sb, Lyrics lyrics,
                                                                 int lineIndex, boolean isBg) {
        java.util.Set<String> outputLangs = new java.util.HashSet<>();
        if (lyrics.translations() == null) return outputLangs;

        for (Map.Entry<String, List<LyricsLine>> entry : lyrics.translations().entrySet()) {
            String lang = entry.getKey();
            List<LyricsLine> transLines = entry.getValue();

            boolean bgLang = isBg && lang.startsWith("bg:");
            boolean mainLang = !isBg && !lang.startsWith("bg:");
            if (!bgLang && !mainLang) continue;

            if (lineIndex >= transLines.size()) continue;
            LyricsLine transLine = transLines.get(lineIndex);
            if (transLine == null || transLine.text() == null || transLine.text().isEmpty()) {
                continue;
            }

            String displayLang = lang.startsWith("bg:") ? lang.substring(3) : lang;
            sb.append("<span ttm:role=\"x-translation\" xml:lang=\"")
                    .append(escapeAttr(displayLang)).append("\">")
                    .append(escapeText(formatLineText(transLine.text()))).append("</span>");
            outputLangs.add(displayLang);
        }
        return outputLangs;
    }

    private static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000;
        long millis = ms % 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(java.util.Locale.US, "%02d:%02d.%03d", minutes, seconds, millis);
    }

    private static String escapeText(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escapeAttr(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isFormattingEnabled() {
        return Settings.LYRICS_STRIP_TRAILING_PUNCTUATION.get()
                || Settings.LYRICS_HAN_FORM.get();
    }

    private static int hanMode() {
        if (!Settings.LYRICS_HAN_FORM.get()) {
            return LyricsTextFormatter.HAN_NONE;
        }
        final String script = LyricsRequests.resolvedScript(LyricsRequests.translationLanguage());
        if ("Hant".equalsIgnoreCase(script)) {
            return LyricsTextFormatter.HAN_TRADITIONAL;
        }
        if ("Hans".equalsIgnoreCase(script)) {
            return LyricsTextFormatter.HAN_SIMPLIFIED;
        }
        return LyricsTextFormatter.HAN_NONE;
    }

    /** Line-level: strip trailing punctuation + Han form. */
    private static String formatLineText(String text) {
        if (text == null || !isFormattingEnabled()) {
            return text;
        }
        return LyricsTextFormatter.format(text, false,
                Settings.LYRICS_STRIP_TRAILING_PUNCTUATION.get(), hanMode()).text();
    }

    /** Han conversion only, for word-level spans. */
    private static String convertHan(String text) {
        if (text == null || hanMode() == LyricsTextFormatter.HAN_NONE) {
            return text;
        }
        return LyricsTextFormatter.format(text, false, false, hanMode()).text();
    }

    private static String trimCjkSpace(String text) {
        if (hanMode() == LyricsTextFormatter.HAN_NONE) {
            return text;
        }
        return text.trim();
    }
}
