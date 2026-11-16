/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class OpenAIClientTest {

    // ── chatUrl ──

    @Test
    public void chatUrlNullReturnsNull() {
        assertNull(OpenAIClient.chatUrl(null));
        assertNull(OpenAIClient.chatUrl(""));
        assertNull(OpenAIClient.chatUrl("   "));
    }

    @Test
    public void chatUrlAppendsChatPathWhenNoPath() {
        assertEquals("https://api.openai.com/v1/chat/completions",
                OpenAIClient.chatUrl("https://api.openai.com/v1"));
    }

    @Test
    public void chatUrlAppendsChatPathWhenBareHost() {
        assertEquals("https://api.openai.com/chat/completions",
                OpenAIClient.chatUrl("https://api.openai.com"));
    }

    @Test
    public void chatUrlKeepsExistingChatPath() {
        assertEquals("https://api.openai.com/v1/chat/completions",
                OpenAIClient.chatUrl("https://api.openai.com/v1/chat/completions"));
    }

    @Test
    public void chatUrlKeepsExistingCompletionsPath() {
        assertEquals("https://api.openai.com/v1/completions",
                OpenAIClient.chatUrl("https://api.openai.com/v1/completions"));
    }

    @Test
    public void chatUrlKeepsNonVersionPath() {
        assertEquals("https://api.openai.com/custom/endpoint",
                OpenAIClient.chatUrl("https://api.openai.com/custom/endpoint"));
    }

    @Test
    public void chatUrlStripsTrailingSlash() {
        assertEquals("https://api.openai.com/v1/chat/completions",
                OpenAIClient.chatUrl("https://api.openai.com/v1/"));
    }

    @Test
    public void chatUrlVersionSegmentMatchesV1() {
        assertEquals("https://api.example.com/v1/chat/completions",
                OpenAIClient.chatUrl("https://api.example.com/v1"));
    }

    @Test
    public void chatUrlVersionSegmentMatchesV1Beta() {
        assertEquals("https://api.example.com/v1beta/chat/completions",
                OpenAIClient.chatUrl("https://api.example.com/v1beta"));
    }

    @Test
    public void chatUrlCompatibleModePath() {
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                OpenAIClient.chatUrl("https://generativelanguage.googleapis.com/v1beta/openai"));
    }

    // ── isValidExtraBody ──

    @Test
    public void isValidExtraBodyNullOrEmptyTrue() {
        assertTrue(OpenAIClient.isValidExtraBody(null));
        assertTrue(OpenAIClient.isValidExtraBody(""));
        assertTrue(OpenAIClient.isValidExtraBody("   "));
    }

    @Test
    public void isValidExtraBodyValidJsonTrue() {
        assertTrue(OpenAIClient.isValidExtraBody("{\"key\": \"value\"}"));
    }

    @Test
    public void isValidExtraBodyInvalidJsonFalse() {
        assertFalse(OpenAIClient.isValidExtraBody("not json"));
        assertFalse(OpenAIClient.isValidExtraBody("[1,2,3]")); // array, not object
        assertFalse(OpenAIClient.isValidExtraBody("\"string\"")); // bare value
    }

    // ── isValidExtraHeaders ──

    @Test
    public void isValidExtraHeadersNullOrEmptyTrue() {
        assertTrue(OpenAIClient.isValidExtraHeaders(null));
        assertTrue(OpenAIClient.isValidExtraHeaders(""));
        assertTrue(OpenAIClient.isValidExtraHeaders("   "));
    }

    @Test
    public void isValidExtraHeadersValidJsonTrue() {
        assertTrue(OpenAIClient.isValidExtraHeaders("{\"X-Custom\": \"value\"}"));
    }

    @Test
    public void isValidExtraHeadersBlankNameFalse() {
        assertFalse(OpenAIClient.isValidExtraHeaders("{\"\": \"value\"}"));
    }

    @Test
    public void isValidExtraHeadersNonStringValueFalse() {
        assertFalse(OpenAIClient.isValidExtraHeaders("{\"X-Custom\": 123}"));
    }

    @Test
    public void isValidExtraHeadersMultilineValueFalse() {
        assertFalse(OpenAIClient.isValidExtraHeaders("{\"X-Custom\": \"line1\\nline2\"}"));
    }

    @Test
    public void isValidExtraHeadersInvalidJsonFalse() {
        assertFalse(OpenAIClient.isValidExtraHeaders("not json"));
    }

    // ── isSkipToken ──

    @Test
    public void isSkipTokenNullTrue() {
        assertTrue(OpenAIClient.isSkipToken(null));
    }

    @Test
    public void isSkipTokenEmptyTrue() {
        assertTrue(OpenAIClient.isSkipToken(""));
        assertTrue(OpenAIClient.isSkipToken("   "));
    }

    @Test
    public void isSkipTokenSkipVariantsTrue() {
        assertTrue(OpenAIClient.isSkipToken("SKIP"));
        assertTrue(OpenAIClient.isSkipToken("skip"));
        assertTrue(OpenAIClient.isSkipToken("[SKIP]"));
        assertTrue(OpenAIClient.isSkipToken("<SKIP>"));
        assertTrue(OpenAIClient.isSkipToken("(SKIP)"));
        assertTrue(OpenAIClient.isSkipToken("SKIP."));
    }

    @Test
    public void isSkipTokenNormalTextFalse() {
        assertFalse(OpenAIClient.isSkipToken("hello"));
        assertFalse(OpenAIClient.isSkipToken("translate"));
    }

    // ── extractLastNumberedBlock ──

    @Test
    public void extractLastNumberedBlockReturnsLastNumberedBlock() {
        String text = "Here are the translations:\n1. first\n2. second\n3. third";
        assertEquals("first\nsecond\nthird",
                OpenAIClient.extractLastNumberedBlock(text));
    }

    @Test
    public void extractLastNumberedBlockHandlesLinePrefix() {
        String text = "Note:\nLine 1. hello\nLine 2. world";
        // A "Line" prefix breaks the scan, so it returns null.
        assertNull(OpenAIClient.extractLastNumberedBlock(text));
    }

    @Test
    public void extractLastNumberedBlockNullForEmpty() {
        assertNull(OpenAIClient.extractLastNumberedBlock(null));
        assertNull(OpenAIClient.extractLastNumberedBlock(""));
    }

    @Test
    public void extractLastNumberedBlockNullForNoNumberedLines() {
        assertNull(OpenAIClient.extractLastNumberedBlock("just some text\nno numbers here"));
    }

    // ── fitsOneRequest ──

    @Test
    public void fitsOneRequestSmallListTrue() {
        List<String> lines = List.of("hello", "world");
        assertTrue(OpenAIClient.fitsOneRequest(lines));
    }

    @Test
    public void fitsOneRequestEmptyListTrue() {
        assertTrue(OpenAIClient.fitsOneRequest(List.of()));
    }

    @Test
    public void fitsOneRequestLargeListFalse() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            lines.add("a very long line with many characters that will exceed the limit");
        }
        assertFalse(OpenAIClient.fitsOneRequest(lines));
    }

    // ── mapResponse ──

    @Test
    public void mapResponseNullReturnsNull() {
        assertNull(OpenAIClient.mapResponse(null, List.of("hello")));
    }

    @Test
    public void mapResponseNumberedLines() {
        String response = "1. hello\n2. world";
        List<String> source = List.of("hello", "world");
        List<String> result = OpenAIClient.mapResponse(response, source);
        assertEquals("hello", Objects.requireNonNull(result).get(0));
        assertEquals("world", result.get(1));
    }

    @Test
    public void mapResponseSkipTokenBecomesEmpty() {
        String response = "1. hello\n2. SKIP";
        List<String> source = List.of("hello", "world");
        List<String> result = OpenAIClient.mapResponse(response, source);
        assertEquals("hello", Objects.requireNonNull(result).get(0));
        assertEquals("", result.get(1));
    }

    @Test
    public void mapResponseAllSkipReturnsNull() {
        String response = "1. SKIP\n2. SKIP";
        List<String> source = List.of("hello", "world");
        assertNull(OpenAIClient.mapResponse(response, source));
    }

    @Test
    public void mapResponseUnnumberedLines() {
        String response = "translated one\ntranslated two";
        List<String> source = List.of("one", "two");
        List<String> result = OpenAIClient.mapResponse(response, source);
        assertEquals("translated one", Objects.requireNonNull(result).get(0));
        assertEquals("translated two", result.get(1));
    }

    // ── renderPrompt ──

    @Test
    public void renderPromptFillsVariables() {
        String template = "Translate {lines} from {language} for {artist} - {title}";
        String result = OpenAIClient.renderPrompt(template, "translate", "en",
                "My Song", "My Artist", List.of("line one", "line two"));
        assertTrue(result.contains("line one"));
        assertTrue(result.contains("line two"));
        assertTrue(result.contains("en"));
        assertTrue(result.contains("My Artist"));
        assertTrue(result.contains("My Song"));
    }

    @Test
    public void renderPromptCountVariable() {
        String template = "There are {count} lines.";
        String result = OpenAIClient.renderPrompt(template, "task", "lang",
                "title", "artist", List.of("a", "b", "c"));
        assertTrue(result.contains("3"));
    }

    @Test
    public void renderPromptEmptyTemplateUsesDefault() {
        String result = OpenAIClient.renderPrompt("", "task", "lang",
                "title", "artist", List.of("a"));
        assertTrue(result.contains("a"));
    }

    @Test
    public void renderPromptSkipForEmptyLines() {
        String template = "Lines:\n{lines}";
        String result = OpenAIClient.renderPrompt(template, "task", "lang",
                "title", "artist", List.of("hello", ""));
        assertTrue(result.contains("SKIP"));
    }
}
