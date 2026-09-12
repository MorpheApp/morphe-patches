/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.Word;

/**
 * Parses Apple Music style TTML into lyric lines.
 *
 * <p>Aligned with the AMLL TypeScript parser ({@code @applemusic-like-lyrics/ttml}):
 * namespace-aware parsing, single-pass body scan, background vocals as lines,
 * agent-based duet/right-alignment, inline + sidecar translations/romanizations,
 * IoU romanization alignment, and Ruby annotation support.
 */
final class TtmlParser {

    private static final Pattern TIME_UNIT = Pattern.compile("(-?\\d+(?:\\.\\d+)?)(ms|h|m|s)");
    private static final Pattern TIME_COLON = Pattern.compile(
            "^(?:(\\d+):)?(?:(\\d+):)?(\\d+(?:\\.\\d+)?)$");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private static final String NS_TT = "http://www.w3.org/ns/ttml";
    private static final String NS_TTM = "http://www.w3.org/ns/ttml#metadata";
    private static final String NS_ITUNES = "http://music.apple.com/lyric-ttml-internal";
    private static final String NS_XML = "http://www.w3.org/XML/1998/namespace";
    private static final String NS_TTS = "http://www.w3.org/ns/ttml#styling";
    private static final String NS_AMLL = "http://www.example.com/ns/amll";

    private static final Set<String> AMLL_EXCLUDED_KEYS = Set.of(
            "ncmMusicId", "qqMusicId", "spotifyId", "appleMusicId", "isrc", "ttmlAuthorGithub", "ttmlAuthorGithubLogin"
    );

    /** IoU minimum overlap threshold for romanization alignment. */
    private static final double MIN_IOU = 0.1;
    /** Fast-track tolerance: if start times differ by ≤2ms, consider them aligned. */
    private static final long FAST_TRACK_MS = 2;

    private TtmlParser() {
    }

    private static final String AGENT_TYPE_PERSON = "person";
    private static final String AGENT_TYPE_GROUP = "group";
    private static final String AGENT_TYPE_OTHER = "other";

    static final class TtmlResult {
        final List<LyricsLine> lines;
        @Nullable final List<LyricsLine> romanization;
        @Nullable final Map<String, List<LyricsLine>> translations;
        @Nullable final Map<String, List<LyricsLine>> romanizations;
        @Nullable final List<String> songwriters;
        @Nullable final List<String> amllCreditLines;
        @Nullable final Map<String, String> agentNames;

        TtmlResult(List<LyricsLine> lines, @Nullable List<LyricsLine> romanization,
                   @Nullable Map<String, List<LyricsLine>> translations,
                   @Nullable Map<String, List<LyricsLine>> romanizations,
                   @Nullable List<String> songwriters,
                   @Nullable List<String> amllCreditLines,
                   @Nullable Map<String, String> agentNames) {
            this.lines = lines;
            this.romanization = romanization;
            this.translations = translations;
            this.romanizations = romanizations;
            this.songwriters = songwriters;
            this.amllCreditLines = amllCreditLines;
            this.agentNames = agentNames;
        }
    }

    private static final class RomajiSyllable {
        final long startMs;
        final long endMs;
        final String text;

