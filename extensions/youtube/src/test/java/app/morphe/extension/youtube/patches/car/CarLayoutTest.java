/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://codeberg.org/JohnInNL/nl-morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.car;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises the form factor decisions of the automotive layout on and off a car display. */
public class CarLayoutTest {
    private static final int UNKNOWN = 0;
    private static final int SMALL = 1;
    private static final int LARGE = 2;
    private static final int AUTOMOTIVE = 3;

    @Test
    public void universalFormFactor_usesAutomotiveOnlyOnACarDisplay() {
        assertEquals(AUTOMOTIVE, CarLayout.universalFormFactor(SMALL, true));
        assertEquals(AUTOMOTIVE, CarLayout.universalFormFactor(LARGE, true));
        assertEquals(AUTOMOTIVE, CarLayout.universalFormFactor(AUTOMOTIVE, true));
        // The phone keeps the original form factor of every request.
        assertEquals(SMALL, CarLayout.universalFormFactor(SMALL, false));
        assertEquals(LARGE, CarLayout.universalFormFactor(LARGE, false));
        assertEquals(UNKNOWN, CarLayout.universalFormFactor(UNKNOWN, false));
    }

    @Test
    public void brokenEndpointFormFactor_usesTheTabletFormFactorOnACarDisplay() {
        assertEquals(LARGE, CarLayout.brokenEndpointFormFactor(AUTOMOTIVE, true));
        // Endpoints that already use another form factor keep it.
        assertEquals(SMALL, CarLayout.brokenEndpointFormFactor(SMALL, true));
        assertEquals(LARGE, CarLayout.brokenEndpointFormFactor(LARGE, true));
        // Off the car display nothing changes.
        assertEquals(AUTOMOTIVE, CarLayout.brokenEndpointFormFactor(AUTOMOTIVE, false));
    }

    @Test
    public void browseFormFactor_keepsAutomotiveOnlyForThePagesThatWork() {
        assertEquals(AUTOMOTIVE, CarLayout.browseFormFactor(AUTOMOTIVE, "FEwhat_to_watch", true));
        assertEquals(AUTOMOTIVE, CarLayout.browseFormFactor(AUTOMOTIVE, "FEsubscriptions", true));
        // Channel and other browse pages use the tablet form factor.
        assertEquals(LARGE, CarLayout.browseFormFactor(AUTOMOTIVE, "UCchannel", true));
        // A request without a page id cannot be told apart, so it keeps the automotive layout.
        assertEquals(AUTOMOTIVE, CarLayout.browseFormFactor(AUTOMOTIVE, null, true));
        // Off the car display, and for other form factors, nothing changes.
        assertEquals(AUTOMOTIVE, CarLayout.browseFormFactor(AUTOMOTIVE, "FEwhat_to_watch", false));
        assertEquals(SMALL, CarLayout.browseFormFactor(SMALL, "UCchannel", true));
    }

    @Test
    public void checkItemSectionRenderer_skipsListsThatHaveNoSuchIndex() {
        assertTrue(CarLayout.checkItemSectionRenderer(java.util.Collections.singletonList("a"), 0));
        assertFalse(CarLayout.checkItemSectionRenderer(java.util.Collections.singletonList("a"), 1));
        assertFalse(CarLayout.checkItemSectionRenderer(java.util.Collections.emptyList(), 0));
        assertFalse(CarLayout.checkItemSectionRenderer(null, 0));
        assertFalse(CarLayout.checkItemSectionRenderer(java.util.Collections.singletonList("a"), -1));
    }

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
