/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5134
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.holdgesture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RadialLayoutTest {
    /** How far the finger is above the point of the press when the launcher opens. */
    private static final float ENTER = 31;

    /** Sizes of the player in dp: phones upright, a small phone, fullscreen, a tall video in fullscreen. */
    private static final float[][] AREAS = {
            {360, 202}, {411, 231}, {320, 180}, {915, 411}, {411, 915}, {700, 394},
    };

    private static RadialLayout open(float pressX, float pressY, float width, float height, int count) {
        return RadialLayout.create(pressX, pressY, pressX, pressY - ENTER, 0, 0, width, height, count);
    }

    @Test
    public void opensUpwardAroundThePressWhereThereIsRoom() {
        RadialLayout layout = open(450, 300, 915, 411, 4);
        assertEquals(450, layout.centerX, 0);
        assertEquals(300, layout.centerY, 0);
        assertEquals(1, layout.scale, 0);
        for (int i = 0; i < 4; i++) {
            assertEquals("far enough out to be seen past the finger", RadialLayout.RADIUS_FAR,
                    Math.hypot(layout.itemX[i] - 450, layout.itemY[i] - 300), 0.01);
            assertTrue("above the press", layout.itemY[i] < 300);
        }
        assertEquals("the same on both sides", 450 - layout.itemX[0], layout.itemX[3] - 450, 0.01);
        assertEquals(layout.itemY[0], layout.itemY[3], 0.01);
    }

    @Test
    public void anOddNumberOfItemsHasOneStraightUp() {
        for (int count : new int[]{1, 3, 5}) {
            RadialLayout layout = open(450, 300, 915, 411, count);
            assertEquals(450, layout.itemX[count / 2], 0.01);
            assertEquals(300 - RadialLayout.RADIUS_FAR, layout.itemY[count / 2], 0.01);
        }
    }

    @Test
    public void everyItemIsInsideTheAreaWhereverThePressIs() {
        for (float[] area : AREAS) {
            for (int count = 1; count <= RadialLayout.MAX_ITEMS; count++) {
                for (float x = 0; x <= area[0]; x += area[0] / 24) {
                    for (float y = 0; y <= area[1]; y += area[1] / 24) {
                        RadialLayout layout = open(x, y, area[0], area[1], count);
                        final String where = count + " items, press at " + x + ", " + y
                                + " in " + area[0] + " x " + area[1];
                        final float room = RadialLayout.ITEM_RADIUS_SELECTED * layout.scale;
                        for (int i = 0; i < count; i++) {
                            assertTrue(where, layout.itemX[i] >= room && layout.itemX[i] <= area[0] - room);
                            assertTrue(where, layout.itemY[i] >= room && layout.itemY[i] <= area[1] - room);
                            if (i > 0) {
                                final double apart = Math.hypot(layout.itemX[i] - layout.itemX[i - 1],
                                        layout.itemY[i] - layout.itemY[i - 1]);
                                assertTrue(where + ": items " + (i - 1) + " and " + i + " are " + apart + " apart",
                                        apart >= 2 * RadialLayout.ITEM_RADIUS * layout.scale);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void theFirstItemIsOnTheLeft() {
        for (float[] area : AREAS) {
            for (float y = 0; y <= area[1]; y += area[1] / 12) {
                RadialLayout layout = open(area[0] / 2, y, area[0], area[1], 4);
                assertTrue("press at " + y + " of " + area[1], layout.itemX[0] < layout.itemX[3]);
            }
        }
    }

    @Test
    public void keepsToTheAreaItIsGiven() {
        // The left 40 dp of a fullscreen player are under a camera cutout.
        RadialLayout layout = RadialLayout.create(60, 300, 60, 300 - ENTER, 40, 0, 915, 411, 5);
        for (int i = 0; i < 5; i++) {
            assertTrue(layout.itemX[i] >= 40 + RadialLayout.ITEM_RADIUS_SELECTED);
        }
        assertTrue(layout.centerX >= 40 + RadialLayout.CENTER_EDGE_MARGIN);
    }

    @Test
    public void opensDownwardAroundTheFingerNearTheTop() {
        RadialLayout layout = open(200, 60, 411, 231, 4);
        assertEquals(200, layout.centerX, 0);
        assertEquals("the finger is in the middle, pointing at nothing", 60 - ENTER, layout.centerY, 0);
        for (int i = 0; i < 4; i++) {
            assertTrue(layout.itemY[i] > layout.centerY);
        }
        assertEquals(-1, layout.select(200, 60 - ENTER, -1));
    }

    @Test
    public void leansAwayFromTheSideOfTheArea() {
        RadialLayout left = open(30, 300, 915, 411, 4);
        RadialLayout right = open(885, 300, 915, 411, 4);
        float leftMiddle = 0;
        float rightMiddle = 0;
        for (int i = 0; i < 4; i++) {
            leftMiddle += left.itemX[i] / 4;
            rightMiddle += right.itemX[i] / 4;
        }
        assertTrue(leftMiddle > left.centerX + 20);
        assertTrue(rightMiddle < right.centerX - 20);
        assertEquals("mirrored", leftMiddle - left.centerX, right.centerX - rightMiddle, 0.01);
    }

    @Test
    public void theFanIsCloserWhereThereIsLessRoomAbove() {
        // A player of a phone held upright. The press comes closer to its top.
        double before = RadialLayout.RADIUS_FAR;
        for (float y = 200; y >= 100; y -= 10) {
            RadialLayout layout = open(205, y, 411, 231, 4);
            final double radius = Math.hypot(layout.itemX[0] - layout.centerX, layout.itemY[0] - layout.centerY);
            assertEquals("still around the press", y, layout.centerY, 0);
            assertTrue("press at " + y + ": " + radius, radius <= before + 0.01 && radius >= RadialLayout.RADIUS - 0.01);
            for (int i = 0; i < 4; i++) {
                assertTrue("upward", layout.itemY[i] < y);
            }
            before = radius;
        }
        assertEquals("closest at the last place where it still opens upward", RadialLayout.RADIUS, before, 0.01);
    }

    @Test
    public void aLowPlayerGetsSmallerItemsAndASmallerFan() {
        RadialLayout layout = open(160, 150, 320, 180, 4);
        assertEquals(180 * RadialLayout.RADIUS_OF_HEIGHT / RadialLayout.RADIUS, layout.scale, 0.001);
        assertEquals(RadialLayout.RADIUS_FAR * layout.scale,
                Math.hypot(layout.itemX[0] - layout.centerX, layout.itemY[0] - layout.centerY), 0.01);
    }

    @Test
    public void pointsAtNothingNearTheCenter() {
        RadialLayout layout = open(450, 300, 915, 411, 4);
        assertEquals(-1, layout.select(450, 300, -1));
        assertEquals(-1, layout.select(450 - 20, 300 - 20, -1));
        assertEquals("where the drag up opened the launcher", -1, layout.select(450, 300 - ENTER, -1));
        // Entering takes more than leaving, so that a finger at the border does not flicker.
        final float between = (RadialLayout.SELECT_ENTER + RadialLayout.SELECT_LEAVE) / 2;
        assertEquals(-1, layout.select(450 - between, 300, -1));
        assertEquals(0, layout.select(450 - between, 300, 0));
        assertEquals(-1, layout.select(450 - RadialLayout.SELECT_LEAVE + 1, 300, 0));
    }

    @Test
    public void opensWithNothingPickedWhereTheCenterIsNotUnderThePress() {
        // The left 40 dp of a fullscreen player are under a camera cutout, and the press is right next to it.
        final float fingerY = 300 - ENTER - 8;
        RadialLayout layout = RadialLayout.create(42, 300, 42, fingerY, 40, 0, 915, 411, 4);
        assertTrue("the center is moved inward", layout.centerX > 42 + 20);
        assertTrue("the finger is already as far from the center as it takes to point",
                Math.hypot(42 - layout.centerX, fingerY - layout.centerY) > RadialLayout.SELECT_ENTER);

        assertEquals(-1, layout.select(42, fingerY, -1));
        assertEquals("a finger never rests fully", -1, layout.select(44, fingerY - 3, -1));
        assertTrue("moving on points at an item",
                layout.select(42, fingerY - RadialLayout.SELECT_MOVE, -1) >= 0);
    }

    @Test
    public void pointsAtTheItemInTheDirectionOfTheFinger() {
        for (int count = 1; count <= RadialLayout.MAX_ITEMS; count++) {
            RadialLayout layout = open(450, 300, 915, 411, count);
            for (int i = 0; i < count; i++) {
                final float dx = layout.itemX[i] - layout.centerX;
                final float dy = layout.itemY[i] - layout.centerY;
                assertEquals("on the item", i, layout.select(layout.itemX[i], layout.itemY[i], -1));
                assertEquals("most of the way", i, layout.select(450 + dx * 0.7f, 300 + dy * 0.7f, -1));
                assertEquals("far past it", i, layout.select(450 + dx * 2.5f, 300 + dy * 2.5f, -1));
            }
        }
    }

    @Test
    public void pointsAtNothingAwayFromTheFan() {
        RadialLayout layout = open(450, 300, 915, 411, 4);
        assertEquals("straight down", -1, layout.select(450, 300 + 80, -1));
        assertEquals("a little below the outer item still means it", 0, layout.select(450 - 80, 300 - 5, -1));
    }

    @Test
    public void staysWithAnItemOnTheLineToItsNeighbor() {
        RadialLayout layout = open(450, 300, 915, 411, 4);
        // Straight up is the line between the two middle items.
        assertEquals(1, layout.select(450 - 1, 300 - 60, -1));
        assertEquals(1, layout.select(450 + 3, 300 - 60, 1));
        assertEquals(2, layout.select(450 + 3, 300 - 60, 2));
        assertEquals(2, layout.select(450 + 20, 300 - 60, 1));
    }
}