        RomajiSyllable(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    private static final class AgentInfo {
        final String type;
        @Nullable final String name;

        AgentInfo(String type, @Nullable String name) {
            this.type = type;
            this.name = name;
        }
    }

    private static final class SidecarTranslation {
        final String text;
        @Nullable final List<Word> words;
        @Nullable final String bgText;
        @Nullable final List<Word> bgWords;

        SidecarTranslation(String text, @Nullable List<Word> words,
                @Nullable String bgText, @Nullable List<Word> bgWords) {
            this.text = text;
            this.words = words;
            this.bgText = bgText;
            this.bgWords = bgWords;
        }
    }

    @Nullable
    static TtmlResult parse(String ttml) {
        if (ttml == null || ttml.isEmpty()) {
            return null;
        }
        try {
            final List<String> rawSongwriters = parseSongwriters(ttml);
            final List<String> songwriters;
            if (!rawSongwriters.isEmpty()) {
                StringBuilder sb = new StringBuilder("Written by ");
                for (int i = 0; i < rawSongwriters.size(); i++) {
                    if (i > 0) sb.append(" · ");
                    sb.append(rawSongwriters.get(i));
                }
                songwriters = List.of(sb.toString());
            } else {
                songwriters = null;
            }
            final List<String> amllCreditLines = parseAmllMetadata(ttml);
            final Map<String, AgentInfo> agentTypes = parseAgentTypes(ttml);
            final Map<String, List<RomajiSyllable>> sidecarRoman =
                    parseSidecarTransliterations(ttml);
            final Map<String, SidecarTranslation> sidecarTrans =
                    parseSidecarTranslations(ttml);

            final XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            final XmlPullParser p = factory.newPullParser();
            p.setInput(new StringReader(ttml));

            final List<LyricsLine> lines = new ArrayList<>();
            final List<LyricsLine> romanization = new ArrayList<>();
            final Map<String, List<LyricsLine>> translations = new HashMap<>();
            final Map<String, List<LyricsLine>> romanizations = new HashMap<>();
            int mainLineCount = 0;

            String rootTiming = null;
            boolean noTiming = false;
            boolean inHead = false;
            boolean inBody = false;
            String divSongPart = null;

            int event = p.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    final String local = localName(p.getName());

                    if ("tt".equals(local)) {
                        rootTiming = getAttr(p, NS_ITUNES, "timing", "itunes:timing");
                        noTiming = "none".equals(rootTiming);
                    } else if ("head".equals(local)) {
                        inHead = true;
                    } else if ("body".equals(local)) {
                        inBody = true;
                    } else if ("div".equals(local) && inBody && !inHead) {
                        divSongPart = getAttr(p, NS_ITUNES, "songPart", "itunes:songPart");
                        if (divSongPart == null) {
                            divSongPart = getAttr(p, NS_ITUNES, "songPart", "itunes:song-part");
                        }
                    } else if ("p".equals(local) && inBody && !inHead) {
                        final String lineId = getAttr(p, NS_ITUNES, "key", "itunes:key");
                        final String agentId = getAttr(p, NS_TTM, "agent", "ttm:agent");
                        final long pBegin = noTiming ? 0 : parseTime(getAttr(p, null, "begin", "begin"));
                        final long pEnd = noTiming ? 0 : parseTime(getAttr(p, null, "end", "end"));
                        final boolean hasTimeAttrs = !noTiming && (
                                getAttr(p, null, "begin", "begin") != null
                                || getAttr(p, null, "end", "end") != null);

                        final ParsedLine pl = processPElement(
                                p, rootTiming, lineId, pBegin, pEnd, hasTimeAttrs);

                        if (pl != null && !pl.text.isBlank()) {
                            final LyricsLine line = new LyricsLine(
                                    pl.begin, pl.end, pl.text, pl.words,
                                    agentId, false, false, divSongPart);
                            lines.add(line);
                            mainLineCount = lines.size();

                            final String bgAgent = pl.bgAgentId != null ? pl.bgAgentId : agentId;
                            for (LyricsLine bgSrc : pl.bgLines) {
                                final LyricsLine bgLine = new LyricsLine(
                                        bgSrc.startTimeMs(), bgSrc.endTimeMs(),
                                        bgSrc.text(), bgSrc.words(),
                                        bgAgent, false, true, divSongPart);
                                lines.add(bgLine);
                            }

                            final List<RomajiSyllable> lineSidecar =
                                    findSidecarRoman(lineId, sidecarRoman);
                            final String romaText = buildLineRomaji(
                                    pl.words, lineSidecar);
                            romanization.add(new LyricsLine(
                                    LyricsLine.NO_TIME, romaText));
                            for (int i = 0; i < pl.bgLines.size(); i++) {
                                romanization.add(new LyricsLine(LyricsLine.NO_TIME, ""));
                            }

                            buildRomanizations(lineId, sidecarRoman, pl.words, romanizations);

                            // Pass first BG line to buildTranslations (sidecar only)
                            final LyricsLine firstBg = pl.bgLines.isEmpty() ? null : pl.bgLines.get(0);
                            buildTranslations(
                                    lineId, sidecarTrans, firstBg,
                                    translations, mainLineCount);

                            // Merge inline translations into translations map
                            if (pl.inlineTranslations != null) {
                                for (Map.Entry<String, String> e : pl.inlineTranslations.entrySet()) {
                                    final String lang = e.getKey();
                                    final String text = e.getValue();
                                    if (text.isEmpty()) continue;
                                    final List<LyricsLine> langLines = translations.computeIfAbsent(
                                            lang, k -> new ArrayList<>());
                                    while (langLines.size() < mainLineCount - 1) {
                                        langLines.add(new LyricsLine(LyricsLine.NO_TIME, ""));
                                    }
                                    langLines.add(new LyricsLine(LyricsLine.NO_TIME, text));
                                }
                                // Ensure all existing languages have entries
                                for (Map.Entry<String, List<LyricsLine>> e : translations.entrySet()) {
                                    while (e.getValue().size() < lines.size()) {
                                        e.getValue().add(new LyricsLine(LyricsLine.NO_TIME, ""));
                                    }
                                }
                            }

                            // Merge inline romanizations into romanizations map
                            if (pl.inlineRomanizations != null) {
                                for (Map.Entry<String, String> e : pl.inlineRomanizations.entrySet()) {
                                    final String lang = e.getKey();
                                    final String text = e.getValue();
                                    if (text.isEmpty()) continue;
                                    final List<LyricsLine> langLines = romanizations.computeIfAbsent(
                                            lang, k -> new ArrayList<>());
                                    while (langLines.size() < mainLineCount - 1) {
                                        langLines.add(new LyricsLine(LyricsLine.NO_TIME, ""));
                                    }
                                    langLines.add(new LyricsLine(LyricsLine.NO_TIME, text));
                                }
                            }

                            // Merge BG inline translations into translations map
                            if (pl.bgInlineTranslations != null) {
                                for (Map.Entry<String, String> e : pl.bgInlineTranslations.entrySet()) {
                                    final String lang = "bg:" + e.getKey();
                                    final String text = e.getValue();
                                    if (text.isEmpty()) continue;
                                    final List<LyricsLine> langLines = translations.computeIfAbsent(
                                            lang, k -> new ArrayList<>());
                                    while (langLines.size() < mainLineCount) {
                                        langLines.add(new LyricsLine(LyricsLine.NO_TIME, ""));
                                    }
                                    langLines.add(new LyricsLine(LyricsLine.NO_TIME, text));
                                }
                            }

                            // Merge BG inline romanizations into romanizations map
                            if (pl.bgInlineRomanizations != null) {
                                for (Map.Entry<String, String> e : pl.bgInlineRomanizations.entrySet()) {
                                    final String lang = "bg:" + e.getKey();
                                    final String text = e.getValue();
                                    if (text.isEmpty()) continue;
                                    final List<LyricsLine> langLines = romanizations.computeIfAbsent(
                                            lang, k -> new ArrayList<>());
                                    while (langLines.size() < mainLineCount) {
                                        langLines.add(new LyricsLine(LyricsLine.NO_TIME, ""));
                                    }
                                    langLines.add(new LyricsLine(LyricsLine.NO_TIME, text));
                                }
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    final String local = localName(p.getName());
                    if ("head".equals(local)) {
                        inHead = false;
                    } else if ("body".equals(local)) {
                        inBody = false;
                    } else if ("div".equals(local)) {
                        divSongPart = null;
                    }
                }
                event = p.next();
            }

            if (lines.isEmpty()) {
                return null;
            }

            resolveDuet(lines, agentTypes);

            final Map<String, List<LyricsLine>> transOut =
                    translations.isEmpty() ? null : translations;
            final Map<String, List<LyricsLine>> romaOut =
                    romanizations.isEmpty() ? null : romanizations;

            final Map<String, String> agentNames;
            if (agentTypes.isEmpty()) {
                agentNames = null;
            } else {
                final Map<String, String> names = new HashMap<>();
                for (Map.Entry<String, AgentInfo> e : agentTypes.entrySet()) {
                    if (e.getValue().name != null) {
                        names.put(e.getKey(), e.getValue().name);
                    }
                }
                agentNames = names.isEmpty() ? null : names;
            }

            return new TtmlResult(lines,
                    LyricsMerge.hasText(romanization) ? romanization : null,
                    transOut, romaOut,
                    songwriters,
                    amllCreditLines.isEmpty() ? null : amllCreditLines,
                    agentNames);
        } catch (XmlPullParserException | IOException ex) {
            return null;
        }
    }

    private static List<String> parseSongwriters(String ttml)
            throws XmlPullParserException, IOException {
        final List<String> result = new ArrayList<>();
        final XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        final XmlPullParser p = factory.newPullParser();
        p.setInput(new StringReader(ttml));

        boolean inSongwriters = false;
        int depth = 0;
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("songwriters".equals(local)) {
                    inSongwriters = true;
                    depth = 1;
                } else if (inSongwriters) {
                    depth++;
                    if ("songwriter".equals(local)) {
                        final String name = readTextContent(p);
                        if (name != null && !name.trim().isEmpty()) {
                            result.add(name.trim());
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                if (inSongwriters) {
                    depth--;
                    if (depth <= 0) {
                        break;
                    }
                }
            }
            event = p.next();
        }
        return result;
    }

    private static List<String> parseAmllMetadata(String ttml) {
        final List<String> result = new ArrayList<>();
        try {
            final XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            final XmlPullParser p = factory.newPullParser();
            p.setInput(new StringReader(ttml));

            boolean inHead = false;
            int event = p.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    final String local = localName(p.getName());
                    if ("head".equals(local)) {
                        inHead = true;
                    } else if (inHead && "meta".equals(local)) {
                        // Match <amll:meta key="xxx" value="yyy"/> or <amll:meta key="xxx" value="yyy">
                        // by checking namespace
                        final String ns = p.getNamespace();
                        if (NS_AMLL.equals(ns)) {
                            final String key = getAttr(p, NS_AMLL, "key", "amll:key");
                            final String value = getAttr(p, NS_AMLL, "value", "amll:value");
                            if (key != null && value != null && !key.isEmpty() && !value.isEmpty()
                                    && !AMLL_EXCLUDED_KEYS.contains(key)) {
                                result.add(key + ": " + value.trim());
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    final String local = localName(p.getName());
                    if ("head".equals(local)) {
                        inHead = false;
                    }
                }
                event = p.next();
            }
        } catch (XmlPullParserException | IOException ignored) {
            // If parsing fails, return what we have
        }
        return result;
    }

    private static Map<String, AgentInfo> parseAgentTypes(String ttml)
            throws XmlPullParserException, IOException {
        final Map<String, AgentInfo> map = new HashMap<>();
        final XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        final XmlPullParser p = factory.newPullParser();
        p.setInput(new StringReader(ttml));

        boolean inHead = false;
        boolean inAgent = false;
        String agentId = null;
        String agentType = null;
        String agentName = null;

        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("head".equals(local)) {
                    inHead = true;
                } else if (inHead && "agent".equals(local)) {
                    agentId = getAttr(p, NS_TTM, "id", "xml:id");
                    agentType = getAttr(p, NS_TTM, "type", "ttm:type");
                    if (agentType == null) {
                        agentType = getAttr(p, null, "type", "type");
                    }
                    agentName = null;
                    inAgent = true;
                } else if (inAgent && "name".equals(local)) {
                    final String name = readTextContent(p);
                    if (name != null && !name.trim().isEmpty()) {
                        agentName = name.trim();
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                final String local = localName(p.getName());
                if ("head".equals(local)) {
                    inHead = false;
                } else if (inAgent && "agent".equals(local)) {
                    if (agentId != null && agentType != null) {
                        map.put(agentId, new AgentInfo(agentType, agentName));
                    }
                    inAgent = false;
                    agentId = null;
                    agentType = null;
                    agentName = null;
                }
            }
            event = p.next();
        }
        return map;
    }

    private static void resolveDuet(List<LyricsLine> lines, Map<String, AgentInfo> agentTypes) {
        String lastPersonAgentId = null;
        boolean lastPersonIsDuet = false;

        for (int i = 0; i < lines.size(); i++) {
            final LyricsLine original = lines.get(i);
            final String agentId = original.agentId();
            if (agentId == null) {
                continue;
            }

            final int agentNum = extractAgentNumber(agentId);
            boolean isDuet;

            if (agentNum > 0) {
                isDuet = (agentNum % 2 == 0);
            } else {
                final AgentInfo info = agentTypes.get(agentId);
                final String type = info != null ? info.type : AGENT_TYPE_PERSON;

                if (AGENT_TYPE_GROUP.equals(type)) {
                    isDuet = false;
                } else if (lastPersonAgentId == null) {
                    isDuet = AGENT_TYPE_OTHER.equals(type);
                } else if (agentId.equals(lastPersonAgentId)) {
                    isDuet = lastPersonIsDuet;
                } else {
                    isDuet = !lastPersonIsDuet;
                }

                if (!AGENT_TYPE_GROUP.equals(type)) {
                    lastPersonAgentId = agentId;
                    lastPersonIsDuet = isDuet;
                }
            }

            lines.set(i, new LyricsLine(
                    original.startTimeMs(), original.endTimeMs(), original.text(),
                    original.words(), agentId, isDuet, original.isBG(),
                    original.songPart()));
        }
    }

    /** Extracts trailing integer from agent ID (e.g. "v1" → 1, "singer2" → 2). Returns 0 if none. */
    private static int extractAgentNumber(String agentId) {
        int end = agentId.length();
        while (end > 0 && Character.isDigit(agentId.charAt(end - 1))) {
            end--;
        }
        if (end == agentId.length()) return 0;
        try {
            return Integer.parseInt(agentId.substring(end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, List<RomajiSyllable>> parseSidecarTransliterations(String ttml)
            throws XmlPullParserException, IOException {
        final Map<String, List<RomajiSyllable>> map = new HashMap<>();
        final XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        final XmlPullParser p = factory.newPullParser();
        p.setInput(new StringReader(ttml));

        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("transliterations".equals(local)) {
                    collectSidecarTransliterations(p, map);
                }
            }
            event = p.next();
        }
        return map;
    }

    private static void collectSidecarTransliterations(XmlPullParser p,
            Map<String, List<RomajiSyllable>> map)
            throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("transliteration".equals(local)) {
                    final String lang = getAttr(p, NS_XML, "lang", "xml:lang");
                    collectOneTransliteration(p, lang, map);
                    continue;
                }
                depth++;
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            }
        }
    }

    private static void collectOneTransliteration(XmlPullParser p,
            @Nullable String lang,
            Map<String, List<RomajiSyllable>> map)
            throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("text".equals(local)) {
                    final String forKey = getAttr(p, null, "for", "for");
                    if (forKey != null && !forKey.isEmpty()) {
                        final List<RomajiSyllable> syllables = parseTransliterationText(p);
                        if (syllables != null && !syllables.isEmpty()) {
                            final String key = (lang != null && !lang.isEmpty())
                                    ? lang + ":" + forKey : forKey;
                            map.put(key, syllables);
                            continue;
                        }
                    }
                    skipElement(p);
                } else {
                    depth++;
                }
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            }
        }
    }

