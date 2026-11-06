/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class CreditInfoTest {

    @Test
    public void recordAccessors() {
        CreditInfo info = new CreditInfo("Google", "AI:gpt-4");
        assertEquals("Google", info.translator());
        assertEquals("AI:gpt-4", info.romanizer());
    }

    @Test
    public void recordAllowsNulls() {
        CreditInfo info = new CreditInfo(null, null);
        assertNull(info.translator());
        assertNull(info.romanizer());
    }

    @Test
    public void recordEquality() {
        assertEquals(new CreditInfo("Google", null), new CreditInfo("Google", null));
        assertNotEquals(new CreditInfo("Google", null), new CreditInfo(null, "Google"));
    }
}
