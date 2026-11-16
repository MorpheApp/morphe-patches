/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class ProcessingProvidersTest {

    private static final List<String> KNOWN_TRANSLATION =
            List.of(ProcessingProviders.EMBEDDED, ProcessingProviders.GOOGLE,
                    ProcessingProviders.OPENAI_COMPATIBLE, ProcessingProviders.DEEPL);

    private static final List<String> KNOWN_ROMANIZATION =
            List.of(ProcessingProviders.EMBEDDED, ProcessingProviders.GOOGLE,
                    ProcessingProviders.LOCAL, ProcessingProviders.RIME,
                    ProcessingProviders.ICU, ProcessingProviders.OPENAI_COMPATIBLE);

    // ── parseOrder ──

    @Test
    public void parseOrderNullUsesDefaultsAllEnabled() {
        List<ProcessingProviders.ProviderEntry> entries =
                ProcessingProviders.parseOrder(null, KNOWN_TRANSLATION);
        assertEquals(KNOWN_TRANSLATION.size(), entries.size());
        for (ProcessingProviders.ProviderEntry entry : entries) {
            assertTrue(entry.enabled());
        }
    }

    @Test
    public void parseOrderEmptyUsesDefaults() {
        List<ProcessingProviders.ProviderEntry> entries =
                ProcessingProviders.parseOrder("", KNOWN_TRANSLATION);
        assertEquals(KNOWN_TRANSLATION.size(), entries.size());
    }

    @Test
    public void parseOrderDisabledPrefix() {
        List<ProcessingProviders.ProviderEntry> entries =
                ProcessingProviders.parseOrder("embedded,-google", KNOWN_TRANSLATION);
        assertEquals(KNOWN_TRANSLATION.size(), entries.size());
        // google should be disabled
        for (ProcessingProviders.ProviderEntry entry : entries) {
            if (entry.id().equals("google")) {
                assertFalse(entry.enabled());
            }
        }
    }

    @Test
    public void parseOrderEmbeddedStaysFirst() {
        List<ProcessingProviders.ProviderEntry> entries =
                ProcessingProviders.parseOrder("google,deepl", KNOWN_TRANSLATION);
        assertEquals(ProcessingProviders.EMBEDDED, entries.get(0).id());
        assertTrue(entries.get(0).enabled());
    }

    @Test
    public void parseOrderDeduplicates() {
        List<ProcessingProviders.ProviderEntry> entries =
                ProcessingProviders.parseOrder("embedded,embedded", KNOWN_TRANSLATION);
        // embedded appears once in the stored list, then the rest are appended.
        long embeddedCount = entries.stream()
                .filter(e -> e.id().equals(ProcessingProviders.EMBEDDED)).count();
        assertEquals(1, embeddedCount);
    }

    @Test
    public void parseOrderUnknownTokenIgnored() {
        List<ProcessingProviders.ProviderEntry> entries =
                ProcessingProviders.parseOrder("embedded,unknown_provider", KNOWN_TRANSLATION);
        assertEquals(KNOWN_TRANSLATION.size(), entries.size());
    }

    @Test
    public void parseOrderRimeOffForOldLists() {
        // A stored list that predates rime should have rime off.
        List<ProcessingProviders.ProviderEntry> entries =
                ProcessingProviders.parseOrder("embedded,google", KNOWN_ROMANIZATION);
        for (ProcessingProviders.ProviderEntry entry : entries) {
            if (entry.id().equals(ProcessingProviders.RIME)) {
                assertFalse(entry.enabled());
            }
        }
    }

    // ── enabledProviders ──

    @Test
    public void enabledProvidersNullAllEnabled() {
        List<String> enabled = ProcessingProviders.enabledProviders(null, KNOWN_TRANSLATION);
        assertEquals(KNOWN_TRANSLATION.size(), enabled.size());
    }

    @Test
    public void enabledProvidersSkipsDisabled() {
        List<String> enabled = ProcessingProviders.enabledProviders("embedded,-google",
                KNOWN_TRANSLATION);
        assertFalse(enabled.contains("google"));
        assertTrue(enabled.contains("embedded"));
    }

    // ── serializeOrder ──

    @Test
    public void serializeOrderRoundTrip() {
        List<ProcessingProviders.ProviderEntry> entries =
                ProcessingProviders.parseOrder(null, KNOWN_TRANSLATION);
        String serialized = ProcessingProviders.serializeOrder(entries);
        List<ProcessingProviders.ProviderEntry> parsed =
                ProcessingProviders.parseOrder(serialized, KNOWN_TRANSLATION);
        assertEquals(entries.size(), parsed.size());
        for (int i = 0; i < entries.size(); i++) {
            assertEquals(entries.get(i).id(), parsed.get(i).id());
            assertEquals(entries.get(i).enabled(), parsed.get(i).enabled());
        }
    }

    @Test
    public void serializeOrderEmptyList() {
        assertEquals("", ProcessingProviders.serializeOrder(List.of()));
    }

    // ── withProviderEnabled ──

    @Test
    public void withProviderEnabledEnables() {
        String result = ProcessingProviders.withProviderEnabled(
                "embedded,-google", KNOWN_TRANSLATION, "google", true);
        assertTrue(ProcessingProviders.parseOrder(result, KNOWN_TRANSLATION)
                .stream().anyMatch(e -> e.id().equals("google") && e.enabled()));
    }

    @Test
    public void withProviderEnabledDisables() {
        String result = ProcessingProviders.withProviderEnabled(
                "embedded,google", KNOWN_TRANSLATION, "google", false);
        assertTrue(ProcessingProviders.parseOrder(result, KNOWN_TRANSLATION)
                .stream().anyMatch(e -> e.id().equals("google") && !e.enabled()));
    }

    @Test
    public void withProviderEnabledNoChangeReturnsSame() {
        String stored = "embedded,-google";
        String result = ProcessingProviders.withProviderEnabled(
                stored, KNOWN_TRANSLATION, "google", false);
        assertEquals(stored, result);
    }

    // ── isAi ──

    @Test
    public void isAiTrueForOpenAiCompatible() {
        assertTrue(ProcessingProviders.isAi(ProcessingProviders.OPENAI_COMPATIBLE));
    }

    @Test
    public void isAiFalseForOtherProviders() {
        assertFalse(ProcessingProviders.isAi(ProcessingProviders.GOOGLE));
        assertFalse(ProcessingProviders.isAi(ProcessingProviders.DEEPL));
        assertFalse(ProcessingProviders.isAi(ProcessingProviders.EMBEDDED));
        //noinspection ConstantValue
        assertFalse(ProcessingProviders.isAi(null));
    }

    // ── constants ──

    @Test
    public void defaultTranslationOrder() {
        assertEquals("embedded,google,-openai_compatible,-deepl",
                ProcessingProviders.DEFAULT_TRANSLATION_ORDER);
    }

    @Test
    public void defaultRomanizationOrder() {
        assertEquals("embedded,google,-local,-rime,icu,-openai_compatible",
                ProcessingProviders.DEFAULT_ROMANIZATION_ORDER);
    }

    @Test
    public void endpointPresetsNotEmpty() {
        assertTrue(ProcessingProviders.ENDPOINT_PRESETS.size() >= 10);
    }
}
