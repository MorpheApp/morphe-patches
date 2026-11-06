/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4376
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class NativeCaptionPlacementTest {
    private NativeCaptionPlacement.Bounds b(int x,int y,int w,int h) {return new NativeCaptionPlacement.Bounds(x,y,x+w,y+h);}
    @Test public void unrelatedWindowsKeepTheirAuthoredPosition() {
        assertArrayEquals(new int[]{0},NativeCaptionPlacement.fit(List.of(b(0,80,30,20)),List.of(b(50,80,30,20)),-1,-1,0,100,2).offsets());
    }
    @Test public void overlappingEffectLayersMoveTogetherBelowPrimary() {
        var p=NativeCaptionPlacement.fit(List.of(b(0,80,40,20)),List.of(b(0,80,40,20),b(0,80,40,20)),-1,-1,0,140,2);
        assertEquals(0,p.primaryOffsets()[0]);assertArrayEquals(new int[]{22,22},p.offsets());
    }
    @Test public void nearTopMovesBelowAndDoesNotAccumulateOffset() {
        var first=List.of(b(0,0,40,20));var second=List.of(b(0,0,40,20));
        assertArrayEquals(new int[]{22},NativeCaptionPlacement.fit(first,second,-1,-1,0,100,2).offsets());
        assertArrayEquals(new int[]{22},NativeCaptionPlacement.fit(first,second,-1,-1,0,100,2).offsets());
    }
    @Test public void onlyHorizontallyRelatedSecondaryGroupMoves() {
        var p=NativeCaptionPlacement.fit(List.of(b(0,80,40,20)),List.of(b(0,80,40,20),b(60,80,30,20)),-1,-1,0,140,2);
        assertArrayEquals(new int[]{22,0},p.offsets());
    }
    @Test public void transitiveEffectLayersStayTogether() {
        var p=NativeCaptionPlacement.fit(List.of(b(0,80,20,20)),List.of(b(0,80,20,20),b(0,70,20,20),b(0,60,20,20)),-1,-1,0,120,2);
        assertEquals(-22,p.primaryOffsets()[0]);assertArrayEquals(new int[]{20,20,20},p.offsets());
    }
    @Test public void alreadyOrderedWindowsKeepTheirAuthoredPositions() {
        var p=NativeCaptionPlacement.fit(List.of(b(0,10,40,20)),List.of(b(0,70,40,20)),-1,-1,0,100,2);
        assertEquals(0,p.primaryOffsets()[0]);assertArrayEquals(new int[]{0},p.offsets());
    }
    @Test public void cannotFitDoesNotMoveOutsideViewport() {
        var p=NativeCaptionPlacement.fit(List.of(b(0,0,40,30)),List.of(b(0,0,40,30)),-1,-1,0,30,2);
        assertEquals(0,p.primaryOffsets()[0]);assertArrayEquals(new int[]{0},p.offsets());
    }
    @Test public void smallViewportCompactsOnlySecondaryTranslation() {
        var p=NativeCaptionPlacement.fit(List.of(b(0,110,100,190)),List.of(b(0,80,100,220)),-1,0,0,300,5);
        assertEquals(.45f,p.scale(),.001f);assertTrue(p.primaryOffsets()[0]<0);assertTrue(p.offsets()[0]>0);
    }
    @Test public void smallViewportKeepsNativeSecondaryAndMovesPrimaryTranslation() {
        var p=NativeCaptionPlacement.fit(List.of(b(0,80,100,220)),List.of(b(0,110,100,190)),0,-1,0,300,5);
        assertEquals(.45f,p.scale(),.001f);assertTrue(p.primaryOffsets()[0]<0);assertArrayEquals(new int[]{0},p.offsets());
    }
    @Test public void authoredWindowsNeverShrinkAndLargerViewportNeedsNoCompaction() {
        var nativeOnly=NativeCaptionPlacement.fit(List.of(b(0,110,100,190)),List.of(b(0,80,100,220)),-1,-1,0,300,5);
        assertEquals(1f,nativeOnly.scale(),0);
        var expanded=NativeCaptionPlacement.fit(List.of(b(0,510,100,190)),List.of(b(0,480,100,220)),-1,0,0,700,5);
        assertEquals(1f,expanded.scale(),0);
    }
    @Test public void primaryStaysAboveSecondaryAtBottomOfViewport() {
        var p=NativeCaptionPlacement.fit(List.of(b(0,80,40,20)),List.of(b(0,80,40,20)),-1,-1,0,100,2);
        assertEquals(-22,p.primaryOffsets()[0]);assertArrayEquals(new int[]{0},p.offsets());
    }
    @Test public void alreadyReversedNativeWindowsArePutInLanguageOrder() {
        var p=NativeCaptionPlacement.fit(List.of(b(0,60,40,20)),List.of(b(0,10,40,20)),-1,-1,0,100,2);
        assertEquals(-2,p.primaryOffsets()[0]);assertArrayEquals(new int[]{70},p.offsets());
    }
    @Test public void secondaryTranslationCompactsBelowTheNativePrimary() {
        var p=NativeCaptionPlacement.fit(List.of(b(0,110,100,190)),List.of(b(0,80,100,220)),-1,0,0,300,5);
        assertTrue(110+p.primaryOffsets()[0]+190+5 <= 190-Math.round(220*p.scale())/2+p.offsets()[0]+1);
    }
    @Test public void unrelatedPrimaryNearTopDoesNotBlockTheBottomPair() {
        var p=NativeCaptionPlacement.fit(List.of(b(0,80,40,20),b(60,0,30,20)),List.of(b(0,80,40,20)),-1,-1,0,100,2);
        assertArrayEquals(new int[]{-22,0},p.primaryOffsets());assertArrayEquals(new int[]{0},p.offsets());
    }
    @Test public void separatePrimaryAboveThePairKeepsItsAuthoredPosition() {
        var p=NativeCaptionPlacement.fit(List.of(b(0,80,40,20),b(0,0,40,20)),List.of(b(0,80,40,20)),-1,-1,0,100,2);
        assertArrayEquals(new int[]{-22,0},p.primaryOffsets());assertArrayEquals(new int[]{0},p.offsets());
    }
    @Test public void movingPrimaryDoesNotOverlapAnotherPrimaryWindow() {
        var p=NativeCaptionPlacement.fit(List.of(b(0,80,40,20),b(0,50,40,20)),List.of(b(0,80,40,20)),-1,-1,0,100,2);
        assertTrue(70+p.primaryOffsets()[1] <= 80+p.primaryOffsets()[0]);
        assertTrue(100+p.primaryOffsets()[0]+2 <= 80+p.offsets()[0]);
    }
    @Test public void secondaryObstaclesDoNotForceAnUnnecessaryFallback() {
        var p=NativeCaptionPlacement.fit(List.of(b(0,80,40,20)),List.of(b(0,80,40,20),b(0,110,40,20)),-1,-1,0,140,2);
        assertTrue(100+p.primaryOffsets()[0]+2 <= 80+p.offsets()[0]);
        assertTrue(100+p.offsets()[0] <= 110+p.offsets()[1]);
        assertTrue(130+p.offsets()[1] <= 140);
    }
}
