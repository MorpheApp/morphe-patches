package app.morphe.extension.youtube.patches;

import org.junit.Test;
import org.json.JSONArray;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class CaptionBlockTranslatorTest {
    private static class Reply extends HttpURLConnection {
        final byte[] bytes;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        boolean closed, disconnected, outputClosed;
        String retry, redirect, date;
        final Map<String, String> headers = new HashMap<>();
        public void setRequestProperty(String name, String value) { headers.put(name, value); super.setRequestProperty(name, value); }
        Reply(int status, String body) throws Exception {
            super(new URL("https://test.invalid")); responseCode = status;
            bytes = body.getBytes(StandardCharsets.UTF_8);
        }
        public void connect() {}
        public boolean usingProxy() { return false; }
        public void disconnect() { disconnected = true; }
        public int getResponseCode() throws IOException { return responseCode; }
        public String getHeaderField(String key) { return "Retry-After".equals(key) ? retry : "Location".equals(key) ? redirect : "Date".equals(key) ? date : null; }
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
        Runnable onPause = () -> {};
        final CaptionBlockTranslator translator = new CaptionBlockTranslator(url -> {
            urls.add(url);
            if (replies.isEmpty()) throw new IOException("Unexpected request");
            return replies.remove();
        }, new CaptionBlockTranslator.Clock() {
            public long millis() { return now; }
            public void pause(long millis) { now += millis; onPause.run(); }
        });
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
    static String google(String value) throws Exception {
        return new JSONArray().put(new JSONArray().put(value)).toString();
    }
    @Test public void googleSuccessDoesNotContactMicrosoft() throws Exception {
        Fixture f = new Fixture();
        f.add(200, google("eins"));
        assertEquals(List.of("eins"), f.translate("one"));
        assertEquals(1, f.urls.size());
        assertTrue(f.urls.get(0).startsWith("https://translate-pa.googleapis.com/"));
    }
    @Test public void googleSendsEscapedTextWithExplicitSourceAndTarget() throws Exception {
        Fixture f = new Fixture(); Reply reply = f.add(200, google("eins"));
        assertEquals(List.of("eins"), f.translate("<one>&"));
        assertEquals("[[[\"&lt;one&gt;&amp;\"],\"en\",\"de\"],\"wt_lib\"]", reply.sent.toString("UTF-8"));
        assertEquals("application/json+protobuf", reply.headers.get("Content-Type"));
        assertFalse(reply.headers.get("X-Goog-API-Key").isBlank());
    }
    @Test public void googleAllowsInitialBurstThenReplenishesEightRequestsPerSecond() throws Exception {
        Fixture f = new Fixture();
        for (int i = 0; i < 20; i++) { f.add(200, google("eins")); f.translate("text" + i); }
        assertEquals(0, f.now);
        for (int i = 20; i < 28; i++) { f.add(200, google("eins")); f.translate("text" + i); }
        assertEquals(1000, f.now);
        f.now += 10000;
        for (int i = 28; i < 48; i++) { f.add(200, google("eins")); f.translate("text" + i); }
        assertEquals(11000, f.now);
        f.add(200, google("eins")); f.translate("last");
        assertEquals(11125, f.now);
    }
    @Test public void cancellationDuringPacingDoesNotOpenAnotherConnection() throws Exception {
        Fixture f = new Fixture();
        for (int i = 0; i < 20; i++) { f.add(200, google("eins")); f.translate("text" + i); }
        AtomicBoolean active = new AtomicBoolean(true); f.onPause = () -> active.set(false);
        assertThrows(CancellationException.class, () -> f.translator.translate("en", "de", List.of("new"), active::get));
        assertEquals(20, f.urls.size());
    }
    @Test public void pacingUsesPrimaryBudgetAndLeavesMicrosoftFallbackAvailable() throws Exception {
        Fixture f = new Fixture();
        for (int i = 0; i < 20; i++) { f.add(200, google("eins")); f.translate("text" + i); }
        f.onPause = () -> f.now += 4000;
        Reply fallback = f.add(200, ms("zwei"));
        assertEquals(List.of("zwei"), f.translate("new"));
        assertEquals(21, f.urls.size()); assertTrue(f.urls.get(20).contains("edge.microsoft.com"));
        assertTrue(fallback.getReadTimeout() > 0 && fallback.getReadTimeout() < 4000);
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
        Fixture f = new Fixture(); f.add(500, "unavailable"); f.add(500, "unavailable");
        Reply r = f.add(200, ms("&amp;lt; &lt; &gt; &amp;", "zwei"));
        assertEquals(Arrays.asList("&lt; < > &", "zwei"), f.translate("<x>&", "two"));
        assertEquals("[\"&lt;x&gt;&amp;\",\"two\"]", r.sent.toString("UTF-8"));
        assertEquals("POST", r.getRequestMethod());
        assertTrue(r.closed && r.outputClosed && r.disconnected);
        assertTrue(r.getConnectTimeout() > 0 && r.getConnectTimeout() <= 8000);
        assertTrue(r.getReadTimeout() > 0 && r.getReadTimeout() <= 8000);
    }
    @Test public void malformedGoogleFallsBackOnceToMicrosoft() throws Exception {
        Fixture f = new Fixture(); f.add(200, "[]");
        Reply fallback = f.add(200, ms("ein zwei"));
        assertEquals(List.of("ein zwei"), f.translate("one two"));
        assertEquals(2, f.urls.size());
        assertTrue(f.urls.get(1).startsWith("https://edge.microsoft.com/"));
        assertEquals("[\"one two\"]", fallback.sent.toString("UTF-8"));
    }
    @Test public void googlePreservesWholeTranslationAndClosesConnection() throws Exception {
        Fixture f = new Fixture();
        Reply g = f.add(200, google("ein zwei"));
        assertEquals(List.of("ein zwei"), f.translate("one two"));
        assertEquals("POST", g.getRequestMethod()); assertTrue(g.closed && g.disconnected);
    }
    @Test public void rejectsPartialNullBlankOversizedAndWrongLanguageWithoutCaching() throws Exception {
        for (String bad : Arrays.asList("[]", ms("only one"), "[null,null]", ms("a","b").replace("\"de\"", "\"tr\""))) {
            Fixture f = new Fixture(); f.add(500, "error"); f.add(500, "error"); f.add(200, bad);
            assertThrows(IOException.class, () -> f.translate("first", "second"));
            f.add(500, "error"); f.add(500, "error"); f.add(200, ms("eins", "zwei"));
            assertEquals(Arrays.asList("eins", "zwei"), f.translate("first", "second"));
            assertEquals(6, f.urls.size());
        }
    }
    @Test public void rejectsNonStringMicrosoftFieldsWithoutCaching() throws Exception {
        for (String field : Arrays.asList("text", "to")) {
            for (String invalid : Arrays.asList("null", "42", "{}")) {
                String text = field.equals("text") ? invalid : "\"eins\"";
                String language = field.equals("to") ? invalid : "\"de\"";
                Fixture f = new Fixture(); f.add(500, "Google unavailable");
                f.add(200, "[{\"translations\":[{\"text\":" + text + ",\"to\":" + language + "}]}]");
                assertThrows(IOException.class, () -> f.translate("one"));
                f.add(500, "Google unavailable"); f.add(200, ms("eins"));
                assertEquals(Arrays.asList("eins"), f.translate("one"));
                assertEquals(4, f.urls.size());
            }
        }
    }
    @Test public void rejectsNonStringGoogleSegmentsWithoutCaching() throws Exception {
        for (String invalid : Arrays.asList("null", "42", "{}")) {
            Fixture f = new Fixture(); f.add(200, "[[" + invalid + "]]");
            f.add(500, "Microsoft unavailable");
            assertThrows(IOException.class, () -> f.translate("one"));
            f.add(200, google("eins"));
            assertEquals(Arrays.asList("eins"), f.translate("one"));
            assertEquals(3, f.urls.size());
        }
    }
    @Test public void cacheIncludesSourceTargetAndEvictsOldestBeyond256() throws Exception {
        Fixture f = new Fixture(); f.add(200, google("eins")); f.translate("one");
        assertEquals(Arrays.asList("eins"), f.translate("one")); assertEquals(1, f.urls.size());
        f.add(200, google("anderes")); f.translator.translate("fr", "de", Arrays.asList("one"));
        f.add(200, google("anders"));
        f.translator.translate("en", "nl", Arrays.asList("one"));
        for (int i = 0; i < 256; i++) { f.add(200, google("text")); f.translate("value" + i); }
        f.add(200, google("neu")); assertEquals(Arrays.asList("neu"), f.translate("one"));
        assertEquals(260, f.urls.size());
    }
    @Test public void retryAfterDatesUseServerTimeAndRemainBounded() throws Exception {
        String[] dates = {"Wed, 21 Oct 2015 07:31:00 GMT", "Wed, 21 Oct 2015 07:27:00 GMT",
                "Wed, 21 Oct 2015 08:28:00 GMT", "invalid"};
        long[] delays = {180000, 1000, 300000, 60000};
        for (int i = 0; i < dates.length; i++) {
            Fixture f = new Fixture();
            for (int provider = 0; provider < 2; provider++) {
                Reply reply = f.add(429, "limited");
                reply.date = "Wed, 21 Oct 2015 07:28:00 GMT";
                reply.retry = dates[i];
            }
            var failure = (CaptionBlockTranslator.Failure) assertThrows(IOException.class, () -> f.translate("one"));
            assertEquals(delays[i], failure.retryAfterMs);
            f.now = delays[i] - 1;
            assertThrows(IOException.class, () -> f.translate("one"));
            assertEquals(2, f.urls.size());
            f.now = delays[i]; f.add(200, google("eins"));
            assertEquals(List.of("eins"), f.translate("one"));
        }
    }
    @Test public void providerCooldownsAreBoundedAndIndependent() throws Exception {
        for (int code : new int[]{403,429}) {
            Fixture f = new Fixture(); Reply primary = f.add(code, "blocked"), fallback = f.add(code, "blocked");
            primary.retry = fallback.retry = "9999";
            assertThrows(IOException.class, () -> f.translate("one"));
            assertThrows(IOException.class, () -> f.translate("another"));
            assertEquals(2, f.urls.size()); assertTrue(primary.disconnected && fallback.disconnected);
            f.now = 300_000; f.add(200, google("eins")); f.translate("one"); assertEquals(3, f.urls.size());
        }
    }
    @Test public void partialGoogleBatchCachesSuccessfulTextOnly() throws Exception {
        Fixture f = new Fixture();
        f.add(200, google("eins")); f.add(200, "[[null]]"); f.add(500, "bad");
        assertEquals(Arrays.asList("eins", null), f.translate("one", "two"));
        f.add(200, google("zwei"));
        assertEquals(List.of("eins", "zwei"), f.translate("one", "two"));
        assertEquals("https://translate-pa.googleapis.com/v1/translateHtml", f.urls.get(3));
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
        Reply fallback = f.add(200, ms("eins"));
        assertEquals(List.of("eins"), f.translate("one"));
        assertTrue(fallback.getReadTimeout() > 0 && fallback.getReadTimeout() <= 4000);
    }
    @Test public void verificationRedirectBacksOffOnlyGoogle() throws Exception {
        Fixture f = new Fixture();
        Reply challenge = f.add(302, "verify");
        challenge.redirect = "https://www.google.com/sorry/index?continue=opaque";
        f.add(500, "unavailable");
        assertThrows(IOException.class, () -> f.translate("one"));
        assertTrue(challenge.disconnected);
        f.add(500, "still unavailable");
        assertThrows(IOException.class, () -> f.translate("one"));
        assertEquals(3, f.urls.size());
        f.add(200, ms("eins"));
        assertEquals(List.of("eins"), f.translate("one"));
        f.now = 60000; f.add(200, google("zwei"));
        assertEquals(List.of("zwei"), f.translate("two"));
        assertEquals(5, f.urls.size());
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
        f.now = 59999; f.add(200, google("eins")); assertEquals(List.of("eins"), f.translate("one"));
        assertEquals(3, f.urls.size());
        f.now = 60000; f.add(500, "unavailable"); f.add(200, ms("zwei"));
        assertEquals(List.of("zwei"), f.translate("two")); assertEquals(5, f.urls.size());
    }
    @Test public void acceptsCodepointsRatherThanUtf16UnitsAndPreservesLanguageSubtags() throws Exception {
        Fixture f = new Fixture();
        String source = "😀".repeat(512);
        Reply reply = f.add(200, google("😀".repeat(4096)));
        assertEquals(8192, f.translator.translate("zh-Hant", "sr-Latn", Arrays.asList(source)).get(0).length());
        JSONArray request = new JSONArray(reply.sent.toString("UTF-8")).getJSONArray(0);
        assertEquals("zh-hant", request.getString(1));
        assertEquals("sr-latn", request.getString(2));
    }
    @Test public void connectionTimeIsRemovedFromResponseTimeout() throws Exception {
        Fixture f = new Fixture();
        Reply slowConnect = new Reply(200, google("eins")) {
            @Override public void connect() { f.now = 3000; }
            @Override public int getResponseCode() {
                assertTrue("read timeout includes time already spent connecting", getReadTimeout() <= 1000);
                return 200;
            }
        };
        f.replies.add(slowConnect); f.translate("one");
    }
    @Test public void unicodeBlankOutputIsRejected() throws Exception {
        Fixture f = new Fixture(); f.add(200, google("\u2003")); f.add(500, "bad");
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
        Fixture f = new Fixture(); f.add(500, "unavailable"); f.add(500, "unavailable");
        f.add(200, ms("eins", ""));
        assertEquals(Arrays.asList("eins", null), f.translate("one", "two"));
        f.add(500, "unavailable"); Reply missing = f.add(200, ms("zwei"));
        assertEquals(List.of("eins", "zwei"), f.translate("one", "two"));
        assertEquals("[\"two\"]", missing.sent.toString("UTF-8"));
    }
    @Test public void primaryProviderCooldownDoesNotBlockIndependentFallback() throws Exception {
        Fixture f = new Fixture(); f.add(429, "limited"); f.add(200, ms("eins"));
        assertEquals(List.of("eins"), f.translate("one"));
        f.add(200, ms("zwei")); assertEquals(List.of("zwei"), f.translate("two"));
        assertEquals(3, f.urls.size()); assertTrue(f.urls.get(2).contains("edge.microsoft.com"));
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

    private static class WaitingReply extends Reply {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final boolean body;
        WaitingReply(boolean body, String response) throws Exception { super(200, response); this.body = body; }
        void awaitRelease() throws IOException {
            entered.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Test response timed out"); }
            catch (InterruptedException interrupted) { throw new IOException(interrupted); }
            if (disconnected) throw new IOException("Disconnected");
        }
        public int getResponseCode() throws IOException { if (!body) awaitRelease(); return 200; }
        public InputStream getInputStream() {
            if (!body) return super.getInputStream();
            return new FilterInputStream(new ByteArrayInputStream(bytes)) {
                public int read(byte[] b, int offset, int length) throws IOException {
                    awaitRelease();
                    return super.read(b, offset, length);
                }
            };
        }
        public InputStream getErrorStream() { return null; }
        public void disconnect() { super.disconnect(); release.countDown(); }
    }
    @Test public void invalidationDisconnectsBlockedResponsesAndNeverStartsFallback() throws Exception {
        for (boolean microsoft : new boolean[]{false, true}) for (boolean body : new boolean[]{false, true}) {
            Fixture f = new Fixture(); if (microsoft) f.add(500, "unavailable");
            WaitingReply reply = new WaitingReply(body, microsoft ? ms("eins") : google("eins"));
            f.replies.add(reply); AtomicBoolean active = new AtomicBoolean(true);
            FutureTask<List<String>> task = new FutureTask<>(() -> f.translator.translate("en", "de", List.of("one"), active::get));
            Thread thread = new Thread(task); thread.start();
            try {
                assertTrue(reply.entered.await(1, TimeUnit.SECONDS));
                active.set(false); f.translator.cancelInactive();
                ExecutionException failure = assertThrows(ExecutionException.class, () -> task.get(1, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof CancellationException);
                assertTrue(reply.disconnected);
                assertEquals(microsoft ? 2 : 1, f.urls.size());
            } finally { reply.release.countDown(); thread.join(1000); }
        }
    }
    @Test public void delayedCancellationLeavesReplacementRequestAlive() throws Exception {
        Fixture f = new Fixture();
        for (boolean cancelled : new boolean[]{true, false}) {
            WaitingReply reply = new WaitingReply(false, google("eins")); f.replies.add(reply);
            AtomicBoolean active = new AtomicBoolean(true);
            FutureTask<List<String>> task = new FutureTask<>(() -> f.translator.translate("en", "de", List.of("one"), active::get));
            Thread thread = new Thread(task); thread.start();
            try {
                assertTrue(reply.entered.await(1, TimeUnit.SECONDS));
                if (cancelled) active.set(false);
                f.translator.cancelInactive();
                if (cancelled) {
                    ExecutionException failure = assertThrows(ExecutionException.class, () -> task.get(1, TimeUnit.SECONDS));
                    assertTrue(failure.getCause() instanceof CancellationException);
                } else {
                    assertFalse(reply.disconnected); reply.release.countDown();
                    assertEquals(List.of("eins"), task.get(1, TimeUnit.SECONDS));
                }
            } finally { reply.release.countDown(); thread.join(1000); }
        }
    }
    @Test public void invalidationWhileOpeningPreventsConnectAndClosesConnection() throws Exception {
        Reply reply = new Reply(200, google("eins")) { public void connect() { fail("Cancelled request must not connect"); } };
        CountDownLatch opened = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean active = new AtomicBoolean(true);
        CaptionBlockTranslator translator = new CaptionBlockTranslator(url -> {
            opened.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Test open timed out"); }
            catch (InterruptedException interrupted) { throw new IOException(interrupted); }
            return reply;
        }, () -> 0);
        FutureTask<List<String>> task = new FutureTask<>(() -> translator.translate("en", "de", List.of("one"), active::get));
        Thread thread = new Thread(task); thread.start();
        try {
            assertTrue(opened.await(1, TimeUnit.SECONDS)); active.set(false); translator.cancelInactive(); release.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> task.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException); assertTrue(reply.disconnected);
        } finally { release.countDown(); thread.join(1000); }
    }
    @Test public void invalidationDuringConfigurationPreventsConnect() throws Exception {
        Fixture f = new Fixture(); AtomicBoolean active = new AtomicBoolean(true);
        Reply reply = new Reply(200, google("eins")) {
            public void setRequestMethod(String method) throws ProtocolException {
                super.setRequestMethod(method); active.set(false); f.translator.cancelInactive();
            }
            public void connect() { fail("Cancelled request must not connect"); }
        };
        f.replies.add(reply);
        assertThrows(CancellationException.class, () -> f.translator.translate("en", "de", List.of("one"), active::get));
        assertTrue(reply.disconnected); assertEquals(1, f.urls.size());
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
