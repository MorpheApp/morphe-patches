/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class MiniLyricsGapTest {
    private static LyricsLine line(long start, long end, String text) {
        return new LyricsLine(start, end, text, List.of());
    }
    private static MiniLyricsTimeline timeline() {
        return new MiniLyricsTimeline(List.of(line(10000,15000,"first lyric"),
                line(15000,45000,"♪♪♪"),line(45000,50000,"second lyric")),60000);
    }
    @Test public void oddWordAndCharacterCountsFavorFirstHalf() {
        assertEquals(List.of("one two three","four five"),MiniLyricsFormatter.chunks(
                new LyricsLine(0,"one two three four five"),10000,3,false,false)
                .stream().map(MiniLyricsFormatter.Chunk::text).toList());
        assertEquals(List.of("你好世界" + MiniLyricsFormatter.SPLIT_ELLIPSIS,"再见你"),MiniLyricsFormatter.chunks(
                new LyricsLine(0,"你好世界再见你"),10000,3,false,false)
                .stream().map(MiniLyricsFormatter.Chunk::text).toList());
    }
    @Test public void thresholdEqualityDoesNotSplit() {
        assertEquals(1,MiniLyricsFormatter.chunks(new LyricsLine(0,"one two"),5000,7,false,false).size());
        assertEquals(2,MiniLyricsFormatter.chunks(new LyricsLine(0,"one two"),5000,6,false,false).size());
    }
    @Test public void thirtySecondGapAllocatesTenSecondsPerFrame() {
        for(long position:new long[]{15000,24999})assertEquals("♪",frame(position));
        for(long position:new long[]{25000,34999})assertEquals("♪♪",frame(position));
        for(long position:new long[]{35000,44999})assertEquals("♪♪♪",frame(position));
    }
    private static String frame(long position) {
        return MiniLyricsFormatter.instrumental(null,15000,45000,position,"title","artist","last");
    }
    @Test public void seekingAndPausingArePositionDriven() {
        assertEquals("♪♪♪",frame(40000));assertEquals("♪",frame(17000));
        assertEquals(frame(30000),frame(30000));
    }
    @Test public void gapFramesAcceptAllVariablesWithoutRecursiveExpansion() {
        assertEquals("T / A: ♪ Last line ♪",MiniLyricsFormatter.instrumental(
                "{t} / {a}: {lrc}",0,30000,10000,"T","A","♪ Last line ♪"));
        assertEquals("{t} / T",MiniLyricsFormatter.instrumental(
                "{lrc} / {t}",0,30000,10000,"T","A","{t}"));
    }
    @Test public void lastLyricOnlyIsAnExactHoldIncludingDecoration() {
        String previous="♪ Last line. ♪";
        assertEquals(previous,MiniLyricsFormatter.instrumental("{lrc}",0,30000,25000,"T","A",previous));
    }
    @Test public void emptyFramesAreIntentionalAndPreserved() {
        assertEquals("",MiniLyricsFormatter.instrumental("first\n\nlast",0,30000,15000,"T","A","last lyric"));
        assertEquals("",MiniLyricsFormatter.instrumental("",0,30000,10000,"T","A","last lyric"));
        assertEquals("",MiniLyricsFormatter.instrumental("first\n",0,30000,20000,"T","A","last lyric"));
    }
    @Test public void noteMarkersNeverBecomeSungLyrics() {
        for(String text:List.of(""," ","♪","♪♪","♪♪♪","♫","♬","♩","🎵","🎶","♪ ♫"))
            assertTrue(text,MiniLyricsTimeline.isInstrumentalMarker(text));
        assertFalse(MiniLyricsTimeline.isInstrumentalMarker("♪ actual lyric ♪"));
        assertNull(timeline().at(20000).vocal());
        assertEquals(15000,timeline().at(20000).gap().startMs());
        assertEquals(45000,timeline().at(20000).gap().endMs());
    }
    @Test public void introVocalGapOutroAndBoundariesAreDistinct() {
        MiniLyricsTimeline t=timeline();
        assertEquals(10000,t.at(0).gap().endMs());
        assertNull(t.at(0).gap().previous());
        assertEquals("first lyric",t.at(10000).vocal().line().text());
        assertEquals("first lyric",t.at(15000).gap().previous().line().text());
        assertEquals("second lyric",t.at(45000).vocal().line().text());
        assertNull(t.at(50000).gap());
    }
    @Test public void explicitVocalEndWorksWithoutAnyNoteMarker() {
        MiniLyricsTimeline t=new MiniLyricsTimeline(List.of(line(0,5000,"one"),line(35000,40000,"two")),45000);
        assertEquals(5000,t.at(15000).gap().startMs());
        assertEquals(35000,t.at(15000).gap().endMs());
    }
    @Test public void shortWordEndsDoNotOverrideThePanelsGapDecision() {
        LyricsLine vocal=new LyricsLine(0,10000,"one two",List.of(
                new Word(0,1000,"one"),new Word(1000,2000,"two")));
        MiniLyricsTimeline t=new MiniLyricsTimeline(List.of(vocal,line(10000,15000,"next")),20000);
        assertNotNull(t.at(1999).vocal());assertNull(t.at(2000).gap());
    }
    @Test public void backgroundVocalsDoNotBecomeFalseInstrumentalGaps() {
        LyricsLine background=new LyricsLine(1800,4000,"backing",List.of(),null,false,true,null);
        MiniLyricsTimeline t=new MiniLyricsTimeline(List.of(line(0,2000,"lead"),background,line(10000,15000,"next")),20000);
        assertEquals("lead",t.at(1900).vocal().line().text());
        assertEquals("backing",t.at(3000).vocal().line().text());
        assertEquals(4000,t.at(4000).gap().startMs());
    }
    @Test public void anUnknownLineEndDoesNotInventSilence() {
        MiniLyricsTimeline t=new MiniLyricsTimeline(List.of(new LyricsLine(0,"held lyric")),0);
        assertNotNull(t.at(100000).vocal());assertNull(t.at(100000).gap());
    }
    @Test public void unknownGapDurationHoldsItsFirstFrame() {
        assertEquals("♪",MiniLyricsFormatter.instrumental(null,1000,Long.MAX_VALUE,100000,"T","A","last"));
    }
    @Test public void markerOnlyTrackUsesItsExplicitInterval() {
        MiniLyricsTimeline t=new MiniLyricsTimeline(List.of(line(0,30000,"♪♪♪")),30000);
        assertNull(t.at(10000).vocal());assertEquals(30000,t.at(10000).gap().endMs());
    }
    @Test public void varyingFrameCountsUseTheWholeGapOnce() {
        assertEquals("two",MiniLyricsFormatter.instrumental("one\ntwo",0,10000,5000,"T","A","last"));
        assertEquals("last",MiniLyricsFormatter.instrumental("one\nmid\nlast",0,10000,10000,"T","A","previous"));
    }
    @Test public void shortPausesBetweenLinesNeverShowInstrumentalText() {
        MiniLyricsTimeline t=new MiniLyricsTimeline(List.of(line(0,5000,"one"),line(6500,9000,"two"),line(10000,12000,"three")),20000);
        assertNull(t.at(5200).gap());assertNull(t.at(9500).gap());
        assertEquals("one",t.at(5200).previous().line().text());
        assertEquals("two",t.at(9500).previous().line().text());
    }
    @Test public void nothingIsPreviousBeforeTheFirstLine() {
        MiniLyricsTimeline t=new MiniLyricsTimeline(List.of(line(2000,5000,"one")),20000);
        assertNull(t.at(1000).vocal());assertNull(t.at(1000).gap());assertNull(t.at(1000).previous());
    }
    @Test public void panelGapClassifierIsAuthoritative() {
        var words = List.of(new Word(0,1000,"one"),new Word(1000,2000,"two"));
        var lines = List.of(new LyricsLine(0,10000,"one two",words),line(15000,18000,"next"));
        var t = new MiniLyricsTimeline(lines,20000);
        assertNull("The panel doesn't show dots at 5 seconds", t.at(5000).gap());
        assertNotNull(t.at(11000).gap());
        assertEquals(10000,t.at(11000).gap().startMs());
    }
}
