/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;

public class LyricsFitTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void extractWeightVariationNullReturnsNull() throws Exception {
        assertNull(callExtractWeightVariation(null));
    }

    @Test
    public void extractWeightVariationWithSpace() throws Exception {
        assertEquals("'wght' 700", callExtractWeightVariation("'wght' 700"));
    }

    @Test
    public void extractWeightVariationWithoutSpace() throws Exception {
        assertEquals("'wght'700", callExtractWeightVariation("'wght'700"));
    }

    @Test
    public void extractWeightVariationDecimal() throws Exception {
        assertEquals("'wght' 400.5", callExtractWeightVariation("'wght' 400.5"));
    }

    @Test
    public void extractWeightVariationNoWghtReturnsNull() throws Exception {
        assertNull(callExtractWeightVariation("'wdth' 85"));
    }

    @Test
    public void extractWeightVariationEmptyString() throws Exception {
        assertNull(callExtractWeightVariation(""));
    }

    @Test
    public void extractWeightVariationWghtNoValue() throws Exception {
        assertNull(callExtractWeightVariation("'wght' "));
    }

    @Test
    public void extractWeightVariationMultipleAxes() throws Exception {
        assertEquals("'wght' 700", callExtractWeightVariation("'wdth' 85, 'wght' 700"));
    }

    @Test
    public void extractWeightVariationOnlyWght() throws Exception {
        assertEquals("'wght' 400", callExtractWeightVariation("'wght' 400"));
    }

    @Test
    public void combineAxesBothNullReturnsNull() throws Exception {
        assertNull(callCombineAxes(null, null));
    }

    @Test
    public void combineAxesWeightNullReturnsFit() throws Exception {
        assertEquals("fit", callCombineAxes(null, "fit"));
    }

    @Test
    public void combineAxesFitNullReturnsWeight() throws Exception {
        assertEquals("weight", callCombineAxes("weight", null));
    }

    @Test
    public void combineAxesBothPresent() throws Exception {
        assertEquals("fit, weight", callCombineAxes("weight", "fit"));
    }

    private static String callExtractWeightVariation(String settings) throws Exception {
        Method method = LyricsFit.class.getDeclaredMethod("extractWeightVariation", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, settings);
    }

    private static String callCombineAxes(String weight, String fit) throws Exception {
        Method method = LyricsFit.class.getDeclaredMethod("combineAxes", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, weight, fit);
    }
}
