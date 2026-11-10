/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4576
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.ambientlight;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class AmbientLightMathTest {
    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;
    private static final int DARK_PAGE = 0xFF0F0F0F;
    private static final int LIGHT_PAGE = 0xFFFFFFFF;

    private static int[] filled(int color, int length) {
        int[] pixels = new int[length];
        Arrays.fill(pixels, color);
        return pixels;
    }

    @Test
    public void downsampleAveragesEveryPixelOfACell() {
        int[] source = {0xFF000000, 0xFF646464, 0xFF000000, 0xFF646464};
        int[] target = new int[1];
        AmbientLightMath.downsample(source, 2, new int[]{0, 0, 2, 2}, target, 1, 1);
        assertEquals(0xFF323232, target[0]);
    }

    @Test
    public void downsampleUsesOnlyTheRegion() {
        int[] source = {0xFF000000, RED, RED, 0xFF000000};
        int[] target = new int[1];
        AmbientLightMath.downsample(source, 4, new int[]{1, 0, 3, 1}, target, 1, 1);
        assertEquals(RED, target[0]);
    }

    private static int[] pictureWithBars(int width, int height, int barX, int barY) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean bar = x < barX || x >= width - barX || y < barY || y >= height - barY;
                pixels[y * width + x] = bar ? 0xFF000000 : RED;
            }
        }
        return pixels;
    }

    @Test
    public void findsBlackBarsOnBothSides() {
        assertArrayEquals(new int[]{10, 0, 86, 54},
                AmbientLightMath.pictureBounds(pictureWithBars(96, 54, 10, 0), 96, 54));
        assertArrayEquals(new int[]{0, 7, 96, 47},
                AmbientLightMath.pictureBounds(pictureWithBars(96, 54, 0, 7), 96, 54));
    }

    @Test
    public void fullPictureAndAllDarkFrames() {
        assertArrayEquals(new int[]{0, 0, 96, 54},
                AmbientLightMath.pictureBounds(filled(RED, 96 * 54), 96, 54));
        assertNull(AmbientLightMath.pictureBounds(filled(0xFF000000, 96 * 54), 96, 54));
    }

    @Test
    public void aBarWithSubtitlesLeavesBothBarsAlone() {
        int[] pixels = pictureWithBars(96, 54, 0, 7);
        // A subtitle in the bottom bar: enough bright pixels on every line of that bar.
        for (int y = 47; y < 54; y++) {
            for (int x = 20; x < 76; x++) pixels[y * 96 + x] = 0xFFFFFFFF;
        }
        assertArrayEquals(new int[]{0, 0, 96, 54}, AmbientLightMath.withoutOneSidedBars(
                AmbientLightMath.pictureBounds(pixels, 96, 54), 96, 54));
    }

    @Test
    public void differenceIsThePerChannelMean() {
        assertEquals(0, AmbientLightMath.difference(filled(RED, 4), filled(RED, 4)));
        assertEquals(170, AmbientLightMath.difference(filled(RED, 4), filled(BLUE, 4)));
    }

    @Test
    public void blendWithoutKeepJumpsToTheTarget() {
        int[] current = filled(RED, 2);
        AmbientLightMath.blend(current, filled(BLUE, 2), 0);
        assertArrayEquals(filled(BLUE, 2), current);
    }

    @Test
    public void blendConvergesOnTheTarget() {
        int[] current = filled(RED, 1);
        int[] target = filled(BLUE, 1);
        for (int i = 0; i < 30; i++) AmbientLightMath.blend(current, target, 112);
        assertTrue(AmbientLightMath.difference(current, target) <= 1);
    }

    private static float[] linear(int... pixels) {
        float[] result = new float[pixels.length * 3];
        AmbientLightMath.toLinear(pixels, result);
        return result;
    }

    @Test
    public void lightNextToThePictureContinuesItsNearestEdge() {
        // Left half red, right half blue. The window is a picture with only a thin row below it.
        float[] near = linear(RED, BLUE, RED, BLUE);
        float[] far = linear(0xFF808080, 0xFF808080, 0xFF808080, 0xFF808080);
        float[] out = new float[6];
        AmbientLightMath.render(near, 2, 2, far, 2, 2, new int[]{0, -1000, 100, 100}, 100, 101, out, 2, 1);

        assertTrue(out[0] > out[3]);
        assertTrue(out[5] > out[2]);
    }

    @Test
    public void lightOnePictureHeightAwayIsOnlyTheSoftLight() {
        float[] near = linear(RED, RED, RED, RED);
        float[] far = linear(0xFF646464, 0xFF646464, 0xFF646464, 0xFF646464);
        float[] out = new float[3];
        AmbientLightMath.render(near, 2, 2, far, 2, 2, new int[]{0, 0, 100, 100}, 100, 400, out, 1, 1);

        float expected = (0x64 / 255f) * (0x64 / 255f);
        assertEquals(expected, out[0], 0.001f);
        assertEquals(expected, out[1], 0.001f);
        assertEquals(expected, out[2], 0.001f);
    }

    private static void soften(float[] light, int width, int height, float[] picture) {
        AmbientLightMath.soften(light, new float[light.length], new float[light.length],
                width, height, picture, new float[]{0f, 0f, 0f});
    }

    @Test
    public void softenKeepsAFlatLightFlatAboveThePicturesBottomEdge() {
        float[] light = new float[3 * 4 * 8];
        Arrays.fill(light, 0.25f);
        soften(light, 4, 8, new float[]{0f, 0f, 4f, 8f});

        for (float value : light) assertEquals(0.25f, value, 0.001f);
    }

    @Test
    public void softenFallsOffTowardsThePageColorAtTheBottom() {
        float[] light = new float[3 * 4 * 8];
        Arrays.fill(light, 0.25f);
        soften(light, 4, 8, new float[]{0f, 0f, 4f, 0f});

        assertTrue(light[light.length - 1] < light[0] * 0.9f);
    }

    @Test
    public void lightKeepsTheShapesOfThePictureNextToIt() {
        // A hard edge between two colors, right below the picture and far below it.
        int width = 64;
        int height = 160;
        float[] light = new float[3 * width * height];
        for (int i = 0; i < width * height; i++) {
            light[i * 3] = i % width < width / 2 ? 1f : 0f;
        }
        soften(light, width, height, new float[]{0f, 0f, 64f, 10f});

        float nearContrast = light[(12 * width + 26) * 3] - light[(12 * width + 38) * 3];
        float farContrast = light[(150 * width + 26) * 3] - light[(150 * width + 38) * 3];
        assertTrue(nearContrast > farContrast * 1.5f);
    }

    @Test
    public void pageColorsBecomeTransparent() {
        assertEquals(0, AmbientLightMath.seeThroughColor(DARK_PAGE, DARK_PAGE));
        assertEquals(0, AmbientLightMath.seeThroughColor(0xFF000000, DARK_PAGE));
        assertEquals(0, AmbientLightMath.seeThroughColor(LIGHT_PAGE, LIGHT_PAGE));
    }

    @Test
    public void raisedSurfacesBecomeATranslucentVeil() {
        int veil = AmbientLightMath.seeThroughColor(0xFF272727, DARK_PAGE);
        assertEquals(0xFFFFFF, veil & 0xFFFFFF);
        assertTrue(veil >>> 24 > 0 && veil >>> 24 < 0xFF);
    }

    private static float[] flatLight(int width, int height, float gammaBrightness) {
        float[] light = new float[3 * width * height];
        Arrays.fill(light, gammaBrightness * gammaBrightness);
        return light;
    }

    @Test
    public void darkLightNeedsNoCoverOnADarkPage() {
        assertEquals(0f, AmbientLightMath.readabilityCover(flatLight(10, 10, 0.3f), 10, 10, 0, true), 0f);
    }

    @Test
    public void brightLightIsCoveredDownToAReadableBrightnessOnADarkPage() {
        float cover = AmbientLightMath.readabilityCover(flatLight(10, 10, 1f), 10, 10, 0, true);
        assertEquals(0.5f, 1f * (1f - cover), 0.02f);
    }

    @Test
    public void aFewBrightSpotsDoNotCoverThePage() {
        float[] light = flatLight(10, 10, 0.3f);
        Arrays.fill(light, 0, 3 * 5, 1f);
        assertEquals(0f, AmbientLightMath.readabilityCover(light, 10, 10, 0, true), 0f);
    }

    @Test
    public void darkLightIsCoveredOnALightPage() {
        float cover = AmbientLightMath.readabilityCover(flatLight(10, 10, 0.1f), 10, 10, 0, false);
        assertTrue(cover > 0f);
        assertEquals(0f, AmbientLightMath.readabilityCover(flatLight(10, 10, 0.9f), 10, 10, 0, false), 0f);
    }

    @Test
    public void onlyTheRowsBelowThePictureCount() {
        float[] light = flatLight(10, 10, 0.3f);
        Arrays.fill(light, 0, 3 * 10 * 5, 1f);
        assertEquals(0f, AmbientLightMath.readabilityCover(light, 10, 10, 5, true), 0f);
    }

    @Test
    public void halfFloatEncoding() {
        assertEquals((short) 0x3C00, AmbientLightMath.encodeHalf(1f));
        assertEquals((short) 0x3800, AmbientLightMath.encodeHalf(0.5f));
        assertEquals((short) 0, AmbientLightMath.encodeHalf(0f));
    }

    @Test
    public void encodingIsTheSquareRootAndReportsTheLargestChange() {
        float[] light = {0.25f, 4f, 0f};
        float[] encoded = new float[3];
        float[] presented = {0.5f, 0.75f, 0f};
        float change = AmbientLightMath.encodeVisible(light, encoded, presented, 1, 1, new float[]{5f, 5f, 6f, 6f});

        assertEquals(0.5f, encoded[0], 0f);
        assertEquals(1f, encoded[1], 0f);
        assertEquals(0.25f, change, 0.0001f);
    }

    @Test
    public void hiddenCellsDoNotCountAsAChange() {
        float[] light = new float[3 * 10 * 10];
        Arrays.fill(light, 1f);
        float[] presented = new float[light.length];
        Arrays.fill(presented, 1f);
        // Only the middle cell is hidden behind this picture.
        presented[(5 * 10 + 5) * 3] = 0f;
        float change = AmbientLightMath.encodeVisible(light, new float[light.length], presented, 10, 10,
                new float[]{2f, 2f, 8f, 8f});

        assertEquals(0f, change, 0f);
    }

    @Test
    public void otherColorsAreKept() {
        assertEquals(RED, AmbientLightMath.seeThroughColor(RED, DARK_PAGE));
        assertEquals(LIGHT_PAGE, AmbientLightMath.seeThroughColor(LIGHT_PAGE, DARK_PAGE));
        assertEquals(0x80000000, AmbientLightMath.seeThroughColor(0x80000000, DARK_PAGE));
    }
}
