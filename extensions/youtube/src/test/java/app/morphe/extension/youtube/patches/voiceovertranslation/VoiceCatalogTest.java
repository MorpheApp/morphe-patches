/*
 * Copyright 2026 Morphe contributors.
 * See the project NOTICE for GPLv3 Section 7 terms.
 */
package app.morphe.extension.youtube.patches.voiceovertranslation;

import org.junit.After;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class VoiceCatalogTest {
    @After
    public void restoreBundledCatalog() {
        VoiceCatalog.useBundledVoices();
    }

    @Test
    public void dynamicCatalogPreservesNativeLanguageAndMultilingualFiltering() {
        VoiceCatalog.setAvailableVoices(Arrays.asList(
                new VoiceCatalog.Voice(true, "tr-TR-AhmetNeural"),
                new VoiceCatalog.Voice(false, "de-DE-KatjaNeural"),
                new VoiceCatalog.Voice(false, "en-US-EmmaMultilingualNeural")));

        List<VoiceCatalog.Voice> turkish = VoiceCatalog.getVoicesForLang("tr-TR");
        List<VoiceCatalog.Voice> german = VoiceCatalog.getVoicesForLang("de-DE");
        assertNotNull(turkish);
        assertNotNull(german);
        assertEquals(2, turkish.size());
        assertEquals(2, german.size());
        assertEquals("tr-TR-AhmetNeural", turkish.get(0).id);
        assertEquals("de-DE-KatjaNeural", german.get(0).id);
        assertEquals("en-US-EmmaMultilingualNeural", turkish.get(1).id);
        assertEquals("en-US-EmmaMultilingualNeural", german.get(1).id);
    }

    @Test
    public void savedVoiceSelectionAndFallbackRemainStable() {
        VoiceCatalog.setAvailableVoices(Arrays.asList(
                new VoiceCatalog.Voice(true, "tr-TR-AhmetNeural"),
                new VoiceCatalog.Voice(false, "de-DE-KatjaNeural"),
                new VoiceCatalog.Voice(false, "en-US-EmmaMultilingualNeural")));
        assertEquals("tr-TR-AhmetNeural", VoiceCatalog.resolve("tr-TR", "tr-TR-AhmetNeural"));
        assertEquals("tr-TR-AhmetNeural", VoiceCatalog.resolve("tr-TR", "de-DE-KatjaNeural"));
        assertEquals("en-US-EmmaMultilingualNeural", VoiceCatalog.resolve("fr-FR", null));
        assertThrows(UnsupportedOperationException.class,
                () -> VoiceCatalog.getVoicesForLang("tr").clear());
    }

    @Test
    public void catalogDiffIgnoresOrderingAndTracksAdditionsAndRemovals() {
        VoiceCatalog.Voice tr = new VoiceCatalog.Voice(true, "tr-TR-AhmetNeural");
        VoiceCatalog.Voice de = new VoiceCatalog.Voice(false, "de-DE-KatjaNeural");
        VoiceCatalog.Voice newVoice = new VoiceCatalog.Voice(false, "tr-TR-NewNeural");

        VoiceCatalog.ChangeSummary same = VoiceCatalog.compare(
                Arrays.asList(tr, de), Arrays.asList(de, tr));
        assertEquals(0, same.added);
        assertEquals(0, same.removed);

        VoiceCatalog.ChangeSummary changed = VoiceCatalog.compare(
                Arrays.asList(tr, de), Arrays.asList(tr, newVoice));
        assertEquals(1, changed.added);
        assertEquals(1, changed.removed);
    }

    @Test
    public void bundledVoicesRemainAvailableOffline() {
        VoiceCatalog.useBundledVoices();
        assertNotNull(VoiceCatalog.getVoice("tr-TR-AhmetNeural"));
        assertNotNull(VoiceCatalog.getVoice("tr-TR-EmelNeural"));
        assertNotNull(VoiceCatalog.resolve("de-DE", null));
    }
}
