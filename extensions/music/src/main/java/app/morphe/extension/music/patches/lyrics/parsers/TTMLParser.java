/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.parsers;

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
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.derive.LyricsRomanizer;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.romanizers.KanaRomanizer;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.patches.lyrics.utils.TextTrim;
import app.morphe.extension.shared.Logger;

/**
 * Parses Apple Music style TTML into lyric lines.
 *
 * <p>Aligned with the AMLL TypeScript parser ({@code @applemusic-like-lyrics/ttml}):
 * namespace-aware parsing, single-pass body scan, background vocals as lines,
 * agent-based duet/right-alignment, inline + sidecar translations/romanizations,
 * IoU romanization alignment, and Ruby annotation support.
 */
public final class TTMLParser {

    private static final Pattern TIME_UNIT = Pattern.compile("(-?\\d+(?:\\.\\d+)?)(ms|h|m|s)");
    private static final Pattern TIME_COLON = Pattern.compile(
            "^(?:(\\d+):)?(?:(\\d+):)?(\\d+(?:\\.\\d+)?)$");
    private static final Pattern TIME_SMPTE = Pattern.compile(
            "^(\\d+):(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)$");
    /** The frame rate SMPTE timecode counts in when the document does not state one. */
    private static final int DEFAULT_FRAME_RATE = 30;
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private static final String INDEFINITE = "indefinite";

    private static final String NS_TTM = "http://www.w3.org/ns/ttml#metadata";
    private static final String NS_ITUNES = "http://music.apple.com/lyric-ttml-internal";
    private static final String NS_LRC_RED = "http://lrc.red/lyric-ttml-internal";
    private static final String NS_XML = "http://www.w3.org/XML/1998/namespace";
    private static final String NS_TTS = "http://www.w3.org/ns/ttml#styling";
    private static final String NS_AMLL = "http://www.example.com/ns/amll";
    private static final String NS_MORPHE = "http://morphe.app/lyrics";
    private static final String NS_COMPOSER = "https://composer.betterlyrics.org/ttml";
    private static final String NS_COMPOSER_LEGACY = "https://composer.boidu.dev/ttml";

    private static final Set<String> AMLL_EXCLUDED_KEYS = Set.of(
            "ncmMusicId", "qqMusicId", "spotifyId", "appleMusicId", "isrc", "ttmlAuthorGithub"
    );

    private static final Set<String> COMPOSER_EXCLUDED_KEYS = Set.of(
            "isrc", "songwriter", "songwriters", "ytVideoId", "language"
    );

    /** IoU minimum overlap threshold for romanization alignment. */
    private static final double MIN_IOU = 0.1;
    /** Fast-track tolerance: if start times differ by ≤2ms, consider them aligned. */
    private static final long FAST_TRACK_MS = 2;
    private static final String AGENT_TYPE_PERSON = "person";
    private static final String AGENT_TYPE_GROUP = "group";
    private static final String AGENT_TYPE_OTHER = "other";

    private TTMLParser() {
    }

