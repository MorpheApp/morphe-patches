/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.holdgesture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpringTest {
    private static final float FRAME = 1 / 60f;

    /** Runs frames until the spring rests, and returns the highest value on the way. */
    private static float settle(Spring spring) {
        float highest = spring.value();
        for (int frame = 0; frame < 600 && spring.advance(FRAME); frame++) {
            highest = Math.max(highest, spring.value());
        }
        return highest;
    }

    @Test
    public void aSpringAtItsTargetDoesNotMove() {
        Spring spring = new Spring(300, 1);
        spring.snapTo(0.5f);
        assertFalse(spring.advance(FRAME));
        assertEquals(0.5f, spring.value(), 0);
    }

    @Test
    public void reachesTheTargetAndRests() {
        Spring spring = new Spring(300, 1);
        spring.setTarget(1);
        assertTrue(spring.advance(FRAME));
        settle(spring);
        assertEquals(1, spring.value(), 0);
        assertTrue(spring.atRest());
    }

    @Test
    public void fullDampingDoesNotOvershoot() {
        Spring spring = new Spring(300, 1);
        spring.setTarget(1);
        assertTrue(settle(spring) <= 1);
    }

    @Test
    public void lowDampingOvershoots() {
        Spring spring = new Spring(380, 0.7f);
        spring.setTarget(1);
        float highest = settle(spring);
        assertTrue("highest " + highest, highest > 1.01f && highest < 1.2f);
        assertEquals(1, spring.value(), 0);
    }

    @Test
    public void aKickComesBackToTheTarget() {
        Spring spring = new Spring(600, 0.45f);
        spring.kick(6);
        float highest = settle(spring);
        assertTrue("highest " + highest, highest > 0.1f && highest < 0.25f);
        assertEquals(0, spring.value(), 0);
    }

    @Test
    public void aLongFrameDoesNotBlowUp() {
        Spring spring = new Spring(600, 0.45f);
        spring.setTarget(1);
        spring.advance(5);
        assertTrue(spring.value() > 0 && spring.value() < 2);
    }

    @Test
    public void aNewTargetKeepsTheMotion() {
        Spring spring = new Spring(300, 1);
        spring.setTarget(1);
        for (int frame = 0; frame < 5; frame++) {
            spring.advance(FRAME);
        }
        float before = spring.value();
        spring.setTarget(0);
        spring.advance(FRAME);
        assertTrue("still moving up right after turning around", spring.value() > before);
    }
}
