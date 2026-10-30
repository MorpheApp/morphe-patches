package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class StableCaptionBlockPolicyTest {
    static byte[] xml(String body) {
        return ("<timedtext format='3'><head><ws id='1' sd='3'/></head><body><w t='0' id='1'/>"
                        + body
                        + "</body></timedtext>")
                .getBytes(StandardCharsets.UTF_8);
    }

    static String p(int t, int d, String s) {
        return "<p t='" + t + "' d='" + d + "' w='1'>" + s + "</p>";
    }

    static String s(int t, String text) {
        return "<s t='" + t + "'>" + text + "</s>";
    }

    private static StableCaptionBlockPolicy parse(byte[] input) {
        return StableCaptionBlockPolicy.parse(CaptionXml.read(input));
    }

    static StableCaptionBlockPolicy policy(String body) {
        StableCaptionBlockPolicy p = parse(xml(body));
        assertNotNull(p);
        return p;
    }

    @Test public void sharedSourceFallbackDoesNotJoinPunctuationAcrossClear() {
        var blocks = policy(p(0, 5000, "Hello") + p(1000, 1000, "")
                + p(4000, 1000, ".") + p(5000, 3000, " Next sentence")).sourceBlocks("en");
        assertEquals("Hello", blocks.get(0).text);
        assertEquals(1000, blocks.get(0).end);
        assertEquals(4000, blocks.get(1).start);
    }
    @Test public void sharedSourceBoundsLongWordTimedPhrasesButKeepsUntimedParagraphs() {
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 24; i++) words.append(s(i * 250, " lengthyword"));
        for (var block : policy(p(0, 6000, words.toString())).sourceBlocks("en"))
            assertTrue(block.text.codePointCount(0, block.text.length()) <= 80);
        String paragraph = "A native paragraph has no internal word times and must remain intact even when its text is fairly long.";
        var blocks = policy(p(0, 6000, paragraph)).sourceBlocks("en");
        assertEquals(1, blocks.size());
        assertEquals(paragraph, blocks.get(0).text);
    }

    @Test public void sharedSourceBalancesShortPunctuationTailsWithoutOversizedMerges() {
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 40; i++) words.append(s(i * 200, " word" + i + (i == 9 || i == 17 ? "." : "")));
        var blocks = policy(p(0, 8000, words.toString())).sourceBlocks("en");
        for (var block : blocks) {
            assertTrue(block.text.split("\\s+").length <= 15);
            assertTrue(block.end - block.start >= 1500);
        }
    }

    @Test public void sharedSourceKeepsClearAfterNormalizingNativeWraps() {
        var source = StableCaptionBlockPolicy.parseSource(CaptionXml.read(xml(
                p(0, 5000, "Hello\nthere.") + p(1000, 1000, "\n")
                + p(5000, 3000, "Another\nphrase."))));
        assertNotNull(source);
        var blocks = source.sourceBlocks("en");
        assertEquals(2, blocks.size());
        assertEquals("Hello there.", blocks.get(0).text);
        assertEquals(1000, blocks.get(0).end);
        assertEquals(5000, blocks.get(1).start);
        assertEquals("Another phrase.", blocks.get(1).text);
    }

    @Test public void sharedSourceAcceptsNativeGroupedAsrWithoutInventingWordTimes() {
        byte[] input = xml(p(0, 5880, "Everyone's final straw with Windows is a\nlittle bit different. Maybe it's a")
                + p(4080, 6840, "headline about yet another privacy\nissue. Maybe it's an update at a really"));
        assertNull(parse(input)); // Existing native/translated policy remains conservative.
        var source = StableCaptionBlockPolicy.parseSource(CaptionXml.read(input));
        assertNotNull(source);
        var blocks = source.sourceBlocks("en");
        assertEquals(2, blocks.size());
        assertEquals(0, blocks.get(0).start);
        assertEquals(4080, blocks.get(0).end);
        assertEquals(4080, blocks.get(1).start);
        assertEquals(10920, blocks.get(1).end);
        assertFalse(blocks.get(0).text.contains("\n"));
        assertTrue(blocks.get(0).text.contains("a little"));
    }

    @Test
    public void sharedSourceScheduleUsesNativeWordBoundariesAndKeepsAllText() {
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 40; i++) words.append(s(i * 200, (i == 0 ? "" : " ") + "word" + i));
        var policy = policy(p(0, 8000, words.toString()));
        var blocks = policy.sourceBlocks("en");
        assertFalse(blocks.isEmpty());
        assertEquals(0, blocks.get(0).start);
        assertEquals(8000, blocks.get(blocks.size() - 1).end);
        for (var block : blocks) {
            assertTrue(block.end - block.start >= 1500);
            assertTrue(block.text.split("\\s+").length <= 15);
        }
        assertEquals(policy.plan(t -> true).stream().map(b -> b.text).collect(java.util.stream.Collectors.joining()).replaceAll("\\s+", ""),
                blocks.stream().map(b -> b.text).collect(java.util.stream.Collectors.joining()).replaceAll("\\s+", ""));
    }

    @Test
    public void sharedSourceScheduleBalancesShortTailWithoutInventingTime() {
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 31; i++) words.append(s(i * 200, "你"));
        var blocks = policy(p(0, 6200, words.toString())).sourceBlocks("zh-Hans");
        assertEquals(2, blocks.size());
        assertEquals(0, blocks.get(0).start);
        assertEquals(6200, blocks.get(1).end);
        assertEquals(31, blocks.stream().mapToInt(b -> b.text.codePointCount(0, b.text.length())).sum());
        for (var block : blocks) assertTrue(block.end - block.start >= 1500);
    }

    @Test
    public void sharedSourceScheduleNeverMergesAcrossClear() {
        var blocks = policy(p(0, 5000, "Hello there.") + p(1000, 1000, "")
                + p(5000, 3000, "Another sentence.")).sourceBlocks("en");
        assertEquals(2, blocks.size());
        assertEquals(1000, blocks.get(0).end);
        assertNull(StableCaptionBlockPolicy.at(blocks, 3000));
        assertEquals(5000, blocks.get(1).start);
    }

    @Test
    public void sharedSourceScheduleDoesNotCutWordFragmentsOrUnicode() {
        var blocks = policy(p(0, 6000, s(0, "trans") + s(1000, "lation")
                + s(2000, " stays") + s(3000, " intact 🐸."))).sourceBlocks("en");
        assertEquals(1, blocks.size());
        assertEquals("translation stays intact 🐸.", blocks.get(0).text);
        assertTrue(blocks.get(0).fits);
    }

    @Test
    public void alreadyCenteredFrameRetainsItsNativeList() {
        var frame = new StableCaptionBlocks.Frame(new StableCaptionBlocks.State(false), 0, 0);
        var cue = new StableCaptionBlocks.Cue() {
            @Override public CharSequence patch_getOriginalCaptionVisible() { return "native"; }
            @Override public StableCaptionBlocks.Frame patch_getBlockFrame() { return frame; }
            @Override public Object patch_copyBlockFrame(StableCaptionBlocks.Frame requested) {
                assertSame(frame, requested);
                return this;
            }
        };
        List<?> original = List.of(cue);
        assertSame(original, StableCaptionBlocks.centerBlocks(original));
    }

    @Test
    public void sharedDocumentKeepsSourcePlanWhenRepairRemovesOrphanEvents() {
        byte[] source =
                ("<timedtext format='3'><head><ws id='1' mh='2'/></head>"
                                + "<body><w t='0' id='1' ws='1'/>"
                                + p(0, 2000, "one")
                                + "<p t='1000' d='1000' w='1' a='1'>\n</p>"
                                + "<p t='1500' d='2000' w='1' a='1'>\n</p>"
                                + p(2000, 4000, "two")
                                + "</body></timedtext>")
                        .getBytes(StandardCharsets.UTF_8);
        var document = CaptionXml.read(source);
        assertNotNull(document);
        var policy = StableCaptionBlockPolicy.parse(document);
        assertNotNull(policy);
        var before = policy.plan(text -> true);
        byte[] repaired = TranslatedRollingCaptionPolicy.repair(source, document);
        assertNotSame(source, repaired);
        assertEquals(2, document.getElementsByTagName("p").getLength());
        assertEquals(4, CaptionXml.read(source).getElementsByTagName("p").getLength());
        var after = policy.plan(text -> true);
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).start, after.get(i).start);
            assertEquals(before.get(i).end, after.get(i).end);
            assertEquals(before.get(i).text, after.get(i).text);
        }
        assertSame(repaired, TranslatedRollingCaptionPolicy.repair(repaired, CaptionXml.read(repaired)));
        assertSame(source, TranslatedRollingCaptionPolicy.repair(source, null));
    }

    @Test
    public void preservesSeparatelyTimedWhitespaceWithoutInventingWordTimes() {
        var policy = policy(p(0, 4000, s(0, "hello") + s(500, " ") + s(1000, "world")));
        var blocks = policy.plan(text -> true);
        assertEquals("hello world", blocks.get(0).text);
        assertEquals(0, blocks.get(0).start);
        assertEquals(1000, policy.templateTime(1100));
    }

    @Test
    public void joinsAdjacentSourceParagraphsIntoOneReadableBlock() {
        var p =
                policy(
                        p(0, 4000, s(0, "one ") + s(1000, "two"))
                                + p(2000, 5000, s(0, "three ") + s(1000, "four")));
        var b = p.plan(text -> text.length() <= 24);
        assertEquals(1, b.size());
        assertEquals("one two three four", b.get(0).text);
        assertEquals(0, b.get(0).start);
        assertEquals(7000, b.get(0).end);
    }

    @Test
    public void usesSourceBoundariesAndKeepsWholeBlockStable() {
        var p =
                policy(
                        p(
                                0,
                                6000,
                                s(0, "one ")
                                        + s(1000, "two ")
                                        + s(2000, "three ")
                                        + s(3000, "four ")
                                        + s(4000, "five")));
        var b = p.plan(text -> text.length() <= 14);
        assertTrue(b.size() > 1);
        for (var block : b) {
            assertEquals(0, block.start % 1000);
            assertTrue(block.fits);
            assertTrue(block.text.length() <= 14);
            assertTrue(block.end > block.start);
        }
        assertEquals(
                "one two three four five",
                String.join(" ", b.stream().map(x -> x.text).toArray(String[]::new)));
    }

    @Test
    public void redistributesShortTailWhenBothPiecesStillFit() {
        var p =
                policy(
                        p(
                                0,
                                6500,
                                s(0, "a ")
                                        + s(1500, "b ")
                                        + s(3000, "c ")
                                        + s(4500, "d ")
                                        + s(6000, "e")));
        var b = p.plan(text -> text.length() <= 7);
        assertEquals(2, b.size());
        assertTrue(b.get(0).end - b.get(0).start >= 1500);
        assertTrue(b.get(1).end - b.get(1).start >= 1500);
    }

    @Test
    public void sameTimeAtomsCannotBecomeTwoDifferentBlocks() {
        var p = policy(p(0, 4000, s(0, "one ") + s(0, "two ") + s(2000, "three")));
        var b = p.plan(text -> text.length() <= 5);
        assertEquals("one two", b.get(0).text);
        assertFalse(b.get(0).fits);
        assertEquals(2000, b.get(1).start);
    }

    @Test
    public void sourceGapsStayEmptyAndPreviewIsBounded() {
        var p =
                policy(
                        p(0, 1000, s(0, "first"))
                                + p(4000, 2000, s(0, "second"))
                                + p(20000, 1000, s(0, "third")));
        var b = p.plan(text -> true);
        assertEquals(3, b.size());
        assertEquals(1000, b.get(0).end);
        assertEquals(4000, b.get(1).start);
        var slow = policy(p(0, 14000, s(0, "first ") + s(10000, "second"))).plan(text -> true);
        assertEquals(2, slow.size());
        assertEquals(10000, slow.get(0).end);
    }

    @Test
    public void preservesUnicodeWithoutSplittingAtomicText() {
        for (String text : List.of("שלום עולם", "مرحبا بالعالم", "日本語字幕", "👩🏽‍💻 merhaba")) {
            var b = policy(p(0, 3000, s(0, text))).plan(value -> true);
            assertEquals(text, b.get(0).text);
        }
    }

    @Test
    public void unsupportedStructureFallsBackBeforePresentation() {
        assertNull(parse(xml("<w t='1' id='2'/>" + p(0, 1000, "hello"))));
        assertNull(parse(xml(p(0, 1000, "<b>styled</b>"))));
        assertNull(parse(xml(p(0, 1000, s(500, "a ") + s(100, "b")))));
    }

    @Test
    public void recognizesOnlyVerifiedAutomaticTracks() {
        assertTrue(
                AutoTranslateCaptionsPatch.isAutomaticCaption(
                        "a.en", "https://www.youtube.com/api/timedtext?kind=asr"));
        assertTrue(
                AutoTranslateCaptionsPatch.isAutomaticCaption(
                        "ta.en.tr", "https://www.youtube.com/api/timedtext?kind=asr&tlang=tr"));
        assertFalse(
                AutoTranslateCaptionsPatch.isAutomaticCaption(
                        ".en", "https://www.youtube.com/api/timedtext?kind=asr"));
        assertFalse(
                AutoTranslateCaptionsPatch.isAutomaticCaption(
                        "a.en", "https://evil.example/api/timedtext?kind=asr"));
    }

    @Test
    public void planIsSeekableAndDoesNotInventSubspanTimes() {
        var p = policy(p(0, 16000, s(0, "a ") + s(4000, "b ") + s(8000, "c ") + s(12000, "d")));
        var plan = p.plan(text -> true);
        assertEquals(2, plan.size());
        assertSame(plan.get(0), StableCaptionBlockPolicy.at(plan, 100));
        assertSame(plan.get(1), StableCaptionBlockPolicy.at(plan, 12500));
        assertSame(plan.get(0), StableCaptionBlockPolicy.at(plan, 100));
        assertNull(StableCaptionBlockPolicy.at(plan, 16000));
        assertEquals(
                "a b c d", String.join(" ", plan.stream().map(b -> b.text).toArray(String[]::new)));
    }

    @Test
    public void longUnbreakableSegmentIsMarkedForNativeFallback() {
        var plan = policy(p(0, 4000, s(0, "unbreakable"))).plan(text -> false);
        assertEquals(1, plan.size());
        assertFalse(plan.get(0).fits);
        assertEquals("unbreakable", plan.get(0).text);
    }

    @Test
    public void styledAndOversizedAtomicSourcesRemainNative() {
        String styled =
                "<timedtext format='3'><head><pen id='1' b='1'/></head><body><w t='0' id='1'/>"
                        + p(0, 1000, "style")
                        + "</body></timedtext>";
        assertNull(parse(styled.getBytes(StandardCharsets.UTF_8)));
        assertNull(
                parse(
                        xml(p(0, 1000, s(0, "x".repeat(3000)) + s(0, "y".repeat(3000))))));
    }

    @Test
    public void sourcePunctuationIsNotSeparatedIntoAnOrphanToken() {
        var plan =
                policy(p(0, 3000, "hello") + p(1000, 3000, ".") + p(2000, 3000, "world"))
                        .plan(text -> true);
        assertEquals("hello. world", plan.get(0).text);
    }

    @Test
    public void emptySourceParagraphCreatesAnActualGap() {
        var p =
                policy(
                        p(0, 5000, s(0, "first"))
                                + p(1000, 2000, "")
                                + p(3000, 2000, s(0, "second")));
        var blocks = p.plan(text -> true);
        assertEquals(2, blocks.size());
        assertEquals(1000, blocks.get(0).end);
        assertNull(StableCaptionBlockPolicy.at(blocks, 2000));
    }

    @Test
    public void balancesReadingLoadBeforeDenseTranslationAfterShortGap() {
        String first =
                s(0, "Aksi ")
                        + s(320, "takdirde, ")
                        + s(640, "geri ")
                        + s(960, "kalanını ")
                        + s(1280, "olduğu ")
                        + s(1600, "gibi ")
                        + s(1920, "bırakın");
        String[] words =
                "bunu yapmanıza izin vermeden önce Windows'taki yönetici erişimine kabaca eşdeğer olan sudo'yu gerektirerek size bunu hatırlatacaktır."
                        .split(" ");
        StringBuilder dense = new StringBuilder();
        for (int i = 0; i < words.length; i++) dense.append(s(i * 101, words[i] + " "));
        var p =
                policy(
                        p(505359, 4400, first)
                                + p(507440, 4959, s(0, "ve ") + s(2159, "Linux,"))
                                + p(514159, 3281, dense.toString()));
        var blocks = p.plan(text -> text.length() <= 110);
        double worst = 0;
        for (var b : blocks) worst = Math.max(worst, b.text.length() * 1000.0 / (b.end - b.start));
        assertTrue("dense translation must borrow reading time, worst=" + worst, worst < 40);
    }

    @Test
    public void borrowsShortUnclearedGapButNeverRevivesExplicitClear() {
        var p = policy(p(0, 1000, "first") + p(2500, 1000, "second"));
        var blocks = p.plan(text -> text.length() <= 6);
        assertEquals(2500, blocks.get(0).end);
        assertEquals(0, p.templateTime(2000));
        assertNotNull(StableCaptionBlockPolicy.at(blocks, 2000));
        var cleared = policy(p(0, 3000, "first") + p(1000, 1000, "") + p(2500, 1000, "second"));
        assertEquals(-1, cleared.templateTime(2000));
        assertNull(StableCaptionBlockPolicy.at(cleared.plan(text -> true), 2000));
        assertEquals(-1, cleared.templateTime(-1));
        assertEquals(-1, cleared.templateTime(4000));
    }

    @Test
    public void neverCutsTimedPartsInsideOneWord() {
        var blocks =
                policy(p(0, 3000, s(0, "win") + s(1000, "dows") + s(2000, ".")))
                        .plan(text -> text.length() <= 4);
        assertEquals(1, blocks.size());
        assertEquals("windows.", blocks.get(0).text);
        assertFalse(blocks.get(0).fits);
    }
    @Test
    public void japaneseTimedPhrasesCanBreakWithoutAsciiSpaces() {
        var source = policy(p(0, 6000,
                s(0, "今日は日本語を勉強します。") + s(3000, "明日は友達と学校に行きます。")));
        var blocks = source.plan(text -> text.codePointCount(0, text.length()) <= 26);
        assertEquals(2, blocks.size());
        assertEquals("今日は日本語を勉強します。", blocks.get(0).text);
        assertEquals("明日は友達と学校に行きます。", blocks.get(1).text);
        assertTrue(blocks.get(0).fits && blocks.get(1).fits);
        assertEquals(3000, blocks.get(0).end);
        assertEquals(3000, blocks.get(1).start);
    }

    @Test
    public void chineseTimedPhrasesPreserveTextAndSourceTimes() {
        var source = policy(p(0, 6000,
                s(0, "今天我们一起学习中文。") + s(3000, "明天我们一起去学校。")));
        var blocks = source.plan(text -> text.length() <= 14);
        assertEquals(2, blocks.size());
        assertEquals("今天我们一起学习中文。", blocks.get(0).text);
        assertEquals("明天我们一起去学校。", blocks.get(1).text);
        assertTrue(blocks.get(0).fits && blocks.get(1).fits);
        assertEquals(0, blocks.get(0).start);
        assertEquals(6000, blocks.get(1).end);
    }

    @Test
    public void timedLatinFragmentsDoNotCreateWordBreaks() {
        var source = policy(p(0, 6000,
                s(0, "inter") + s(1000, "national") + s(3000, " words")));
        var blocks = source.plan(text -> text.length() <= 9);
        assertEquals("international", blocks.get(0).text);
        assertFalse(blocks.get(0).fits);
        assertEquals("words", blocks.get(1).text);
    }

    private static org.w3c.dom.Document doc(String body) {
        return CaptionXml.read(("<timedtext format='3'><head/><body><w t='0' id='1'/>"
                + body + "</body></timedtext>").getBytes(StandardCharsets.UTF_8));
    }

    @Test public void sourceSnapshotIsImmutable() {
        var document = doc(p(0, 4000, "original"));
        var source = StableCaptionBlockPolicy.parse(document);
        document.getElementsByTagName("p").item(0).setTextContent("changed");
        assertEquals("original", source.sourceBlocks("en").get(0).text);
    }
    @Test
    public void manualPositionsAreNotEligibleForDualReflow() {
        var positioned = doc(p(0, 4000, "hello"));
        var head = positioned.getElementsByTagName("head").item(0);
        var position = positioned.createElement("wp");
        position.setAttribute("id", "1");
        position.setAttribute("ah", "10");
        position.setAttribute("av", "10");
        head.appendChild(position);
        assertNotNull(StableCaptionBlockPolicy.parse(positioned));
        assertNull(StableCaptionBlockPolicy.parseManual(positioned));
        var inline = doc(p(0, 4000, "hello"));
        ((org.w3c.dom.Element) inline.getElementsByTagName("w").item(0)).setAttribute("wp", "1");
        assertNull(StableCaptionBlockPolicy.parseManual(inline));
    }

    @Test
    public void plainManualAndAutomaticWindowStylesRemainEligible() {
        assertNotNull(StableCaptionBlockPolicy.parseManual(doc(p(0, 4000, "hello"))));
        var automatic = doc(p(0, 4000, "hello"));
        automatic.getElementsByTagName("head").item(0).appendChild(automatic.createElement("ws"));
        assertNotNull(StableCaptionBlockPolicy.parse(automatic));
        assertNotNull(StableCaptionBlockPolicy.parseManual(automatic));
    }
    @Test public void manualCuesKeepTheirOwnClockAndDoNotMergeAcrossShortGaps() {
        var document = CaptionXml.read(("<timedtext format='3'><head/><body>"
                + "<p t='1000' d='1000'>First\nphrase.</p>"
                + "<p t='2200' d='1800'>Next phrase.</p></body></timedtext>").getBytes(StandardCharsets.UTF_8));
        var manual = StableCaptionBlockPolicy.parseManual(document);
        assertNotNull(manual);
        for (var blocks : List.of(manual.sourceBlocks("en"), manual.plan(text -> true))) {
            assertEquals(2, blocks.size());
            assertEquals("First phrase.", blocks.get(0).text);
            assertEquals(1000, blocks.get(0).start);
            assertEquals(2000, blocks.get(0).end);
            assertEquals(2200, blocks.get(1).start);
            assertEquals(4000, blocks.get(1).end);
        }
        assertEquals("First\nphrase.", document.getElementsByTagName("p").item(0).getTextContent());
    }
    @Test public void punctuationLeadingManualCueCannotMergeAcrossAuthorBoundary() {
        var manual = StableCaptionBlockPolicy.parseManual(doc(p(1000, 1000, "First") + p(2200, 1800, ", continued.")));
        assertNotNull(manual);
        for (var blocks : List.of(manual.sourceBlocks("en"), manual.plan(text -> true))) {
            assertEquals(2, blocks.size());
            assertEquals("First", blocks.get(0).text);
            assertEquals(2000, blocks.get(0).end);
            assertEquals(2200, blocks.get(1).start);
        }
    }
    @Test public void standardBottomCenterManualWindowIsEligible() {
        String xml = "<timedtext format='3'><head><ws id='0'/><ws id='1' ju='2'/>"
                + "<wp id='0'/><wp id='1' ap='7' ah='50' av='100' rc='2' cc='40'/></head>"
                + "<body><w t='0' id='1' wp='1' ws='1'/>"
                + p(1000, 3000, "Normal caption.") + "</body></timedtext>";
        assertNotNull(StableCaptionBlockPolicy.parseManual(CaptionXml.read(xml.getBytes(StandardCharsets.UTF_8))));
        assertNull(StableCaptionBlockPolicy.parseManual(CaptionXml.read(xml.replace("ah='50'", "ah='10'").getBytes(StandardCharsets.UTF_8))));
        assertNull(StableCaptionBlockPolicy.parseManual(CaptionXml.read(xml.replace("ju='2'", "ju='0'").getBytes(StandardCharsets.UTF_8))));
        assertNull(StableCaptionBlockPolicy.parseManual(CaptionXml.read(xml.replace("ws='1'", "ws='9'").getBytes(StandardCharsets.UTF_8))));
    }
    @Test public void manualOverlapsAndTimedKaraokeStayNative() {
        assertNull(StableCaptionBlockPolicy.parseManual(doc(p(1000, 3000, "one") + p(2000, 3000, "two"))));
        assertNull(StableCaptionBlockPolicy.parseManual(doc(p(1000, 3000, s(0, "one") + s(500, " two")))));
    }

    @Test public void longVideoCaptionClockDoesNotDiscardEarlierCues() {
        var document = doc(p(264000, 3000, "Earlier phrase.") + p(9000000, 3000, "Later phrase."));
        for (var policy : new StableCaptionBlockPolicy[]{StableCaptionBlockPolicy.parseManual(document), StableCaptionBlockPolicy.parseSource(document)}) {
            assertNotNull(policy);
            var blocks = policy.sourceBlocks("en");
            assertEquals("Earlier phrase.", StableCaptionBlockPolicy.at(blocks, 265000).text);
            assertEquals("Later phrase.", StableCaptionBlockPolicy.at(blocks, 9001000).text);
            assertEquals(9003000, blocks.get(1).end);
        }
    }
    @Test public void overflowingCaptionClockStaysNative() {
        String body = "<p t='9223372036854775000' d='3000' w='1'>Overflow.</p>";
        assertNull(StableCaptionBlockPolicy.parseManual(doc(body)));
        assertNull(StableCaptionBlockPolicy.parseSource(doc(body)));
    }

}
