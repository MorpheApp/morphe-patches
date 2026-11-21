/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4933
 *
 * Adapted from
 * https://codeberg.org/JohnInNL/nl-morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.car;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CarDisplayTest {

    @Test
    public void isCarHostName_matchesTheNamesCarHostsGive() {
        assertTrue(CarDisplay.isCarHostName("drivedeck-guest-com.google.android.youtube"));
        assertTrue(CarDisplay.isCarHostName("DriveDeck"));
        assertTrue(CarDisplay.isCarHostName("Gearslip Guest"));
        assertTrue(CarDisplay.isCarHostName("Android Auto"));
        assertFalse(CarDisplay.isCarHostName("Living room TV"));
        assertFalse(CarDisplay.isCarHostName(""));
        assertFalse(CarDisplay.isCarHostName(null));
    }
}