    private static HeadMetadata parseHeadMetadata(String ttml) {
        List<String> songwriters = new ArrayList<>();
        List<String> amllCredits = new ArrayList<>();
        List<String> morpheCredits = new ArrayList<>();
        Map<String, AgentInfo> agentTypes = new HashMap<>();
        Map<String, List<RomajiSyllable>> sidecarRoman = new HashMap<>();
        Map<String, SidecarTranslation> sidecarTrans = new HashMap<>();
        String desc = null;
        String morpheSource = null;
        String morpheSourceUrl = null;
        int offsetMs = 0;

        try {
            final XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            final XmlPullParser p = factory.newPullParser();
            p.setInput(new StringReader(ttml));

            boolean inHead = false;
            boolean inSongwriters = false;
            boolean inAgent = false;
            boolean inItunesMetadata = false;
            int songwritersDepth = 0;
            String agentId = null;
            String agentType = null;
            String agentName = null;

            int event = p.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    final String local = localName(p.getName());
                    if ("head".equals(local)) {
                        inHead = true;
                    } else if (inHead) {
                        if ("songwriters".equals(local)) {
                            inSongwriters = true;
                            songwritersDepth = 1;
                        } else if (inSongwriters) {
                            if ("songwriter".equals(local)) {
                                // readTextContent swallows the closing tag too, so counting this
                                // one would leave songwritersDepth above zero at
                                // </songwriters> and latch inSongwriters, which swallows
                                // the transliterations below.
                                final String name = readTextContent(p);
                                if (!name.trim().isEmpty()) {
                                    songwriters.add(name.trim());
                                }
                            } else {
                                songwritersDepth++;
                            }
                        } else if ("meta".equals(local) && isMetaNamespace(p)) {
                            collectMetaCredit(p, amllCredits);
                        } else if ("agent".equals(local)) {
                            agentId = getAttr(p, NS_TTM, "id", "xml:id");
                            agentType = getAttr(p, NS_TTM, "type", "ttm:type");
                            if (agentType == null) {
                                agentType = getAttr(p, null, "type", "type");
                            }
                            agentName = null;
                            inAgent = true;
                        } else if (inAgent && "name".equals(local)) {
                            final String name = readTextContent(p);
                            if (!name.trim().isEmpty()) {
                                agentName = name.trim();
                            }
                        } else if ("transliterations".equals(local)) {
                            collectSidecarTransliterations(p, sidecarRoman);
                        } else if ("translations".equals(local)) {
                            collectSidecarTranslations(p, sidecarTrans);
                        } else if ("itunesmetadata".equalsIgnoreCase(local)) {
                            inItunesMetadata = true;
                        } else if (inItunesMetadata && "audio".equals(local)) {
                            // <audio lyricOffset="0.940" role="base"/>: seconds every timing is late by.
                            final String offset = getAttr(p, NS_ITUNES, "lyricOffset", null);
                            if (offset != null) {
                                try {
                                    offsetMs = (int) Math.round(Double.parseDouble(offset) * 1000);
                                } catch (NumberFormatException ex) {
                                    Logger.printDebug(() -> "Invalid TTML lyricOffset: " + offset, ex);
                                }
                            }
                        } else {
                            // Morphe namespace metadata (non-standard)
                            if (NS_MORPHE.equals(p.getNamespace())) {
                                switch (local) {
                                    case "source" -> {
                                        morpheSource = readTextContent(p).trim();
                                        if (morpheSource.isEmpty()) morpheSource = null;
                                    }
                                    case "sourceUrl" -> {
                                        morpheSourceUrl = readTextContent(p).trim();
                                        if (morpheSourceUrl.isEmpty()) morpheSourceUrl = null;
                                    }
                                    case "credits" -> {
                                        String credit = readTextContent(p).trim();
                                        if (!credit.isEmpty()) {
                                            morpheCredits.add(credit);
                                        }
                                    }
                                }
                            }

                            // Whether inside <metadata> or directly in <head>.
                            if (desc == null && "desc".equals(local)) {
                                desc = readTextContent(p).trim();
                                if (desc.isEmpty()) desc = null;
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    final String local = localName(p.getName());
                    if ("head".equals(local)) {
                        inHead = false;
                    } else if (inSongwriters && "songwriters".equals(local)) {
                        songwritersDepth--;
                        if (songwritersDepth <= 0) {
                            inSongwriters = false;
                        }
                    } else if (inAgent && "agent".equals(local)) {
                        if (agentId != null && agentType != null) {
                            agentTypes.put(agentId, new AgentInfo(agentType, agentName));
                        }
                        inAgent = false;
                        agentId = null;
                        agentType = null;
                        agentName = null;
                    } else if (inItunesMetadata && "itunesmetadata".equalsIgnoreCase(local)) {
                        inItunesMetadata = false;
                    }
                }
                event = p.next();
            }
        } catch (XmlPullParserException | IOException ex) {
            Logger.printDebug(() -> "Could not parse TTML head metadata", ex);
        }

        return new HeadMetadata(songwriters, amllCredits, morpheCredits, agentTypes, sidecarRoman,
                sidecarTrans, desc, morpheSource, morpheSourceUrl, offsetMs);
    }

    @Nullable
    public static TtmlResult parse(String ttml) {
        if (ttml == null || ttml.isEmpty()) {
            return null;
        }
        try {
            final HeadMetadata hm = parseHeadMetadata(ttml);

            // Title/Artist/Album/Copyright are standard metadata, not songwriters.
            final List<String> songwriters = hm.songwriters().isEmpty()
                    ? null : hm.songwriters();
            // Credits: non-standard metadata with no standard TTML equivalent.
            final List<String> credits = combineCreditLists(hm.amllCredits(), hm.morpheCredits());
            final String about = hm.desc();
            final Map<String, AgentInfo> agentTypes = hm.agentTypes();
            final Map<String, List<RomajiSyllable>> sidecarRoman = hm.sidecarRoman();
            final Map<String, SidecarTranslation> sidecarTrans = hm.sidecarTrans();

            final XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            final XmlPullParser p = factory.newPullParser();
            p.setInput(new StringReader(ttml));

            final List<LyricsLine> lines = new ArrayList<>();
            final List<LyricsLine> romanization = new ArrayList<>();
            final Map<String, List<LyricsLine>> translations = new HashMap<>();
            final Map<String, List<LyricsLine>> romanizations = new HashMap<>();
            int mainLineCount;

            boolean noTiming = false;
            int frameRate = DEFAULT_FRAME_RATE;
            boolean inHead = false;
            boolean inBody = false;
            String divSongPart = null;

            int event = p.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    final String local = localName(p.getName());

                    if ("tt".equals(local)) {
                        noTiming = "none".equalsIgnoreCase(
                                getLyricAttr(p, "timing", "itunes:timing"));
                        frameRate = parseFrameRate(getAttr(p, null, "frameRate", "frameRate"));
                    } else if ("head".equals(local)) {
                        inHead = true;
                    } else if ("body".equals(local)) {
                        inBody = true;
                    } else if ("div".equals(local) && inBody && !inHead) {
                        divSongPart = getLyricAttr(p, "songPart", "itunes:songPart");
                        if (divSongPart == null) {
                            divSongPart = getLyricAttr(p, "songPart", "itunes:song-part");
                        }
                    } else if ("p".equals(local) && inBody && !inHead) {
                        final String lineId = getLyricAttr(p, "key", "itunes:key");
                        final String agentId = getAttr(p, NS_TTM, "agent", "ttm:agent");
                        final String pBeginRaw = noTiming ? null : getAttr(p, null, "begin", "begin");
                        final String pEndRaw = noTiming ? null : getAttr(p, null, "end", "end");
                        final String pDurRaw = noTiming ? null : getAttr(p, null, "dur", "dur");
                        final long pBegin = noTiming ? 0 : parseTime(pBeginRaw, frameRate);
                        final long pEnd = noTiming ? 0 : resolveEndTime(pBegin, pEndRaw, pDurRaw, frameRate);

                        final ParsedLine pl = processPElement(p, pBegin, pEnd, frameRate);

                        final boolean hasText = pl != null && !pl.text().trim().isEmpty();
                        if (pl != null && (hasText || !pl.bgLines().isEmpty())) {
                            final List<RomajiSyllable> lineSidecar =
                                    findSidecarRoman(lineId, sidecarRoman);
                            // Read off the sidecar before the words carry a reading, or the
                            // line loses the parts no word matched.
                            final String romaText = buildLineRomaji(pl.words(), lineSidecar);
                            // The panel draws a reading over the words, not beside them.
                            if (lineSidecar != null && !lineSidecar.isEmpty()) {
                                alignRomajiToWords(pl.words(), lineSidecar);
                            }

                            if (hasText) {
                                final LyricsLine line = new LyricsLine(
                                        pl.begin(), pl.end(), pl.text(), pl.words(),
                                        agentId, false, false, divSongPart);
                                lines.add(line);
                            }
                            mainLineCount = lines.size();

                            final String bgAgent = pl.bgAgentId() != null ? pl.bgAgentId() : agentId;
                            for (LyricsLine bgSrc : pl.bgLines()) {
                                final LyricsLine bgLine = new LyricsLine(
                                        bgSrc.startTimeMs(), bgSrc.endTimeMs(),
                                        bgSrc.text(), bgSrc.words(),
                                        bgAgent, false, true, divSongPart);
                                lines.add(bgLine);
                            }

                            if (hasText) {
                                romanization.add(new LyricsLine(
                                        LyricsLine.NO_TIME, romaText));
                            }
                            for (int i = 0; i < pl.bgLines().size(); i++) {
                                romanization.add(new LyricsLine(LyricsLine.NO_TIME, ""));
                            }

                            buildRomanizations(lineId, sidecarRoman, pl.words(), romanizations,
                                    mainLineCount - 1, pl.bgLines().size());

                            buildTranslations(lineId, sidecarTrans, translations,
                                    mainLineCount - 1, pl.bgLines().size());

                            if (pl.inlineTranslations() != null) {
                                for (Map.Entry<String, String> e : pl.inlineTranslations().entrySet()) {
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
                                for (Map.Entry<String, List<LyricsLine>> e : translations.entrySet()) {
                                    while (e.getValue().size() < lines.size()) {
                                        e.getValue().add(new LyricsLine(LyricsLine.NO_TIME, ""));
                                    }
                                }
                            }

                            if (pl.inlineRomanizations() != null) {
                                for (Map.Entry<String, String> e : pl.inlineRomanizations().entrySet()) {
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

                            if (pl.bgInlineTranslations() != null) {
                                for (Map.Entry<String, String> e : pl.bgInlineTranslations().entrySet()) {
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

                            if (pl.bgInlineRomanizations() != null) {
                                for (Map.Entry<String, String> e : pl.bgInlineRomanizations().entrySet()) {
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
                    switch (localName(p.getName())) {
                        case "head" -> inHead = false;
                        case "body" -> inBody = false;
                        case "div" -> divSongPart = null;
                    }
                }
                event = p.next();
            }

            if (lines.isEmpty()) {
                return null;
            }

            resolveDuet(lines, agentTypes);

            padDerivedTo(translations, lines.size());
            padDerivedTo(romanizations, lines.size());

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
                    if (e.getValue().name() != null) {
                        names.put(e.getKey(), e.getValue().name());
                    }
                }
                agentNames = names.isEmpty() ? null : names;
            }

            return new TtmlResult(lines,
                    LyricsMerge.hasText(romanization) ? romanization : null,
                    transOut, romaOut,
                    songwriters,
                    credits,
                    agentNames,
                    about,
                    hm.morpheSource(),
                    hm.morpheSourceUrl(),
                    hm.offsetMs());
        } catch (XmlPullParserException | IOException ex) {
            Logger.printDebug(() -> "Could not parse TTML to lyrics", ex);
            return null;
        }
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
                isDuet = !LyricsLine.isCenteredAgentId(agentId) && (agentNum % 2 == 0);
            } else {
                final AgentInfo info = agentTypes.get(agentId);
                final String type = info != null ? info.type() : AGENT_TYPE_PERSON;

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
        } catch (NumberFormatException ex) {
            Logger.printDebug(() -> "Could not extract agent number from ID: " + agentId, ex);
            return 0;
        }
    }

    private static void collectSidecarTransliterations(XmlPullParser p,
                                                       Map<String, List<RomajiSyllable>> map)
            throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.END_DOCUMENT) {
                break;
            }
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
            if (event == XmlPullParser.END_DOCUMENT) {
                break;
            }
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("text".equals(local)) {
                    final String forKey = getAttr(p, null, "for", "for");
                    if (forKey != null && !forKey.isEmpty()) {
                        final List<RomajiSyllable> syllables = parseTransliterationText(p);
                        if (!syllables.isEmpty()) {
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
            if (event == XmlPullParser.END_DOCUMENT) {
                break;
            }
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("span".equals(local)) {
                    final String begin = getAttr(p, null, "begin", "begin");
                    final String end = getAttr(p, null, "end", "end");
                    final String dur = getAttr(p, null, "dur", "dur");
                    if (hasTiming(begin, end, dur)) {
                        final String text = normalizeText(readTextContent(p));
                        if (!text.isEmpty()) {
                            final long beginMs = parseTime(begin);
                            final long endMs = resolveEndTime(beginMs, end, dur, DEFAULT_FRAME_RATE);
                            syllables.add(new RomajiSyllable(beginMs, endMs, text));
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

    private static void collectSidecarTranslations(XmlPullParser p,
                                                   Map<String, SidecarTranslation> map)
            throws XmlPullParserException, IOException {
        int depth = 1;
        int eventCount = 0;
        while (depth > 0 && eventCount < 100_000) {
            final int event = p.next();
            eventCount++;
            if (event == XmlPullParser.END_DOCUMENT) {
                break;
            }

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
            if (event == XmlPullParser.END_DOCUMENT) {
                break;
            }
            if (event == XmlPullParser.START_TAG) {
                final String local = localName(p.getName());
                if ("text".equals(local)) {
                    final String forKey = getAttr(p, null, "for", "for");
                    if (forKey != null && !forKey.isEmpty()) {
                        final SidecarTranslation st = parseTranslationText(p);
                        if (!st.text().isEmpty()) {
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
            if (event == XmlPullParser.END_DOCUMENT) {
                break;
            }
            if (event == XmlPullParser.START_TAG) {
                depth++;
                final String local = localName(p.getName());
                final String role = getAttr(p, NS_TTM, "role", "ttm:role");

                if ("x-bg".equals(role)) {
                    inBg = true;
                } else if ("br".equals(local)) {
                    // A line break inside the translation: treat as a word separator.
                    if (inBgWord) {
                        bgWordBuf.append(' ');
                    } else if (inMainWord) {
                        mainWordBuf.append(' ');
                    } else if (inBg) {
                        endWordWithBreak(bgWords, bgFullText);
                    } else {
                        endWordWithBreak(mainWords, mainFullText);
                    }
                } else if ("span".equals(local)) {
                    final String beginAttr = getAttr(p, null, "begin", "begin");
                    final String endAttr = getAttr(p, null, "end", "end");
                    final String durAttr = getAttr(p, null, "dur", "dur");
                    if (hasTiming(beginAttr, endAttr, durAttr)) {
                        if (inBg) {
                            inBgWord = true;
                            bgWordBuf.setLength(0);
                            bgWordBegin = parseTime(beginAttr);
                            bgWordEnd = resolveEndTime(bgWordBegin, endAttr, durAttr, DEFAULT_FRAME_RATE);
                        } else {
                            inMainWord = true;
                            mainWordBuf.setLength(0);
                            mainWordBegin = parseTime(beginAttr);
                            mainWordEnd = resolveEndTime(mainWordBegin, endAttr, durAttr, DEFAULT_FRAME_RATE);
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
                                            prev.romaji(), true, prev.ruby()));
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
                                            prev.romaji(), true, prev.ruby()));
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
                                    prev.text(), prev.romaji(), true, prev.ruby()));
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
                                    prev.text(), prev.romaji(), true, prev.ruby()));
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
                        first.text().substring(1).trim(), first.romaji(), first.endsWithSpace(), first.ruby()));
            }
            final int lastIdx = mainWords.size() - 1;
            final Word last = mainWords.get(lastIdx);
            if (last.text().endsWith(" ") || last.endsWithSpace()) {
                mainWords.set(lastIdx, new Word(last.startMs(), last.endMs(),
                        last.text().trim(), last.romaji(), false, last.ruby()));
            }
        }

        if (!bgWords.isEmpty()) {
            final Word first = bgWords.get(0);
            if (first.text().startsWith(" ")) {
                bgWords.set(0, new Word(first.startMs(), first.endMs(),
                        first.text().substring(1).trim(), first.romaji(), first.endsWithSpace(), first.ruby()));
            }
            final int lastIdx = bgWords.size() - 1;
            final Word last = bgWords.get(lastIdx);
            if (last.text().endsWith(" ") || last.endsWithSpace()) {
                bgWords.set(lastIdx, new Word(last.startMs(), last.endMs(),
                        last.text().trim(), last.romaji(), false, last.ruby()));
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

    /** A line break between words: the word before it now ends in a space, as does the text. */
    private static void endWordWithBreak(List<Word> words, StringBuilder text) {
        if (!words.isEmpty()) {
            final Word last = words.get(words.size() - 1);
            if (!last.endsWithSpace()) {
                words.set(words.size() - 1, new Word(last.startMs(), last.endMs(), last.text(),
                        last.romaji(), true, last.ruby()));
            }
        }
        //noinspection SizeReplaceableByIsEmpty
        if (text.length() > 0 && text.charAt(text.length() - 1) != ' ') {
            text.append(' ');
        }
    }

    @SuppressWarnings({"DataFlowIssue", "StatementWithEmptyBody", "ExtractMethodRecommender"})
    private static ParsedLine processPElement(XmlPullParser p, long pBegin, long pEnd, int frameRate)
            throws XmlPullParserException, IOException {

        final List<Word> words = new ArrayList<>();
        final StringBuilder fullText = new StringBuilder();

        boolean inTranslation = false;
        final StringBuilder transBuf = new StringBuilder();
        String transLang = null;
        boolean inRoman = false;
        final StringBuilder romanBuf = new StringBuilder();
        String romanLang = null;

        final Map<String, String> inlineTrans = new HashMap<>();
        final Map<String, String> inlineRoma = new HashMap<>();
        final Map<String, String> bgInlineTrans = new HashMap<>();
        final Map<String, String> bgInlineRoma = new HashMap<>();

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
        int rubyContainerInnerDepth = 0;
        boolean inRubyBase = false;
        int rubyBaseInnerDepth = 0;
        final StringBuilder rubyBaseBuf = new StringBuilder();
        boolean inRubyTextContainer = false;
        int rubyTextContainerInnerDepth = 0;
        boolean inRubyText = false;
        int rubyTextInnerDepth = 0;
        final StringBuilder rubyTextBuf = new StringBuilder();
        long rubyTextBegin = 0, rubyTextEnd = 0;
        long rubyContainerBegin = 0, rubyContainerEnd = 0;
        final List<RomajiSyllable> rubyTags = new ArrayList<>();

        int depth = 1;

        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.END_DOCUMENT) {
                break;
            }
            if (event == XmlPullParser.START_TAG) {
                depth++;
                final String local = localName(p.getName());
                final String htmlRuby = htmlRubyRole(local);

                if ("br".equals(local)) {
                    // A line break inside a paragraph: treat as a word separator.
                    if (inBgTranslation) {
                        bgTransBuf.append(' ');
                    } else if (inBgRoman) {
                        bgRomanBuf.append(' ');
                    } else if (inBgWord) {
                        bgWordBuf.append(' ');
                    } else if (inRubyContainer) {
                        if (inRubyBase) {
                            rubyBaseBuf.append(' ');
                        } else if (inRubyText) {
                            rubyTextBuf.append(' ');
                        }
                    } else if (inBg && bgWrapperDepth == 0) {
                        endWordWithBreak(bgWords, bgFullText);
                    } else if (inTranslation) {
                        transBuf.append(' ');
                    } else if (inRoman) {
                        romanBuf.append(' ');
                    } else if (inWord) {
                        wordBuf.append(' ');
                    } else if (!inBg) {
                        endWordWithBreak(words, fullText);
                    }
                    continue;
                }

                if (!"span".equals(local) && htmlRuby == null) {
                    continue;
                }

                final String role = getAttr(p, NS_TTM, "role", "ttm:role");
                final String rubyAttr = getAttr(p, NS_TTS, "ruby", "tts:ruby");
                final String ruby = rubyAttr != null ? rubyAttr : htmlRuby;
                final String beginAttr = getAttr(p, null, "begin", "begin");
                final String endAttr = getAttr(p, null, "end", "end");
                final String durAttr = getAttr(p, null, "dur", "dur");

                // Ruby is settled first: an annotation sits inside the vocal it reads,
                // so the background branch below would drop its base text.
                if ("container".equals(ruby)) {
                    inRubyContainer = true;
                    rubyContainerInnerDepth = 0;
                    rubyBaseInnerDepth = 0;
                    rubyTextContainerInnerDepth = 0;
                    rubyTextInnerDepth = 0;
                    rubyBaseBuf.setLength(0);
                    rubyTags.clear();
                    rubyContainerBegin = parseTime(beginAttr, frameRate);
                    rubyContainerEnd = resolveEndTime(rubyContainerBegin, endAttr, durAttr, frameRate);
                    if (rubyContainerBegin == 0 && rubyContainerEnd == 0) {
                        if (inBg && inBgWord) {
                            rubyContainerBegin = bgWordBegin;
                            rubyContainerEnd = bgWordEnd;
                            inBgWord = false;
                        } else if (!inBg && inWord) {
                            rubyContainerBegin = wordBegin;
                            rubyContainerEnd = wordEnd;
                            inWord = false;
                        }
                    }
                } else if ("base".equals(ruby) && inRubyContainer) {
                    inRubyBase = true;
                    rubyBaseInnerDepth = 0;
                    rubyBaseBuf.setLength(0);
                    final long b = parseTime(beginAttr, frameRate);
                    final long e = resolveEndTime(b, endAttr, durAttr, frameRate);
                    if (b > 0) rubyContainerBegin = b;
                    if (e > 0) rubyContainerEnd = e;
                } else if ("textContainer".equals(ruby) && inRubyContainer) {
                    inRubyTextContainer = true;
                    rubyTextContainerInnerDepth = 0;
                } else if ("text".equals(ruby) && (inRubyTextContainer || inRubyContainer)) {
                    inRubyText = true;
                    rubyTextInnerDepth = 0;
                    rubyTextBuf.setLength(0);
                    rubyTextBegin = parseTime(beginAttr, frameRate);
                    rubyTextEnd = resolveEndTime(rubyTextBegin, endAttr, durAttr, frameRate);
                } else if (inRubyContainer) {
                    // A span nested in the ruby, such as one timing each mora of the base.
                    final long b = parseTime(beginAttr, frameRate);
                    final long e = resolveEndTime(b, endAttr, durAttr, frameRate);
                    if (inRubyText) {
                        rubyTextInnerDepth++;
                        if (b > 0 && (rubyTextBegin == 0 || b < rubyTextBegin)) rubyTextBegin = b;
                        if (e > rubyTextEnd) rubyTextEnd = e;
                    } else if (inRubyBase) {
                        rubyBaseInnerDepth++;
                        if (b > 0 && (rubyContainerBegin == 0 || b < rubyContainerBegin))
                            rubyContainerBegin = b;
                        if (e > rubyContainerEnd) rubyContainerEnd = e;
                    } else if (inRubyTextContainer) {
                        rubyTextContainerInnerDepth++;
                    } else {
                        rubyContainerInnerDepth++;
                        if (b > 0 && (rubyContainerBegin == 0 || b < rubyContainerBegin))
                            rubyContainerBegin = b;
                        if (e > rubyContainerEnd) rubyContainerEnd = e;
                    }
                } else if (inBg) {
                    if ("x-translation".equals(role)) {
                        inBgTranslation = true;
                        bgTransBuf.setLength(0);
                    } else if ("x-roman".equals(role)) {
                        inBgRoman = true;
                        bgRomanBuf.setLength(0);
                    } else if (hasTiming(beginAttr, endAttr, durAttr)) {
                        inBgWord = true;
                        bgWordBuf.setLength(0);
                        bgWordBegin = parseTime(beginAttr, frameRate);
                        bgWordEnd = resolveEndTime(bgWordBegin, endAttr, durAttr, frameRate);
                    } else {
                        bgWrapperDepth++;
                    }
                } else if ("x-bg".equals(role)) {
                    inBg = true;
                    bgWords.clear();
                    bgFullText.setLength(0);
                    bgWrapperDepth = 0;
                    bgAgentId = getAttr(p, NS_TTM, "agent", "ttm:agent");
                    bgBeginMs = parseTime(beginAttr, frameRate);
                    bgEndMs = resolveEndTime(bgBeginMs, endAttr, durAttr, frameRate);
                } else if ("x-translation".equals(role)) {
                    inTranslation = true;
                    transBuf.setLength(0);
                    transLang = getAttr(p, NS_XML, "lang", "xml:lang");
                } else if ("x-roman".equals(role)) {
                    inRoman = true;
                    romanBuf.setLength(0);
                    romanLang = getAttr(p, NS_XML, "lang", "xml:lang");
                } else if (hasTiming(beginAttr, endAttr, durAttr)) {
                    inWord = true;
                    wordBuf.setLength(0);
                    wordBegin = parseTime(beginAttr, frameRate);
                    wordEnd = resolveEndTime(wordBegin, endAttr, durAttr, frameRate);
                } else {
                    wrapperDepth++;
                }
            } else if (event == XmlPullParser.END_TAG) {
                final String local = localName(p.getName());

                if ("span".equals(local) || htmlRubyRole(local) != null) {
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
                                            prev.romaji(), true, prev.ruby()));
                        }
                        if (!text.isEmpty()) {
                            bgWords.add(new Word(bgWordBegin, bgWordEnd, text, null,
                                    endsWithSpace));
                        }
                    } else if (inRubyText && inRubyContainer) {
                        if (rubyTextInnerDepth > 0) {
                            rubyTextInnerDepth--;
                        } else {
                            inRubyText = false;
                            final String text = normalizeText(rubyTextBuf.toString());
                            if (!text.isEmpty()) {
                                rubyTags.add(new RomajiSyllable(
                                        rubyTextBegin, rubyTextEnd, text));
                            }
                        }
                    } else if (inRubyBase && inRubyContainer) {
                        if (rubyBaseInnerDepth > 0) {
                            rubyBaseInnerDepth--;
                        } else {
                            inRubyBase = false;
                        }
                    } else if (inRubyTextContainer && inRubyContainer) {
                        if (rubyTextContainerInnerDepth > 0) {
                            rubyTextContainerInnerDepth--;
                        } else {
                            inRubyTextContainer = false;
                        }
                    } else if (inRubyContainer) {
                        if (rubyContainerInnerDepth > 0) {
                            rubyContainerInnerDepth--;
                        } else {
                            inRubyContainer = false;
                            final String baseText = normalizeText(rubyBaseBuf.toString());
                            if (!baseText.isEmpty()) {
                                long rBegin = 0, rEnd = 0;
                                if (!rubyTags.isEmpty()) {
                                    rBegin = rubyTags.get(0).startMs();
                                    rEnd = rubyTags.get(rubyTags.size() - 1).endMs();
                                }
                                if (rBegin == 0 && rEnd == 0) {
                                    rBegin = rubyContainerBegin;
                                    rEnd = rubyContainerEnd;
                                }
                                StringBuilder rubyBuilder = new StringBuilder();
                                for (RomajiSyllable rs : rubyTags) {
                                    //noinspection SizeReplaceableByIsEmpty
                                    if (rubyBuilder.length() > 0) rubyBuilder.append(' ');
                                    rubyBuilder.append(rs.text());
                                }
                                //noinspection SizeReplaceableByIsEmpty
                                final String rubyText = rubyBuilder.length() > 0 ? rubyBuilder.toString() : null;
                                // The ruby is drawn as furigana. The romaji reads the kana, which is
                                // the ruby unless that is a gloss, such as "Normal" over ノルマ.
                                final String romaji;
                                if (ScriptDetector.containsKana(rubyText)) {
                                    romaji = KanaRomanizer.romanizeSyllables(rubyText);
                                } else if (ScriptDetector.containsKana(baseText)) {
                                    romaji = KanaRomanizer.romanizeSyllables(baseText);
                                } else {
                                    romaji = null;
                                }

                                // The base is the sung text either way, only the line differs.
                                final Word word = new Word(rBegin, rEnd, baseText, romaji, false, rubyText);
                                if (inBg) {
                                    bgFullText.append(baseText);
                                    bgWords.add(word);
                                } else {
                                    fullText.append(baseText);
                                    words.add(word);
                                }
                            }
                        }
                    } else if (inBg && bgWrapperDepth > 0) {
                        bgWrapperDepth--;
                    } else if (inBg) {
                        flushBgLine(bgLines, bgWords, bgFullText, bgBeginMs, bgEndMs,
                                pBegin, pEnd);
                        bgWords.clear();
                        bgFullText.setLength(0);
                        inBg = false;
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
                                            prev.romaji(), true, prev.ruby()));
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
                final boolean isWhitespace = raw.trim().isEmpty();
                final boolean hasNewline = raw.contains("\n");

                if (inBgTranslation) {
                    bgTransBuf.append(raw);
                } else if (inBgRoman) {
                    bgRomanBuf.append(raw);
                } else if (inBgWord) {
                    bgWordBuf.append(raw);
                } else if (inRubyBase && inRubyContainer) {
                    rubyBaseBuf.append(raw);
                } else if (inRubyText && inRubyContainer) {
                    rubyTextBuf.append(raw);
                } else if (inRubyContainer) {
                    // Text between the base and the ruby text is only layout whitespace.
                } else if (inBg && bgWrapperDepth == 0) {
                    if (isWhitespace) {
                        if (!bgWords.isEmpty()) {
                            final Word prev = bgWords.get(bgWords.size() - 1);
                            final boolean cjk = isContinuousCjk(prev.text());
                            if (!hasNewline || !cjk) {
                                //noinspection SizeReplaceableByIsEmpty
                                if (bgFullText.length() > 0 && bgFullText.charAt(bgFullText.length() - 1) != ' ') {
                                    bgFullText.append(' ');
                                }
                                if (!prev.endsWithSpace()) {
                                    bgWords.set(bgWords.size() - 1, new Word(
                                            prev.startMs(), prev.endMs(),
                                            prev.text(), prev.romaji(), true, prev.ruby()));
                                }
                            }
                        }
                    } else {
                        bgFullText.append(raw);
                    }
                } else if (inTranslation) {
                    transBuf.append(raw);
                } else if (inRoman) {
                    romanBuf.append(raw);
                } else if (inWord && !inRubyContainer) {
                    wordBuf.append(raw);
                } else if (!inBg && !inRubyContainer) {
                    if (isWhitespace) {
                        if (!words.isEmpty()) {
                            final Word prev = words.get(words.size() - 1);
                            final boolean cjk = isContinuousCjk(prev.text());
                            if (!hasNewline || !cjk) {
                                //noinspection SizeReplaceableByIsEmpty
                                if (fullText.length() > 0 && fullText.charAt(fullText.length() - 1) != ' ') {
                                    fullText.append(' ');
                                }
                                if (!prev.endsWithSpace()) {
                                    words.set(words.size() - 1, new Word(
                                            prev.startMs(), prev.endMs(),
                                            prev.text(), prev.romaji(), true, prev.ruby()));
                                }
                            }
                        }
                    } else {
                        fullText.append(raw);
                    }
                }
            }
        }

        final String rawLineText = normalizeText(fullText.toString());
        String lineText = rawLineText;

        // A background vocal that is still open ends with its line.
        if (inBg) {
            flushBgLine(bgLines, bgWords, bgFullText, bgBeginMs, bgEndMs, pBegin, pEnd);
            inBg = false;
        }

        if (lineText.trim().isEmpty() && bgLines.isEmpty()) {
            return null;
        }

        // The words give the line its range, so a syllable sung past the line's own end is not cut off.
        long effectiveBegin = pBegin;
        long effectiveEnd = pEnd;
        if (!words.isEmpty()) {
            long minStart = Long.MAX_VALUE;
            long maxEnd = 0;
            for (Word w : words) {
                if (w.startMs() > 0 && w.startMs() < minStart) minStart = w.startMs();
                if (w.endMs() > 0 && w.endMs() > maxEnd) maxEnd = w.endMs();
            }
            if (minStart < Long.MAX_VALUE && maxEnd > 0) {
                if (effectiveBegin == 0 || minStart < effectiveBegin) {
                    effectiveBegin = minStart;
                }
                if (effectiveEnd == 0 || maxEnd > effectiveEnd) {
                    effectiveEnd = maxEnd;
                }
            }
        } else if ((effectiveBegin == 0 || effectiveEnd == 0) && !bgLines.isEmpty()) {
            effectiveBegin = bgLines.get(0).startTimeMs();
            effectiveEnd = bgLines.get(bgLines.size() - 1).endTimeMs();
        }

        if (effectiveBegin == 0 && effectiveEnd == 0 && words.isEmpty() && bgLines.isEmpty()) {
            effectiveBegin = LyricsLine.NO_TIME;
            effectiveEnd = LyricsLine.NO_TIME;
        }

        if (!words.isEmpty()) {
            final Word first = words.get(0);
            if (first.text().startsWith(" ")) {
                words.set(0, new Word(first.startMs(), first.endMs(),
                        first.text().substring(1).trim(), first.romaji(), first.endsWithSpace(), first.ruby()));
            }
            final int lastIdx = words.size() - 1;
            final Word last = words.get(lastIdx);
            if (last.text().endsWith(" ") || last.endsWithSpace()) {
                words.set(lastIdx, new Word(last.startMs(), last.endMs(),
                        last.text().trim(), last.romaji(), false, last.ruby()));
            }
        }

        if (inBg) {
            // Defensive: only reachable if an x-bg span was never closed.
            flushBgLine(bgLines, bgWords, bgFullText, bgBeginMs, bgEndMs, pBegin, pEnd);
        }
        for (LyricsLine bg : bgLines) {
            if (bg.endTimeMs() > effectiveEnd && effectiveEnd > 0) {
                effectiveEnd = bg.endTimeMs();
            }
        }

        return new ParsedLine(effectiveBegin, effectiveEnd, lineText, words, bgLines,
                bgAgentId,
                inlineTrans.isEmpty() ? null : inlineTrans,
                inlineRoma.isEmpty() ? null : inlineRoma,
                bgInlineTrans.isEmpty() ? null : bgInlineTrans,
                bgInlineRoma.isEmpty() ? null : bgInlineRoma);
    }

    private static void flushBgLine(
            List<LyricsLine> bgLines,
            List<Word> bgWords,
            StringBuilder bgFullText,
            long bgBeginMs,
            long bgEndMs,
            long pBegin,
            long pEnd) {
        //noinspection SizeReplaceableByIsEmpty
        if (bgWords.isEmpty() && bgFullText.length() == 0) {
            return;
        }
        String bgText = normalizeText(bgFullText.toString());
        if (!bgText.trim().isEmpty()) {
            String cleanBgText = bgText.replaceAll("^[(（]+", "").replaceAll("[)）]+$", "").trim();
            stripBgWordParens(bgWords);
            if (!cleanBgText.isEmpty()) {
                final long bgStart = bgBeginMs > 0 ? bgBeginMs : (bgWords.isEmpty() ? pBegin : bgWords.get(0).startMs());
                final long bgEnd = bgEndMs > 0 ? bgEndMs : (bgWords.isEmpty() ? pEnd : bgWords.get(bgWords.size() - 1).endMs());
                bgLines.add(new LyricsLine(bgStart, bgEnd, cleanBgText, new ArrayList<>(bgWords)));
            }
        }
        bgWords.clear();
        bgFullText.setLength(0);
    }

    private static void stripBgWordParens(List<Word> bgWords) {
        if (bgWords.isEmpty()) return;
        final Word first = bgWords.get(0);
        final String strippedFirst = TextTrim.stripLeading(
                first.text().replaceAll("^[(（]+", ""));
        if (!strippedFirst.equals(first.text())) {
            bgWords.set(0, new Word(first.startMs(), first.endMs(),
                    strippedFirst, first.romaji(), first.endsWithSpace(), first.ruby()));
        }
        final int lastIdx = bgWords.size() - 1;
        final Word last = bgWords.get(lastIdx);
        final String strippedLast = TextTrim.stripTrailing(
                last.text().replaceAll("[)）]+$", ""));
        if (!strippedLast.equals(last.text())) {
            bgWords.set(lastIdx, new Word(last.startMs(), last.endMs(),
                    strippedLast, last.romaji(), false, last.ruby()));
        }
    }

    public static boolean isContinuousCjk(@Nullable String s) {
        if (s == null || s.isEmpty()) return false;
        int cp = s.codePointBefore(s.length());
        Character.UnicodeBlock b = Character.UnicodeBlock.of(cp);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA;
    }

    private static void alignRomajiToWords(List<Word> words,
                                           List<RomajiSyllable> sidecar) {
        if (words.isEmpty() || sidecar.isEmpty()) {
            return;
        }

        int romanSearchStart = 0;

        for (int i = 0; i < words.size(); i++) {
            final Word main = words.get(i);
            final long nextWordStart = (i + 1 < words.size()) ? words.get(i + 1).startMs() : Long.MAX_VALUE;
            final long mainEndBound = Math.min(main.endMs(), nextWordStart);

            // Collect all consecutive sidecar syllables whose span falls inside this word's window
            // (e.g. "no", "ru", "ma" inside "ノルマ" [750ms..1550ms]).
            StringBuilder multiRoma = new StringBuilder();
            int multiLastIdx = -1;
            double maxIou = 0;
            int bestIdx = -1;

            int j = romanSearchStart;
            while (j < sidecar.size()) {
                final RomajiSyllable sub = sidecar.get(j);
                if (sub.startMs() >= mainEndBound + FAST_TRACK_MS && sub.startMs() > main.startMs() + FAST_TRACK_MS) {
                    break;
                }

                final long overlapStart = Math.max(main.startMs(), sub.startMs());
                final long overlapEnd = Math.min(main.endMs(), sub.endMs());
                final long intersection = Math.max(0, overlapEnd - overlapStart);
                final long subDur = Math.max(1, sub.endMs() - sub.startMs());

                final boolean startsTogether = Math.abs(main.startMs() - sub.startMs()) <= FAST_TRACK_MS;
                final boolean mostlyInsideMain = intersection * 2 >= subDur
                        && sub.startMs() >= main.startMs() - FAST_TRACK_MS
                        && sub.endMs() <= main.endMs() + FAST_TRACK_MS * 2;

                if (startsTogether || mostlyInsideMain) {
                    String piece = sub.text() != null ? sub.text().trim() : "";
                    if (!piece.isEmpty()) {
                        //noinspection SizeReplaceableByIsEmpty
                        if (multiRoma.length() > 0) multiRoma.append(' ');
                        multiRoma.append(piece);
                        multiLastIdx = j;
                    }
                }

                if (intersection > 0) {
                    final long unionStart = Math.min(main.startMs(), sub.startMs());
                    final long unionEnd = Math.max(main.endMs(), sub.endMs());
                    final double iou = (double) intersection / Math.max(1, unionEnd - unionStart);
                    if (iou > maxIou) {
                        maxIou = iou;
                        bestIdx = j;
                    }
                }
                j++;
            }

            //noinspection SizeReplaceableByIsEmpty
            if (multiLastIdx >= 0 && multiRoma.length() > 0) {
                words.set(i, new Word(main.startMs(), main.endMs(), main.text(),
                        multiRoma.toString(), main.endsWithSpace(), main.ruby()));
                romanSearchStart = multiLastIdx + 1;
            } else if (bestIdx >= 0 && maxIou >= MIN_IOU) {
                final RomajiSyllable sub = sidecar.get(bestIdx);
                words.set(i, new Word(main.startMs(), main.endMs(), main.text(),
                        sub.text(), main.endsWithSpace(), main.ruby()));
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
        if (sidecar != null && !sidecar.isEmpty()) {
            final StringBuilder sb = new StringBuilder();
            for (RomajiSyllable s : sidecar) {
                if (s.text().isEmpty()) continue;
                sb.append(s.text());
            }
            final String result = sb.toString().trim();
            if (!result.isEmpty() && LyricsRomanizer.isPurelyLatinScript(result)) {
                return result;
            }
        }

        final StringBuilder perWord = new StringBuilder();
        boolean hasPerWord = false;
        for (Word w : words) {
            if (w.romaji() != null && !w.romaji().isEmpty() && LyricsRomanizer.isPurelyLatinScript(w.romaji())) {
                hasPerWord = true;
                break;
            }
        }
        if (hasPerWord) {
            for (Word w : words) {
                String r = w.romaji();
                if (r != null && !r.isEmpty() && LyricsRomanizer.isPurelyLatinScript(r)) {
                    perWord.append(r);
                    if (w.endsWithSpace() && !r.endsWith(" ")) {
                        perWord.append(' ');
                    }
                }
            }
            //noinspection SizeReplaceableByIsEmpty
            if (perWord.length() > 0) {
                return perWord.toString().trim();
            }
        }

        return "";
    }

    private static void buildRomanizations(String lineId,
                                           Map<String, List<RomajiSyllable>> sidecarRoman,
                                           List<Word> words,
                                           Map<String, List<LyricsLine>> romanizations,
                                           int mainIndex, int bgCount) {
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

            if (sidecar.size() == 1 && sidecar.get(0).startMs() == 0 && sidecar.get(0).endMs() == 0) {
                continue;
            }

            final List<Word> alignedWords = new ArrayList<>(words);
            alignRomajiToWords(alignedWords, sidecar);

            final StringBuilder sb = new StringBuilder();
            for (Word w : alignedWords) {
                if (w.romaji() != null && !w.romaji().isEmpty()) {
                    sb.append(w.romaji());
                    if (w.endsWithSpace() && !w.romaji().endsWith(" ")) {
                        sb.append(' ');
                    }
                }
            }
            final String text = sb.toString().trim();
            final String langKey = lang != null ? lang : "romaji";
            final List<LyricsLine> langLines = romanizations.computeIfAbsent(
                    langKey, k -> new ArrayList<>());
            while (langLines.size() < mainIndex) {
                langLines.add(new LyricsLine(LyricsLine.NO_TIME, ""));
            }
            if (langLines.size() == mainIndex) {
                String lineContent = (!text.isEmpty() && LyricsRomanizer.isPurelyLatinScript(text)) ? text : "";
                langLines.add(new LyricsLine(LyricsLine.NO_TIME, lineContent));
            }
        }
        padDerivedTo(romanizations, mainIndex + 1 + bgCount);
    }

    private static void padDerivedTo(Map<String, List<LyricsLine>> derived, int size) {
        if (derived == null || derived.isEmpty()) {
            return;
        }
        for (List<LyricsLine> langLines : derived.values()) {
            while (langLines.size() < size) {
                langLines.add(new LyricsLine(LyricsLine.NO_TIME, ""));
            }
        }
    }

    private static void buildTranslations(String lineId,
                                          Map<String, SidecarTranslation> sidecarTrans,
                                          Map<String, List<LyricsLine>> translations, int mainIndex, int bgCount) {

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
            if (st.bgText() != null) {
                bgByLang.put(lang, st);
            }
        }

        for (Map.Entry<String, SidecarTranslation> entry : mainByLang.entrySet()) {
            final String lang = entry.getKey();
            final SidecarTranslation st = entry.getValue();
            final List<LyricsLine> langLines = translations.computeIfAbsent(
                    lang, k -> new ArrayList<>());
            while (langLines.size() < mainIndex) {
                langLines.add(new LyricsLine(LyricsLine.NO_TIME, ""));
            }
            if (langLines.size() == mainIndex) {
                langLines.add(new LyricsLine(LyricsLine.NO_TIME, st.text(), st.words()));
            }
        }

        for (Map.Entry<String, SidecarTranslation> entry : bgByLang.entrySet()) {
            final String lang = "bg:" + entry.getKey();
            final SidecarTranslation st = entry.getValue();
            final List<LyricsLine> langLines = translations.computeIfAbsent(
                    lang, k -> new ArrayList<>());
            final int firstBg = mainIndex + 1;
            while (langLines.size() < firstBg) {
                langLines.add(new LyricsLine(LyricsLine.NO_TIME, ""));
            }
            if (langLines.size() == firstBg) {
                final String bgText = st.bgText() == null ? "" : st.bgText();
                langLines.add(bgText.isEmpty()
                        ? new LyricsLine(LyricsLine.NO_TIME, "")
                        : new LyricsLine(LyricsLine.NO_TIME, bgText, st.bgWords()));
            }
        }

        padDerivedTo(translations, mainIndex + 1 + bgCount);
    }

    private static String readTextContent(XmlPullParser p)
            throws XmlPullParserException, IOException {
        final StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            final int event = p.next();
            if (event == XmlPullParser.END_DOCUMENT) {
                break;
            }
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
            if (event == XmlPullParser.END_DOCUMENT) {
                break;
            }
            if (event == XmlPullParser.START_TAG) {
                depth++;
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            }
        }
    }

    public static long parseTime(@Nullable String raw) {
        return parseTime(raw, DEFAULT_FRAME_RATE);
    }

    /**
     * Parse a TTML time expression to milliseconds.
     *
     * @param raw       The raw time string (begin/end/dur attribute value).
     * @param frameRate Frames per second of the document, for SMPTE timecode.
     * @return Milliseconds, or 0 if the value cannot be parsed or is "indefinite".
     */
    private static long parseTime(@Nullable String raw, int frameRate) {
        if (raw == null || raw.isEmpty()) return 0;
        final String trimmed = raw.trim();

        // "indefinite" means the timing is not known yet.
        if (INDEFINITE.equalsIgnoreCase(trimmed)) {
            return 0;
        }

        final Matcher sm = TIME_SMPTE.matcher(trimmed);
        if (sm.matches()) {
            final long seconds = Long.parseLong(Objects.requireNonNull(sm.group(1))) * 3600
                    + Long.parseLong(Objects.requireNonNull(sm.group(2))) * 60
                    + Long.parseLong(Objects.requireNonNull(sm.group(3)));
            return seconds * 1000 + (long) (Double.parseDouble(Objects.requireNonNull(sm.group(4))) * 1000 / frameRate);
        }

        // Try colon-separated format first: HH:MM:SS.mmm or MM:SS.mmm
        final Matcher cm = TIME_COLON.matcher(trimmed);
        if (cm.matches()) {
            final String g1 = cm.group(1); // hours (HH:MM:SS) or minutes (MM:SS)
            final String g2 = cm.group(2); // minutes (HH:MM:SS only)
            // Seconds are the only mandatory group of TIME_COLON.
            final double sec = Double.parseDouble(Objects.requireNonNull(cm.group(3)));
            if (g1 != null && g2 != null) {
                // HH:MM:SS.mmm
                return (long) ((Integer.parseInt(g1) * 3600L + Integer.parseInt(g2) * 60L + sec) * 1000);
            } else if (g1 != null) {
                // MM:SS.mmm, g1=minutes
                return (long) ((Integer.parseInt(g1) * 60L + sec) * 1000);
            } else {
                // SS.mmm
                return (long) (sec * 1000);
            }
        }

        // Try unit-suffixed format: 3.5s, 100ms, etc.
        final Matcher m = TIME_UNIT.matcher(trimmed);
        if (m.find()) {
            final double value = Double.parseDouble(Objects.requireNonNull(m.group(1)));
            return switch (Objects.requireNonNull(m.group(2))) {
                case "ms" -> (long) value;
                case "m" -> (long) (value * 60_000);
                case "h" -> (long) (value * 3_600_000);
                default -> (long) (value * 1000); // "s"
            };
        }

        // Fallback: bare decimal number treated as seconds
        try {
            return (long) (Double.parseDouble(trimmed) * 1000);
        } catch (NumberFormatException ex) {
            Logger.printDebug(() -> "Could not parse TTML time string: " + trimmed, ex);
            return 0;
        }
    }

    private static int parseFrameRate(@Nullable String raw) {
        if (raw == null) {
            return DEFAULT_FRAME_RATE;
        }
        try {
            final int frameRate = Integer.parseInt(raw.trim());
            return frameRate > 0 ? frameRate : DEFAULT_FRAME_RATE;
        } catch (NumberFormatException ex) {
            Logger.printDebug(() -> "Could not parse TTML frame rate: " + raw, ex);
            return DEFAULT_FRAME_RATE;
        }
    }

    /**
     * Whether a span has usable timing information: a non-null begin that is not
     * "indefinite", plus either an end or a dur attribute.
     */
    private static boolean hasTiming(@Nullable String beginRaw,
                                     @Nullable String endRaw,
                                     @Nullable String durRaw) {
        if (beginRaw == null || INDEFINITE.equalsIgnoreCase(beginRaw.trim())) {
            return false;
        }
        return endRaw != null || durRaw != null;
    }

    /**
     * Resolve the end time of a span: use the {@code end} attribute if present,
     * otherwise compute {@code begin + dur}. Returns 0 if neither is available.
     */
    private static long resolveEndTime(long beginMs,
                                       @Nullable String endRaw,
                                       @Nullable String durRaw,
                                       int frameRate) {
        if (endRaw != null) {
            return parseTime(endRaw, frameRate);
        }
        if (durRaw != null) {
            return beginMs + parseTime(durRaw, frameRate);
        }
        return 0;
    }

    private static String normalizeTextRaw(String s) {
        return MULTI_SPACE.matcher(s).replaceAll(" ");
    }

    private static String normalizeText(String s) {
        return normalizeTextRaw(s).trim();
    }

    /** The tts:ruby role an HTML style ruby element stands for, or null for any other element. */
    @Nullable
    private static String htmlRubyRole(String local) {
        return switch (local) {
            case "ruby" -> "container";
            case "rb" -> "base";
            case "rtc" -> "textContainer";
            case "rt" -> "text";
            default -> null;
        };
    }

    private static String localName(String name) {
        final int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static boolean isMetaNamespace(XmlPullParser p) {
        final String ns = p.getNamespace();
        if (NS_AMLL.equals(ns) || NS_COMPOSER.equals(ns) || NS_COMPOSER_LEGACY.equals(ns)) {
            return true;
        }
        final String raw = p.getName();
        return raw != null && raw.startsWith("composer:");
    }

    private static void collectMetaCredit(XmlPullParser p, List<String> credits) {
        final String ns = p.getNamespace();
        final String key = getAttr(p, ns, "key", "key");
        final String value = getAttr(p, ns, "value", "value");
        if (key == null || value == null || key.isEmpty() || value.isEmpty()) {
            return;
        }
        final Set<String> excluded = NS_AMLL.equals(ns) ? AMLL_EXCLUDED_KEYS : COMPOSER_EXCLUDED_KEYS;
        if (!excluded.contains(key)) {
            credits.add(key + ": " + value.trim());
        }
    }

    /**
     * lrc.red is matched first: the document may also carry Apple's spelling, which the local-name
     * fallback would otherwise let win.
     */
    @Nullable
    private static String getLyricAttr(XmlPullParser p, String local, @Nullable String fallbackQName) {
        final String lrcRed = getAttr(p, NS_LRC_RED, local, null);
        return lrcRed != null ? lrcRed : getAttr(p, NS_ITUNES, local, fallbackQName);
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
    public static Lyrics ttmlToLyrics(String ttml, String providerName,
                                      @Nullable String sourceUrl) {
        if (ttml == null || ttml.isEmpty()) return null;
        TtmlResult result = parse(ttml);
        if (result == null || result.lines().isEmpty()) return null;

        final int offsetMs = result.offsetMs();
        final List<LyricsLine> shiftedLines = shiftLines(result.lines(), offsetMs);
        final List<LyricsLine> shiftedRoman = shiftLines(result.romanization(), offsetMs);
        final Map<String, List<LyricsLine>> shiftedTrans = shiftLineMap(result.translations(), offsetMs);
        final Map<String, List<LyricsLine>> shiftedRoma = shiftLineMap(result.romanizations(), offsetMs);

        boolean synced = shiftedLines.stream()
                .anyMatch(line -> line.startTimeMs() != LyricsLine.NO_TIME);

        final String finalProvider = (providerName != null && !providerName.isEmpty())
                ? providerName : (result.source() != null ? result.source() : "");
        final String finalSourceUrl = (sourceUrl != null && !sourceUrl.isEmpty())
                ? sourceUrl : result.sourceUrl();

        return new Lyrics(shiftedLines, finalProvider, synced,
                shiftedRoman, shiftedTrans, shiftedRoma,
                result.songwriters(), result.credits(),
                ttml, "ttml", finalSourceUrl,
                result.about(), result.agentNames());
    }

    private static LyricsLine shiftLine(LyricsLine line, int offsetMs) {
        if (offsetMs == 0) return line;
        List<Word> shiftedWords = line.words();
        if (!shiftedWords.isEmpty()) {
            List<Word> newWords = new ArrayList<>(shiftedWords.size());
            for (Word w : shiftedWords) {
                newWords.add(new Word(w.startMs() + offsetMs, w.endMs() + offsetMs,
                        w.text(), w.romaji(), w.endsWithSpace(), w.ruby()));
            }
            shiftedWords = newWords;
        }
        long newStart = line.startTimeMs() != LyricsLine.NO_TIME
                ? line.startTimeMs() + offsetMs : LyricsLine.NO_TIME;
        long newEnd = line.endTimeMs() != LyricsLine.NO_TIME
                ? line.endTimeMs() + offsetMs : LyricsLine.NO_TIME;
        return new LyricsLine(newStart, newEnd, line.text(), shiftedWords,
                line.agentId(), line.isDuet(), line.isBG(), line.songPart());
    }

    private static List<LyricsLine> shiftLines(@Nullable List<LyricsLine> lines, int offsetMs) {
        if (lines == null || offsetMs == 0) return lines;
        List<LyricsLine> shifted = new ArrayList<>(lines.size());
        for (LyricsLine line : lines) {
            shifted.add(shiftLine(line, offsetMs));
        }
        return shifted;
    }

    private static Map<String, List<LyricsLine>> shiftLineMap(
            @Nullable Map<String, List<LyricsLine>> map, int offsetMs) {
        if (map == null || offsetMs == 0) return map;
        Map<String, List<LyricsLine>> shifted = new HashMap<>(map.size());
        for (Map.Entry<String, List<LyricsLine>> entry : map.entrySet()) {
            shifted.put(entry.getKey(), shiftLines(entry.getValue(), offsetMs));
        }
        return shifted;
    }

    @Nullable
    private static List<String> combineCreditLists(@Nullable List<String> first,
                                                   @Nullable List<String> second) {
        if (first == null && second == null) {
            return null;
        }
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        List<String> combined = new ArrayList<>(first);
        combined.addAll(second);
        return combined;
    }

    private record HeadMetadata(
            List<String> songwriters,
            List<String> amllCredits,
            List<String> morpheCredits,
            Map<String, AgentInfo> agentTypes,
            Map<String, List<RomajiSyllable>> sidecarRoman,
            Map<String, SidecarTranslation> sidecarTrans,
            @Nullable String desc,
            @Nullable String morpheSource,
            @Nullable String morpheSourceUrl,
            int offsetMs) {
    }

    public record TtmlResult(List<LyricsLine> lines,
                             @Nullable List<LyricsLine> romanization,
                             @Nullable Map<String, List<LyricsLine>> translations,
                             @Nullable Map<String, List<LyricsLine>> romanizations,
                             @Nullable List<String> songwriters,
                             @Nullable List<String> credits,
                             @Nullable Map<String, String> agentNames,
                             @Nullable String about,
                             @Nullable String source,
                             @Nullable String sourceUrl,
                             int offsetMs) {
    }

    private record RomajiSyllable(long startMs, long endMs, String text) {
    }

    private record AgentInfo(String type, @Nullable String name) {
    }

    private record SidecarTranslation(String text, @Nullable List<Word> words,
                                      @Nullable String bgText, @Nullable List<Word> bgWords) {
    }

    private record ParsedLine(long begin, long end, String text, List<Word> words,
                              List<LyricsLine> bgLines,
                              @Nullable String bgAgentId,
                              @Nullable Map<String, String> inlineTranslations,
                              @Nullable Map<String, String> inlineRomanizations,
                              @Nullable Map<String, String> bgInlineTranslations,
                              @Nullable Map<String, String> bgInlineRomanizations) {
    }
}
