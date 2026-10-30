package app.morphe.extension.youtube.patches;

import org.junit.Test;
import org.json.JSONArray;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class CaptionBlockTranslatorTest {
    private static class Reply extends HttpURLConnection {
        final byte[] bytes;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        boolean closed, disconnected, outputClosed;
        String retry, redirect;
        Reply(int status, String body) throws Exception {
            super(new URL("https://test.invalid")); responseCode = status;
            bytes = body.getBytes(StandardCharsets.UTF_8);
        }
        public void connect() {}
        public boolean usingProxy() { return false; }
        public void disconnect() { disconnected = true; }
        public int getResponseCode() { return responseCode; }
        public String getHeaderField(String key) { return "Retry-After".equals(key) ? retry : "Location".equals(key) ? redirect : null; }
        public InputStream getInputStream() { return new ByteArrayInputStream(bytes) {
            public void close() throws IOException { closed = true; super.close(); }
        }; }
        public InputStream getErrorStream() { return getInputStream(); }
        public OutputStream getOutputStream() { return new FilterOutputStream(sent) {
            public void close() throws IOException { outputClosed = true; super.close(); }
        }; }
    }
    private static class Fixture {
        final Deque<Reply> replies = new ArrayDeque<>();
        final List<String> urls = new ArrayList<>();
        long now;
        final CaptionBlockTranslator translator = new CaptionBlockTranslator(url -> {
            urls.add(url);
            if (replies.isEmpty()) throw new IOException("Unexpected request");
            return replies.remove();
        }, () -> now);
        Reply add(int code, String body) throws Exception {
            Reply r = new Reply(code, body); replies.add(r); return r;
        }
        List<String> translate(String... texts) throws IOException {
            return translator.translate("en", "de", Arrays.asList(texts));
        }
    }
    static String ms(String... values) throws Exception {
        JSONArray out = new JSONArray();
        for (String value : values) out.put(new org.json.JSONObject().put("translations",
                new JSONArray().put(new org.json.JSONObject().put("text", value).put("to", "de"))));
        return out.toString();
    }
    @Test public void sameLanguageReturnsImmutableOriginalWithoutNetwork() throws Exception {
        Fixture f = new Fixture();
        List<String> input = new ArrayList<>(Arrays.asList("hello"));
        List<String> result = f.translator.translate("zh-Hant", "ZH-hant", input);
        input.set(0, "changed");
        assertEquals(Arrays.asList("hello"), result);
        assertThrows(UnsupportedOperationException.class, () -> result.set(0, "bad"));
        assertTrue(f.urls.isEmpty());
    }
    @Test public void validatesInputsBeforeAnyNetwork() throws Exception {
        Fixture f = new Fixture();
        for (List<String> values : Arrays.asList(Collections.<String>emptyList(),
                Arrays.asList((String)null), Arrays.asList("  "), Arrays.asList("x".repeat(513)),
                Arrays.asList("1","2","3","4","5","6"))) {
            assertThrows(IOException.class, () -> f.translator.translate("en", "de", values));
        }
        assertThrows(IOException.class, () -> f.translator.translate("en&key=x", "de", Arrays.asList("x")));
        assertTrue(f.urls.isEmpty());
    }
    @Test public void preservesOrderEscapesRequestAndDecodesExactlyOnce() throws Exception {
        Fixture f = new Fixture();
        Reply r = f.add(200, ms("&amp;lt; &lt; &gt; &amp;", "zwei"));
        assertEquals(Arrays.asList("&lt; < > &", "zwei"), f.translate("<x>&", "two"));
        assertEquals("[\"&lt;x&gt;&amp;\",\"two\"]", r.sent.toString("UTF-8"));
        assertEquals("POST", r.getRequestMethod());
        assertTrue(r.closed && r.outputClosed && r.disconnected);
        assertTrue(r.getConnectTimeout() > 0 && r.getConnectTimeout() <= 8000);
        assertTrue(r.getReadTimeout() > 0 && r.getReadTimeout() <= 8000);
    }
    @Test public void malformedMicrosoftFallsBackOnceAndJoinsGoogleSegments() throws Exception {
        Fixture f = new Fixture(); f.add(200, "[]");
        Reply g = f.add(200, "[[[\"ein\"],[\" zwei\"]],null,\"en\"]");
        assertEquals(Arrays.asList("ein zwei"), f.translate("one two"));
        assertEquals(2, f.urls.size()); assertTrue(f.urls.get(1).contains("client=gtx"));
        assertEquals("GET", g.getRequestMethod()); assertTrue(g.closed && g.disconnected);
    }
    @Test public void rejectsPartialNullBlankOversizedAndWrongLanguageWithoutCaching() throws Exception {
        for (String bad : Arrays.asList("[]", ms("only one"), "[null,null]", ms("a","b").replace("\"de\"", "\"tr\""))) {
            Fixture f = new Fixture(); f.add(200, bad); f.add(500, "error"); f.add(500, "error");
            assertThrows(IOException.class, () -> f.translate("first", "second"));
            f.add(200, ms("eins", "zwei"));
            assertEquals(Arrays.asList("eins", "zwei"), f.translate("first", "second"));
            assertEquals(4, f.urls.size());
        }
    }
    @Test public void rejectsNonStringMicrosoftFieldsWithoutCaching() throws Exception {
        for (String field : Arrays.asList("text", "to")) {
            for (String invalid : Arrays.asList("null", "42", "{}")) {
                String text = field.equals("text") ? invalid : "\"eins\"";
                String language = field.equals("to") ? invalid : "\"de\"";
                Fixture f = new Fixture();
                f.add(200, "[{\"translations\":[{\"text\":" + text + ",\"to\":" + language + "}]}]");
                f.add(500, "fallback failed");
                assertThrows(IOException.class, () -> f.translate("one"));
                f.add(200, ms("eins"));
                assertEquals(Arrays.asList("eins"), f.translate("one"));
                assertEquals(3, f.urls.size());
            }
        }
    }
    @Test public void rejectsNonStringGoogleSegmentsWithoutCaching() throws Exception {
        for (String invalid : Arrays.asList("null", "42", "{}")) {
            Fixture f = new Fixture(); f.add(400, "unsupported");
            f.add(200, "[[[" + invalid + "]]]");
            assertThrows(IOException.class, () -> f.translate("one"));
            f.add(200, ms("eins"));
            assertEquals(Arrays.asList("eins"), f.translate("one"));
            assertEquals(3, f.urls.size());
        }
    }
    @Test public void cacheIncludesSourceTargetAndEvictsOldestBeyond256() throws Exception {
        Fixture f = new Fixture(); f.add(200, ms("eins")); f.translate("one");
        assertEquals(Arrays.asList("eins"), f.translate("one")); assertEquals(1, f.urls.size());
        f.add(200, ms("anderes")); f.translator.translate("fr", "de", Arrays.asList("one"));
        f.add(200, ms("anders").replace("\"de\"", "\"nl\""));
        f.translator.translate("en", "nl", Arrays.asList("one"));
        for (int i = 0; i < 256; i++) { f.add(200, ms("text")); f.translate("value" + i); }
        f.add(200, ms("neu")); assertEquals(Arrays.asList("neu"), f.translate("one"));
        assertEquals(260, f.urls.size());
    }
    @Test public void providerCooldownsAreBoundedAndIndependent() throws Exception {
        for (int code : new int[]{403,429}) {
            Fixture f = new Fixture(); Reply primary = f.add(code, "blocked"), fallback = f.add(code, "blocked");
            primary.retry = fallback.retry = "9999";
            assertThrows(IOException.class, () -> f.translate("one"));
            assertThrows(IOException.class, () -> f.translate("another"));
            assertEquals(2, f.urls.size()); assertTrue(primary.disconnected && fallback.disconnected);
            f.now = 300_000; f.add(200, ms("eins")); f.translate("one"); assertEquals(3, f.urls.size());
        }
    }
    @Test public void partialGoogleBatchCachesSuccessfulTextOnly() throws Exception {
        Fixture f = new Fixture(); f.add(500, "bad");
        f.add(200, "[[[\"eins\"]]]"); f.add(200, "[[[null]]]");
        assertEquals(Arrays.asList("eins", null), f.translate("one", "two"));
        Reply missing = f.add(200, ms("zwei"));
        assertEquals(List.of("eins", "zwei"), f.translate("one", "two"));
        assertEquals("[\"two\"]", missing.sent.toString("UTF-8"));
        assertEquals(4, f.urls.size());
    }
    @Test public void slowPrimaryLeavesFallbackTimeWithinOverallBudget() throws Exception {
        Fixture f = new Fixture();
        Reply slow = new Reply(500, "bad") {
            @Override public int getResponseCode() {
                assertTrue(getConnectTimeout() <= 4000);
                f.now = 4000;
                return 500;
            }
        };
        f.replies.add(slow);
        Reply fallback = f.add(200, "[[[\"eins\"]]]");
        assertEquals(List.of("eins"), f.translate("one"));
        assertTrue(fallback.getReadTimeout() > 0 && fallback.getReadTimeout() <= 4000);
    }
    @Test public void verificationRedirectBacksOffOnlyTheBlockedFallback() throws Exception {
        Fixture f = new Fixture(); f.add(500, "unavailable");
        Reply challenge = f.add(302, "verify");
        challenge.redirect = "https://www.google.com/sorry/index?continue=opaque";
        assertThrows(IOException.class, () -> f.translate("one"));
        assertTrue(challenge.disconnected);
        f.add(500, "still unavailable");
        assertThrows(IOException.class, () -> f.translate("one"));
        assertEquals(3, f.urls.size());
        f.add(200, ms("eins"));
        assertEquals(List.of("eins"), f.translate("one"));
        f.now = 60000; f.add(500, "unavailable"); f.add(200, "[[[\"zwei\"]]]");
        assertEquals(List.of("zwei"), f.translate("two"));
        assertEquals(6, f.urls.size());
    }
    @Test public void expiredBudgetPreventsFallback() throws Exception {
        Fixture f = new Fixture();
        Reply slow = new Reply(500, "bad") {
            @Override public int getResponseCode() { f.now = 8000; return 500; }
        };
        f.replies.add(slow);
        assertThrows(IOException.class, () -> f.translate("one"));
        assertEquals(1, f.urls.size()); assertTrue(slow.disconnected);
    }
    @Test public void fallbackDenialDoesNotBlockHealthyPrimary() throws Exception {
        Fixture f = new Fixture(); f.add(400, "unsupported"); f.add(403, "forbidden");
        assertThrows(IOException.class, () -> f.translate("one"));
        f.now = 59999; f.add(200, ms("eins")); assertEquals(List.of("eins"), f.translate("one"));
        assertEquals(3, f.urls.size());
        f.now = 60000; f.add(500, "unavailable"); f.add(200, "[[[\"zwei\"]]]");
        assertEquals(List.of("zwei"), f.translate("two")); assertEquals(5, f.urls.size());
    }
    @Test public void acceptsCodepointsRatherThanUtf16UnitsAndPreservesLanguageSubtags() throws Exception {
        Fixture f = new Fixture();
        String source = "😀".repeat(512);
        f.add(200, ms("😀".repeat(4096)).replace("\"de\"", "\"sr-Latn\""));
        assertEquals(8192, f.translator.translate("zh-Hant", "sr-Latn", Arrays.asList(source)).get(0).length());
        assertTrue(f.urls.get(0).contains("from=zh-hant&to=sr-latn"));
    }
    @Test public void connectionTimeIsRemovedFromResponseTimeout() throws Exception {
        Fixture f = new Fixture();
        Reply slowConnect = new Reply(200, ms("eins")) {
            @Override public void connect() { f.now = 3000; }
            @Override public int getResponseCode() {
                assertTrue("read timeout includes time already spent connecting", getReadTimeout() <= 1000);
                return 200;
            }
        };
        f.replies.add(slowConnect); f.translate("one");
    }
    @Test public void unicodeBlankOutputIsRejected() throws Exception {
        Fixture f = new Fixture(); f.add(200, ms("\u2003")); f.add(500, "bad");
        assertThrows(IOException.class, () -> f.translate("one"));
    }
    @Test public void responseSizeIsBoundedAndResourcesCloseOnFailure() throws Exception {
        Fixture f = new Fixture(); Reply r = f.add(200, "x".repeat(262145));
        Reply g = f.add(200, "null");
        assertThrows(IOException.class, () -> f.translate("one"));
        assertTrue(r.closed && r.disconnected && g.closed && g.disconnected);
        assertEquals(2, f.urls.size());
    }
    @Test public void partialMicrosoftResultCachesValidSiblingAndRetriesOnlyMissingText() throws Exception {
        Fixture f = new Fixture(); f.add(200, ms("eins", "")); f.add(500, "unavailable");
        assertEquals(Arrays.asList("eins", null), f.translate("one", "two"));
        Reply missing = f.add(200, ms("zwei"));
        assertEquals(List.of("eins", "zwei"), f.translate("one", "two"));
        assertEquals("[\"two\"]", missing.sent.toString("UTF-8"));
    }
    @Test public void primaryProviderCooldownDoesNotBlockIndependentFallback() throws Exception {
        Fixture f = new Fixture(); f.add(429, "limited"); f.add(200, "[[[\"eins\"]]]");
        assertEquals(List.of("eins"), f.translate("one"));
        f.add(200, "[[[\"zwei\"]]]"); assertEquals(List.of("zwei"), f.translate("two"));
        assertEquals(3, f.urls.size()); assertTrue(f.urls.get(2).contains("translate.googleapis.com"));
    }

    @Test public void fallbackFailurePreservesLongestProviderRetryDelay() throws Exception {
        Fixture f = new Fixture(); Reply limited = f.add(429, "limited"); limited.retry = "60";
        f.add(503, "unavailable");
        IOException failure = assertThrows(IOException.class, () -> f.translate("one"));
        assertTrue(failure instanceof CaptionBlockTranslator.Failure);
        assertEquals(60000, ((CaptionBlockTranslator.Failure) failure).retryAfterMs);
        assertTrue(((CaptionBlockTranslator.Failure) failure).retryable);
        Fixture unequal = new Fixture(); unequal.add(429, "limited").retry = "60";
        unequal.add(429, "limited").retry = "120";
        var longer = (CaptionBlockTranslator.Failure) assertThrows(IOException.class, () -> unequal.translate("one"));
        assertEquals(120000, longer.retryAfterMs);
    }

    @Test public void cancellationStopsFallbackAndClosesCurrentConnection() throws Exception {
        Fixture f = new Fixture(); boolean[] active = {true};
        Reply interrupted = new Reply(500, "unavailable") {
            @Override public int getResponseCode() { active[0] = false; return 500; }
        };
        f.replies.add(interrupted);
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> f.translator.translate("en", "de", List.of("one"), () -> active[0]));
        assertEquals(1, f.urls.size()); assertTrue(interrupted.disconnected);
    }

}