    private static List<RomajiSyllable> parseTransliterationText(XmlPullParser p)
            throws XmlPullParserException, IOException {
        final List<RomajiSyllable> syllables = new ArrayList<>();
        int depth = 1;
        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("span".equals(local)) {
                    final String begin = getAttr(p, null, "begin", "begin");
                    final String end = getAttr(p, null, "end", "end");
                    if (begin != null && end != null) {
                        final String text = normalizeText(readTextContent(p));
                        if (!text.isEmpty()) {
                            syllables.add(new RomajiSyllable(
                                    parseTime(begin), parseTime(end), text));
                        }
                    } else {
                        skipElement(p);
                    }
                } else {
                    depth++;
                }
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            } else if (event == XmlPullParser.TEXT) {
                final String text = p.getText();
                if (text != null) {
                    final String trimmed = normalizeText(text);
                    if (!trimmed.isEmpty()) {
                        syllables.add(new RomajiSyllable(0, 0, trimmed));
                    }
                }
            }
        }
        return syllables;
    }

    private static Map<String, SidecarTranslation> parseSidecarTranslations(String ttml)
            throws XmlPullParserException, IOException {
        final Map<String, SidecarTranslation> map = new HashMap<>();
        final XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        final XmlPullParser p = factory.newPullParser();
        p.setInput(new StringReader(ttml));

        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("translations".equals(local)) {
                    collectSidecarTranslations(p, map);
                }
            }
            event = p.next();
        }
        return map;
    }

    private static void collectSidecarTranslations(XmlPullParser p,
            Map<String, SidecarTranslation> map)
            throws XmlPullParserException, IOException {
        int depth = 1;
        int eventCount = 0;
        while (depth > 0 && eventCount < 20) {
            final int event = p.next();
            eventCount++;

            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("translation".equals(local)) {
                    final String lang = getAttr(p, NS_XML, "lang", "xml:lang");
                    if (lang != null && !lang.isEmpty()) {
                        collectOneTranslation(p, lang, map);
                        continue;
                    }
                    skipElement(p);
                } else {
                    depth++;
                }
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            }
        }
    }

    private static void collectOneTranslation(XmlPullParser p, String lang,
            Map<String, SidecarTranslation> map)
            throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("text".equals(local)) {
                    final String forKey = getAttr(p, null, "for", "for");
                    if (forKey != null && !forKey.isEmpty()) {
                        final SidecarTranslation st = parseTranslationText(p);
                        if (st != null && !st.text.isEmpty()) {
                            map.put(lang + ":" + forKey, st);
                        }
                        continue;
                    }
                    skipElement(p);
                } else {
                    depth++;
                }
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            }
        }
    }

    private static SidecarTranslation parseTranslationText(XmlPullParser p)
            throws XmlPullParserException, IOException {
        final StringBuilder mainFullText = new StringBuilder();
        final List<Word> mainWords = new ArrayList<>();
        final StringBuilder bgFullText = new StringBuilder();
        final List<Word> bgWords = new ArrayList<>();
        boolean inBg = false;
        int bgWrapperDepth = 0;

        boolean inMainWord = false;
        final StringBuilder mainWordBuf = new StringBuilder();
        long mainWordBegin = 0, mainWordEnd = 0;

        boolean inBgWord = false;
        final StringBuilder bgWordBuf = new StringBuilder();
        long bgWordBegin = 0, bgWordEnd = 0;

        int depth = 1;

        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.START_TAG) {
                depth++;
                final String local = localName(p.getName());
                final String role = getAttr(p, NS_TTM, "role", "ttm:role");

                if ("x-bg".equals(role)) {
                    inBg = true;
                } else if ("span".equals(local)) {
                    final String beginAttr = getAttr(p, null, "begin", "begin");
                    final String endAttr = getAttr(p, null, "end", "end");
                    if (beginAttr != null && endAttr != null) {
                        if (inBg) {
                            inBgWord = true;
                            bgWordBuf.setLength(0);
                            bgWordBegin = parseTime(beginAttr);
                            bgWordEnd = parseTime(endAttr);
                        } else {
                            inMainWord = true;
                            mainWordBuf.setLength(0);
                            mainWordBegin = parseTime(beginAttr);
                            mainWordEnd = parseTime(endAttr);
                        }
                    } else if (inBg) {
                        bgWrapperDepth++;
                    } else {
                        depth++; // plain wrapper span
                    }
                } else {
                    depth++;
                }
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
                if (depth <= 1) {
                    // Closing main or bg span
                    if (inBgWord) {
                        inBgWord = false;
                        final String normalized = normalizeTextRaw(bgWordBuf.toString());
                        final boolean startsWithSpace = !normalized.isEmpty()
                                && Character.isWhitespace(normalized.charAt(0));
                        final boolean endsWithSpace = !normalized.isEmpty()
                                && Character.isWhitespace(normalized.charAt(normalized.length() - 1));
                        final String text = normalized.trim();
                        bgFullText.append(normalized);
                        if (startsWithSpace && !bgWords.isEmpty()) {
                            final Word prev = bgWords.get(bgWords.size() - 1);
                            bgWords.set(bgWords.size() - 1,
                                    new Word(prev.startMs(), prev.endMs(), prev.text(),
                                            prev.romaji(), true));
                        }
                        if (!text.isEmpty()) {
                            bgWords.add(new Word(bgWordBegin, bgWordEnd, text, null,
                                    endsWithSpace));
                        }
                    } else if (inMainWord) {
                        inMainWord = false;
                        final String normalized = normalizeTextRaw(mainWordBuf.toString());
                        final boolean startsWithSpace = !normalized.isEmpty()
                                && Character.isWhitespace(normalized.charAt(0));
                        final boolean endsWithSpace = !normalized.isEmpty()
                                && Character.isWhitespace(normalized.charAt(normalized.length() - 1));
                        final String text = normalized.trim();
                        mainFullText.append(normalized);
                        if (startsWithSpace && !mainWords.isEmpty()) {
                            final Word prev = mainWords.get(mainWords.size() - 1);
                            mainWords.set(mainWords.size() - 1,
                                    new Word(prev.startMs(), prev.endMs(), prev.text(),
                                            prev.romaji(), true));
                        }
                        if (!text.isEmpty()) {
                            mainWords.add(new Word(mainWordBegin, mainWordEnd, text, null,
                                    endsWithSpace));
                        }
                    } else if (inBg && bgWrapperDepth > 0) {
                        bgWrapperDepth--;
                    } else if (inBg) {
                        inBg = false;
                    }
                }
            } else if (event == XmlPullParser.TEXT) {
                final String raw = p.getText();
                if (raw == null || raw.isEmpty()) continue;
                if (inBgWord) {
                    bgWordBuf.append(raw);
                } else if (inMainWord) {
                    mainWordBuf.append(raw);
                } else if (inBg && bgWrapperDepth == 0) {
                    bgFullText.append(raw);
                    // Whitespace between bg word spans
                    if (!raw.contains("\n") && raw.trim().isEmpty() && !bgWords.isEmpty()) {
                        final Word prev = bgWords.get(bgWords.size() - 1);
                        if (!prev.endsWithSpace()) {
                            bgWords.set(bgWords.size() - 1, new Word(
                                    prev.startMs(), prev.endMs(),
                                    prev.text(), prev.romaji(), true));
                        }
                    }
                } else if (!inBg) {
                    mainFullText.append(raw);
                    // Whitespace between main word spans
                    if (!raw.contains("\n") && raw.trim().isEmpty() && !mainWords.isEmpty()) {
                        final Word prev = mainWords.get(mainWords.size() - 1);
                        if (!prev.endsWithSpace()) {
                            mainWords.set(mainWords.size() - 1, new Word(
                                    prev.startMs(), prev.endMs(),
                                    prev.text(), prev.romaji(), true));
                        }
                    }
                }
            }
        }

        final String main = normalizeText(mainFullText.toString());
        final String bg = normalizeText(bgFullText.toString());

        if (!mainWords.isEmpty()) {
            final Word first = mainWords.get(0);
            if (first.text().startsWith(" ")) {
                mainWords.set(0, new Word(first.startMs(), first.endMs(),
                        first.text().substring(1).trim(), first.romaji(), first.endsWithSpace()));
            }
            final int lastIdx = mainWords.size() - 1;
            final Word last = mainWords.get(lastIdx);
            if (last.text().endsWith(" ") || last.endsWithSpace()) {
                mainWords.set(lastIdx, new Word(last.startMs(), last.endMs(),
                        last.text().trim(), last.romaji(), false));
            }
        }

        if (!bgWords.isEmpty()) {
            final Word first = bgWords.get(0);
            if (first.text().startsWith(" ")) {
                bgWords.set(0, new Word(first.startMs(), first.endMs(),
                        first.text().substring(1).trim(), first.romaji(), first.endsWithSpace()));
            }
            final int lastIdx = bgWords.size() - 1;
            final Word last = bgWords.get(lastIdx);
            if (last.text().endsWith(" ") || last.endsWithSpace()) {
                bgWords.set(lastIdx, new Word(last.startMs(), last.endMs(),
                        last.text().trim(), last.romaji(), false));
            }
        }

        final List<Word> finalMainWords = (mainWords.size() == 1
                && mainWords.get(0).startMs() == 0 && mainWords.get(0).endMs() == 0)
                ? null : (mainWords.isEmpty() ? null : mainWords);
        final List<Word> finalBgWords = (bgWords.size() == 1
                && bgWords.get(0).startMs() == 0 && bgWords.get(0).endMs() == 0)
                ? null : (bgWords.isEmpty() ? null : bgWords);

        return new SidecarTranslation(main, finalMainWords,
                bg.isEmpty() ? null : bg, finalBgWords);
    }

    private static final class ParsedLine {
        final long begin;
        final long end;
        final String text;
        final List<Word> words;
        final List<LyricsLine> bgLines;
        @Nullable final String bgAgentId;
        @Nullable final Map<String, String> inlineTranslations;
        @Nullable final Map<String, String> inlineRomanizations;
        @Nullable final Map<String, String> bgInlineTranslations;
        @Nullable final Map<String, String> bgInlineRomanizations;

        ParsedLine(long begin, long end, String text, List<Word> words,
                   List<LyricsLine> bgLines,
                   @Nullable String bgAgentId,
                   @Nullable Map<String, String> inlineTranslations,
                   @Nullable Map<String, String> inlineRomanizations,
                   @Nullable Map<String, String> bgInlineTranslations,
                   @Nullable Map<String, String> bgInlineRomanizations) {
            this.begin = begin;
            this.end = end;
            this.text = text;
            this.words = words;
            this.bgLines = bgLines;
            this.bgAgentId = bgAgentId;
            this.inlineTranslations = inlineTranslations;
            this.inlineRomanizations = inlineRomanizations;
            this.bgInlineTranslations = bgInlineTranslations;
            this.bgInlineRomanizations = bgInlineRomanizations;
        }
    }

    private static ParsedLine processPElement(XmlPullParser p, String rootTiming,
            String lineId, long pBegin, long pEnd, boolean hasTimeAttrs)
            throws XmlPullParserException, IOException {

        final List<Word> words = new ArrayList<>();
        final StringBuilder fullText = new StringBuilder();

        // Inline translation/roman state
        boolean inTranslation = false;
        final StringBuilder transBuf = new StringBuilder();
        String transLang = null;
        boolean inRoman = false;
        final StringBuilder romanBuf = new StringBuilder();
        String romanLang = null;

        // Collected inline translations/romanizations for this <p>
        final Map<String, String> inlineTrans = new HashMap<>();
        final Map<String, String> inlineRoma = new HashMap<>();
        final Map<String, String> bgInlineTrans = new HashMap<>();
        final Map<String, String> bgInlineRoma = new HashMap<>();

        // Background vocal state
        boolean inBg = false;
        final List<Word> bgWords = new ArrayList<>();
        final StringBuilder bgFullText = new StringBuilder();
        final StringBuilder bgWordBuf = new StringBuilder();
        long bgWordBegin = 0, bgWordEnd = 0;
        int bgWrapperDepth = 0;
        String bgAgentId = null;
        long bgBeginMs = 0, bgEndMs = 0;
        final StringBuilder bgTransBuf = new StringBuilder();
        final StringBuilder bgRomanBuf = new StringBuilder();
        boolean inBgTranslation = false;
        boolean inBgRoman = false;
        boolean inBgWord = false;
        final List<LyricsLine> bgLines = new ArrayList<>();

        boolean inWord = false;
        final StringBuilder wordBuf = new StringBuilder();
        long wordBegin = 0, wordEnd = 0;
        int wrapperDepth = 0;

        boolean inRubyContainer = false;
        boolean inRubyBase = false;
        final StringBuilder rubyBaseBuf = new StringBuilder();
        boolean inRubyTextContainer = false;
        boolean inRubyText = false;
        final StringBuilder rubyTextBuf = new StringBuilder();
        long rubyTextBegin = 0, rubyTextEnd = 0;
        final List<RomajiSyllable> rubyTags = new ArrayList<>();

        int depth = 1;

        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.START_TAG) {
                depth++;
                final String local = localName(p.getName());
                if (!"span".equals(local)) {
                    continue;
                }

                final String role = getAttr(p, NS_TTM, "role", "ttm:role");
                final String ruby = getAttr(p, NS_TTS, "ruby", "tts:ruby");
                final String beginAttr = getAttr(p, null, "begin", "begin");
                final String endAttr = getAttr(p, null, "end", "end");

                if (inBg) {
                    if ("x-translation".equals(role)) {
                        inBgTranslation = true;
                        bgTransBuf.setLength(0);
                    } else if ("x-roman".equals(role)) {
                        inBgRoman = true;
                        bgRomanBuf.setLength(0);
                    } else if (beginAttr != null && endAttr != null) {
                        inBgWord = true;
                        bgWordBuf.setLength(0);
                        bgWordBegin = parseTime(beginAttr);
                        bgWordEnd = parseTime(endAttr);
                    } else {
                        bgWrapperDepth++;
                    }
                } else if ("x-bg".equals(role)) {
                    // Save previous BG section if any
                    if (inBg && (!bgWords.isEmpty() || bgFullText.length() > 0)) {
                        String prevBgText = normalizeText(bgFullText.toString());
                        if (!prevBgText.isBlank()) {
                            prevBgText = prevBgText.replaceAll("^[(（]+", "").replaceAll("[)）]+$", "").trim();
                            stripBgWordParens(bgWords);
                            if (!prevBgText.isEmpty()) {
                                final long bgStart = bgBeginMs > 0 ? bgBeginMs : pBegin;
                                final long bgEnd = bgEndMs > 0 ? bgEndMs : pEnd;
                                bgLines.add(new LyricsLine(bgStart, bgEnd, prevBgText, new ArrayList<>(bgWords)));
                            }
                        }
                    }
                    inBg = true;
                    bgWords.clear();
                    bgFullText.setLength(0);
                    bgWrapperDepth = 0;
                    bgAgentId = getAttr(p, NS_TTM, "agent", "ttm:agent");
                    bgBeginMs = parseTime(beginAttr);
                    bgEndMs = parseTime(endAttr);
                } else if ("x-translation".equals(role)) {
                    inTranslation = true;
                    transBuf.setLength(0);
                    transLang = getAttr(p, NS_XML, "lang", "xml:lang");
                } else if ("x-roman".equals(role)) {
                    inRoman = true;
                    romanBuf.setLength(0);
                    romanLang = getAttr(p, NS_XML, "lang", "xml:lang");
                } else if ("container".equals(ruby)) {
                    inRubyContainer = true;
                    rubyBaseBuf.setLength(0);
                    rubyTags.clear();
                } else if ("base".equals(ruby) && inRubyContainer) {
                    inRubyBase = true;
                    rubyBaseBuf.setLength(0);
                } else if ("textContainer".equals(ruby) && inRubyContainer) {
                    inRubyTextContainer = true;
                } else if ("text".equals(ruby) && inRubyTextContainer) {
                    inRubyText = true;
                    rubyTextBuf.setLength(0);
                    rubyTextBegin = parseTime(beginAttr);
                    rubyTextEnd = parseTime(endAttr);
                } else if (beginAttr != null && endAttr != null) {
                    inWord = true;
                    wordBuf.setLength(0);
                    wordBegin = parseTime(beginAttr);
                    wordEnd = parseTime(endAttr);
                } else {
                    wrapperDepth++;
                }
            } else if (event == XmlPullParser.END_TAG) {
                final String local = localName(p.getName());

                if ("span".equals(local)) {
                    if (inBgTranslation) {
                        inBgTranslation = false;
                        final String btText = bgTransBuf.toString().trim();
                        if (!btText.isEmpty()) {
                            bgInlineTrans.merge("bg", btText, (a, b) -> a + " " + b);
                        }
                    } else if (inBgRoman) {
                        inBgRoman = false;
                        final String brText = bgRomanBuf.toString().trim();
                        if (!brText.isEmpty()) {
                            bgInlineRoma.merge("bg", brText, (a, b) -> a + " " + b);
                        }
                    } else if (inBgWord) {
                        inBgWord = false;
                        final String rawBgWord = bgWordBuf.toString();
                        final String normalized = normalizeTextRaw(rawBgWord);
                        final boolean isFormatting = rawBgWord.contains("\n");
                        final boolean startsWithSpace = !isFormatting && !normalized.isEmpty()
                                && Character.isWhitespace(normalized.charAt(0));
                        final boolean endsWithSpace = !isFormatting && !normalized.isEmpty()
                                && Character.isWhitespace(normalized.charAt(normalized.length() - 1));
                        final String text = normalized.trim();
                        bgFullText.append(normalized);
                        if (startsWithSpace && !bgWords.isEmpty()) {
                            final Word prev = bgWords.get(bgWords.size() - 1);
                            bgWords.set(bgWords.size() - 1,
                                    new Word(prev.startMs(), prev.endMs(), prev.text(),
                                            prev.romaji(), true));
                        }
                        if (!text.isEmpty()) {
                            bgWords.add(new Word(bgWordBegin, bgWordEnd, text, null,
                                    endsWithSpace));
                        }
                    } else if (inBg && bgWrapperDepth > 0) {
                        bgWrapperDepth--;
                    } else if (inBg) {
                        inBg = false;
                    } else if (inRubyText && inRubyContainer) {
                        inRubyText = false;
                        final String text = normalizeText(rubyTextBuf.toString());
                        if (!text.isEmpty()) {
                            rubyTags.add(new RomajiSyllable(
                                    rubyTextBegin, rubyTextEnd, text));
                        }
                    } else if (inRubyTextContainer && inRubyContainer) {
                        inRubyTextContainer = false;
                    } else if (inRubyBase && inRubyContainer) {
                        inRubyBase = false;
                    } else if (inRubyContainer) {
                        inRubyContainer = false;
                        final String baseText = normalizeText(rubyBaseBuf.toString());
                        if (!baseText.isEmpty()) {
                            long rBegin = 0, rEnd = 0;
                            if (!rubyTags.isEmpty()) {
                                rBegin = rubyTags.get(0).startMs;
                                rEnd = rubyTags.get(rubyTags.size() - 1).endMs;
                            }
                            final StringBuilder rRoma = new StringBuilder();
                            for (RomajiSyllable rs : rubyTags) {
                                if (rRoma.length() > 0) rRoma.append(' ');
                                rRoma.append(rs.text);
                            }
                            final String romaji = rRoma.length() > 0 ? rRoma.toString() : null;

                            fullText.append(baseText);
                            words.add(new Word(rBegin, rEnd, baseText, romaji, false));
                        }
                    } else if (inTranslation) {
                        inTranslation = false;
                        final String tText = transBuf.toString().trim();
                        if (!tText.isEmpty() && transLang != null) {
                            inlineTrans.merge(transLang, tText, (a, b) -> a + " " + b);
                        }
                    } else if (inRoman) {
                        inRoman = false;
                        final String rText = romanBuf.toString().trim();
                        if (!rText.isEmpty() && romanLang != null) {
                            inlineRoma.merge(romanLang, rText, (a, b) -> a + " " + b);
                        }
                    } else if (inWord) {
                        inWord = false;
                        final String rawWord = wordBuf.toString();
                        final String normalized = normalizeTextRaw(rawWord);
                        final boolean isFormatting = rawWord.contains("\n");
                        final boolean startsWithSpace = !isFormatting && !normalized.isEmpty()
                                && Character.isWhitespace(normalized.charAt(0));
                        final boolean endsWithSpace = !isFormatting && !normalized.isEmpty()
                                && Character.isWhitespace(normalized.charAt(normalized.length() - 1));
                        final String text = normalized.trim();
                        fullText.append(normalized);
                        if (startsWithSpace && !words.isEmpty()) {
                            final Word prev = words.get(words.size() - 1);
                            words.set(words.size() - 1,
                                    new Word(prev.startMs(), prev.endMs(), prev.text(),
                                            prev.romaji(), true));
                        }
                        if (!text.isEmpty()) {
                            words.add(new Word(wordBegin, wordEnd, text, null,
                                    endsWithSpace));
                        }
                    } else if (wrapperDepth > 0) {
                        wrapperDepth--;
                    }
                }
                depth--;
            } else if (event == XmlPullParser.TEXT) {
                final String raw = p.getText();
                if (raw == null || raw.isEmpty()) continue;

                if (inBgTranslation) {
                    bgTransBuf.append(raw);
                } else if (inBgRoman) {
                    bgRomanBuf.append(raw);
                } else if (inBgWord) {
                    bgWordBuf.append(raw);
                } else if (inBg && bgWrapperDepth == 0) {
                    // Formatting newlines (whitespace + \n) between BG word spans
                    if (!raw.contains("\n") || !raw.trim().isEmpty()) {
                        bgFullText.append(raw);
                    }
                    // Whitespace-only (non-newline) between BG word spans
                    if (!raw.contains("\n") && raw.trim().isEmpty() && !bgWords.isEmpty()) {
                        final Word prev = bgWords.get(bgWords.size() - 1);
                        if (!prev.endsWithSpace()) {
                            bgWords.set(bgWords.size() - 1, new Word(
                                    prev.startMs(), prev.endMs(),
                                    prev.text(), prev.romaji(), true));
                        }
                    }
                } else if (inRubyBase && inRubyContainer) {
                    rubyBaseBuf.append(raw);
                } else if (inRubyText && inRubyContainer) {
                    rubyTextBuf.append(raw);
                } else if (inTranslation) {
                    transBuf.append(raw);
                } else if (inRoman) {
                    romanBuf.append(raw);
                } else if (inWord) {
                    wordBuf.append(raw);
                } else if (!inBg || bgWrapperDepth == 0) {
                    // Formatting newlines (whitespace + \n) between word spans — skip entirely
                    if (raw.contains("\n") && raw.trim().isEmpty()) {
                        // skip
                    } else {
                        fullText.append(raw);
                        // Whitespace-only (non-newline) between word spans → trailing space
                        if (!raw.contains("\n") && raw.trim().isEmpty() && !words.isEmpty()) {
                            final Word prev = words.get(words.size() - 1);
                            if (!prev.endsWithSpace()) {
                                words.set(words.size() - 1, new Word(
                                        prev.startMs(), prev.endMs(),
                                        prev.text(), prev.romaji(), true));
                            }
                        }
                    }
                }
            }
        }

        final String lineText = normalizeText(fullText.toString());
        if (lineText.isBlank()) {
            return null;
        }

        // Infer time range from children when <p> has no timing
        long effectiveBegin = pBegin;
        long effectiveEnd = pEnd;
        if ((effectiveBegin == 0 || effectiveEnd == 0) && !words.isEmpty()) {
            long minStart = Long.MAX_VALUE;
            long maxEnd = 0;
            for (Word w : words) {
                if (w.startMs() > 0 && w.startMs() < minStart) minStart = w.startMs();
                if (w.endMs() > 0 && w.endMs() > maxEnd) maxEnd = w.endMs();
            }
            if (minStart < Long.MAX_VALUE && maxEnd > 0) {
                if (effectiveBegin == 0 || (minStart > 0 && minStart < effectiveBegin)) {
                    effectiveBegin = minStart;
                }
                if (effectiveEnd == 0 || maxEnd > effectiveEnd) {
                    effectiveEnd = maxEnd;
                }
            }
        }

        if (words.isEmpty() && !lineText.isEmpty() && hasTimeAttrs && effectiveEnd > effectiveBegin) {
            words.add(new Word(effectiveBegin, effectiveEnd, lineText, null, false));
        }

        if (!words.isEmpty()) {
            final Word first = words.get(0);
            if (first.text().startsWith(" ")) {
                words.set(0, new Word(first.startMs(), first.endMs(),
                        first.text().substring(1).trim(), first.romaji(), first.endsWithSpace()));
            }
            final int lastIdx = words.size() - 1;
            final Word last = words.get(lastIdx);
            if (last.text().endsWith(" ") || last.endsWithSpace()) {
                words.set(lastIdx, new Word(last.startMs(), last.endMs(),
                        last.text().trim(), last.romaji(), false));
            }
        }

        // Save final BG section
        if (inBg && (!bgWords.isEmpty() || bgFullText.length() > 0)) {
            String bgText = normalizeText(bgFullText.toString());
            if (!bgText.isBlank()) {
                bgText = bgText.replaceAll("^[(（]+", "").replaceAll("[)）]+$", "").trim();
                stripBgWordParens(bgWords);
                if (!bgText.isEmpty()) {
                    final long bgStart = bgBeginMs > 0 ? bgBeginMs : pBegin;
                    final long bgEnd = bgEndMs > 0 ? bgEndMs : pEnd;
                    bgLines.add(new LyricsLine(bgStart, bgEnd, bgText, bgWords));
                }
            }
        }

        return new ParsedLine(effectiveBegin, effectiveEnd, lineText, words, bgLines,
                bgAgentId,
                inlineTrans.isEmpty() ? null : inlineTrans,
                inlineRoma.isEmpty() ? null : inlineRoma,
                bgInlineTrans.isEmpty() ? null : bgInlineTrans,
                bgInlineRoma.isEmpty() ? null : bgInlineRoma);
    }

    private static void stripBgWordParens(List<Word> bgWords) {
        if (bgWords.isEmpty()) return;
        final Word first = bgWords.get(0);
        final String strippedFirst = first.text().replaceAll("^[(（]+", "").stripLeading();
        if (!strippedFirst.equals(first.text())) {
            bgWords.set(0, new Word(first.startMs(), first.endMs(),
                    strippedFirst, first.romaji(), first.endsWithSpace()));
        }
        final int lastIdx = bgWords.size() - 1;
        final Word last = bgWords.get(lastIdx);
        final String strippedLast = last.text().replaceAll("[)）]+$", "").stripTrailing();
        if (!strippedLast.equals(last.text())) {
            bgWords.set(lastIdx, new Word(last.startMs(), last.endMs(),
                    strippedLast, last.romaji(), false));
        }
    }

    private static void alignRomajiToWords(List<Word> words,
            List<RomajiSyllable> sidecar) {
        if (words.isEmpty() || sidecar.isEmpty()) {
            return;
        }

        int romanSearchStart = 0;

        for (int i = 0; i < words.size(); i++) {
            final Word main = words.get(i);
            double maxIou = 0;
            int bestIdx = -1;
            boolean fastMatched = false;

            int j = romanSearchStart;
            while (j < sidecar.size()) {
                final RomajiSyllable sub = sidecar.get(j);

                // Fast track: start times match within 2ms
                if (Math.abs(main.startMs() - sub.startMs) <= FAST_TRACK_MS) {
                    words.set(i, new Word(main.startMs(), main.endMs(), main.text(),
                            sub.text, main.endsWithSpace()));
                    romanSearchStart = j + 1;
                    fastMatched = true;
                    break;
                }

                // IoU computation
                final long overlapStart = Math.max(main.startMs(), sub.startMs);
                final long overlapEnd = Math.min(main.endMs(), sub.endMs);
                final long intersection = Math.max(0, overlapEnd - overlapStart);

                if (intersection > 0) {
                    final long unionStart = Math.min(main.startMs(), sub.startMs);
                    final long unionEnd = Math.max(main.endMs(), sub.endMs);
                    final double iou = (double) intersection / Math.max(1, unionEnd - unionStart);
                    if (iou > maxIou) {
                        maxIou = iou;
                        bestIdx = j;
                    }
                }

                if (sub.startMs >= main.endMs()) {
                    break;
                }
                j++;
            }

            if (!fastMatched && bestIdx >= 0 && maxIou >= MIN_IOU) {
                final RomajiSyllable sub = sidecar.get(bestIdx);
                words.set(i, new Word(main.startMs(), main.endMs(), main.text(),
                        sub.text, main.endsWithSpace()));
                romanSearchStart = bestIdx + 1;
            }
        }
    }

    @Nullable
    private static List<RomajiSyllable> findSidecarRoman(String lineId,
            Map<String, List<RomajiSyllable>> sidecarRoman) {
        final List<RomajiSyllable> exact = sidecarRoman.get(lineId);
        if (exact != null) return exact;
        for (Map.Entry<String, List<RomajiSyllable>> e : sidecarRoman.entrySet()) {
            final String key = e.getKey();
            final int sep = key.indexOf(':');
            if (sep > 0 && key.substring(sep + 1).equals(lineId)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String buildLineRomaji(List<Word> words,
            @Nullable List<RomajiSyllable> sidecar) {
        final StringBuilder perWord = new StringBuilder();
        boolean hasPerWord = false;
        for (Word w : words) {
            if (w.romaji() != null && !w.romaji().isEmpty()) {
                hasPerWord = true;
                break;
            }
        }
        if (hasPerWord) {
            for (int i = 0; i < words.size(); i++) {
                final Word w = words.get(i);
                final String r = w.romaji();
                if (r != null && !r.isEmpty()) {
                    if (perWord.length() > 0) perWord.append(' ');
                    perWord.append(r);
                }
            }
            if (perWord.length() > 0) {
                return perWord.toString();
            }
        }

        if (sidecar != null && !sidecar.isEmpty()) {
            final StringBuilder sb = new StringBuilder();
            for (RomajiSyllable s : sidecar) {
                if (s.text.isEmpty()) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(s.text);
            }
            final String result = sb.toString().trim();
            if (!result.isEmpty()) {
                return result;
            }
        }

        return "";
    }

    private static void buildRomanizations(String lineId,
            Map<String, List<RomajiSyllable>> sidecarRoman,
            List<Word> words,
            Map<String, List<LyricsLine>> romanizations) {
        // Find all sidecar romanization entries for this line
        // Keys may be "lang:lineId" or just "lineId" (no language)
        for (Map.Entry<String, List<RomajiSyllable>> entry : sidecarRoman.entrySet()) {
            final String key = entry.getKey();
            final String forId;
            final String lang;

            final int sep = key.indexOf(':');
            if (sep > 0) {
                lang = key.substring(0, sep);
                forId = key.substring(sep + 1);
            } else {
                lang = null;
                forId = key;
            }

            if (!lineId.equals(forId)) continue;

            final List<RomajiSyllable> sidecar = entry.getValue();
            if (sidecar.isEmpty()) continue;

            if (sidecar.size() == 1 && sidecar.get(0).startMs == 0 && sidecar.get(0).endMs == 0) {
                continue;
            }

            final List<Word> alignedWords = new ArrayList<>(words);
            alignRomajiToWords(alignedWords, sidecar);

            final StringBuilder sb = new StringBuilder();
            for (Word w : alignedWords) {
                if (w.romaji() != null && !w.romaji().isEmpty()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(w.romaji());
                }
            }
            final String text = sb.toString().trim();
            if (!text.isEmpty()) {
                final String langKey = lang != null ? lang : "romaji";
                final List<LyricsLine> langLines = romanizations.computeIfAbsent(
                        langKey, k -> new ArrayList<>());
                langLines.add(new LyricsLine(LyricsLine.NO_TIME, text));
            }
        }
    }

    private static void buildTranslations(String lineId,
            Map<String, SidecarTranslation> sidecarTrans,
            @Nullable LyricsLine bgLine,
            Map<String, List<LyricsLine>> translations, int lineCount) {

        final Map<String, SidecarTranslation> mainByLang = new HashMap<>();
        final Map<String, SidecarTranslation> bgByLang = new HashMap<>();

        for (Map.Entry<String, SidecarTranslation> entry : sidecarTrans.entrySet()) {
            final String key = entry.getKey();
            final int sep = key.indexOf(':');
            if (sep < 0) continue;
            final String lang = key.substring(0, sep);
            final String forId = key.substring(sep + 1);
            if (!lineId.equals(forId)) continue;

            final SidecarTranslation st = entry.getValue();
            mainByLang.put(lang, st);
            if (st.bgText != null) {
                bgByLang.put(lang, st);
            }
        }

        // Add main translations (with word-level timing if available)
        for (Map.Entry<String, SidecarTranslation> entry : mainByLang.entrySet()) {
            final String lang = entry.getKey();
            final SidecarTranslation st = entry.getValue();
            if (st.text.isEmpty()) continue;
            final List<LyricsLine> langLines = translations.computeIfAbsent(
                    lang, k -> new ArrayList<>());
            while (langLines.size() < lineCount - 1) {
                langLines.add(new LyricsLine(LyricsLine.NO_TIME, ""));
            }
            langLines.add(new LyricsLine(LyricsLine.NO_TIME, st.text,
                    st.words != null ? st.words : null));
        }

        // Add BG translations (from sidecar) — prefixed with "bg:" to distinguish
        for (Map.Entry<String, SidecarTranslation> entry : bgByLang.entrySet()) {
            final String lang = "bg:" + entry.getKey();
            final SidecarTranslation st = entry.getValue();
            if (st.bgText == null || st.bgText.isEmpty()) continue;
            final List<LyricsLine> langLines = translations.computeIfAbsent(
                    lang, k -> new ArrayList<>());
            while (langLines.size() < lineCount) {
                langLines.add(new LyricsLine(LyricsLine.NO_TIME, ""));
            }
            langLines.add(new LyricsLine(LyricsLine.NO_TIME, st.bgText,
                    st.bgWords != null ? st.bgWords : null));
        }

        // Ensure all existing languages have entries for this line
        for (Map.Entry<String, List<LyricsLine>> entry : translations.entrySet()) {
            final List<LyricsLine> langLines = entry.getValue();
            while (langLines.size() < lineCount) {
                langLines.add(new LyricsLine(LyricsLine.NO_TIME, ""));
            }
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private static String readTextContent(XmlPullParser p)
            throws XmlPullParserException, IOException {
        final StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.START_TAG) {
                depth++;
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            } else if (event == XmlPullParser.TEXT) {
                final String text = p.getText();
                if (text != null) {
                    sb.append(text);
                }
            }
        }
        return sb.toString();
    }

    private static void skipElement(XmlPullParser p)
            throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.START_TAG) {
                depth++;
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            }
        }
    }

    static long parseTime(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        final String trimmed = raw.trim();

        // Try colon-separated format first: HH:MM:SS.mmm or MM:SS.mmm
        final Matcher cm = TIME_COLON.matcher(trimmed);
        if (cm.matches()) {
            final String g1 = cm.group(1); // hours (HH:MM:SS) or minutes (MM:SS)
            final String g2 = cm.group(2); // minutes (HH:MM:SS only)
            final String g3 = cm.group(3); // seconds (always present)
            final double sec = Double.parseDouble(g3);
            if (g1 != null && g2 != null) {
                // HH:MM:SS.mmm
                return (long) ((Integer.parseInt(g1) * 3600 + Integer.parseInt(g2) * 60 + sec) * 1000);
            } else if (g1 != null) {
                // MM:SS.mmm — g1=minutes, g3=seconds
                return (long) ((Integer.parseInt(g1) * 60 + sec) * 1000);
            } else {
                // SS.mmm
                return (long) (sec * 1000);
            }
        }

        // Try unit-suffixed format: 3.5s, 100ms, etc.
        final Matcher m = TIME_UNIT.matcher(trimmed);
        if (m.find()) {
            final double value = Double.parseDouble(m.group(1));
            final String unit = m.group(2);
            switch (unit) {
                case "ms": return (long) value;
                case "s":  return (long) (value * 1000);
                case "m":  return (long) (value * 60_000);
                case "h":  return (long) (value * 3_600_000);
                default:   return (long) (value * 1000);
            }
        }

        // Fallback: bare decimal number treated as seconds
        try {
            return (long) (Double.parseDouble(trimmed) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String normalizeTextRaw(String s) {
        return MULTI_SPACE.matcher(s).replaceAll(" ");
    }

    private static String normalizeText(String s) {
        return normalizeTextRaw(s).trim();
    }

    private static String localName(String name) {
        final int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static String getAttr(XmlPullParser p, @Nullable String ns,
            String local, @Nullable String fallbackQName) {
        if (ns != null) {
            final String val = p.getAttributeValue(ns, local);
            if (val != null) return val;
        }
        if (fallbackQName != null) {
            final String val = p.getAttributeValue(null, fallbackQName);
            if (val != null) return val;
        }
        for (int i = 0, n = p.getAttributeCount(); i < n; i++) {
            final String name = p.getAttributeName(i);
            if (name != null) {
                final int colon = name.indexOf(':');
                final String attrLocal = colon >= 0 ? name.substring(colon + 1) : name;
                if (attrLocal.equals(local)) {
                    return p.getAttributeValue(i);
                }
            }
        }
        return null;
    }

    @Nullable
    static Lyrics ttmlToLyrics(String ttml, String providerName,
            @Nullable String sourceUrl) {
        if (ttml == null || ttml.isEmpty()) return null;
        TtmlResult result = parse(ttml);
        if (result == null || result.lines.isEmpty()) return null;

        List<String> combinedSongwriters = combineCredits(result.songwriters, result.amllCreditLines);
        return new Lyrics(result.lines, providerName, true,
                result.romanization, result.translations,
                result.romanizations, combinedSongwriters, ttml, "ttml", sourceUrl);
    }

    @Nullable
    private static List<String> combineCredits(@Nullable List<String> existing,
            @Nullable List<String> amllCredits) {
        if (existing == null && amllCredits == null) {
            return null;
        }
        if (existing == null) {
            return amllCredits;
        }
        if (amllCredits == null) {
            return existing;
        }
        List<String> combined = new ArrayList<>(existing);
        combined.addAll(amllCredits);
        return combined;
    }
}
