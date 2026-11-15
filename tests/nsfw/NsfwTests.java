package app.morphe.extension.reddit.patches.nsfw;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic schema/transport tests. These are not evidence of coverage in a Reddit APK. */
public final class NsfwTests {
    private static int passed;

    public static void main(String[] args) throws Exception {
        check("one-way persistence and failed writes", NsfwTests::latch);
        check("mixed REST listing preserves cursors", NsfwTests::listing);
        check("GraphQL search filters posts, users and communities", NsfwTests::graphql);
        check("direct post, community and profile are blocked", NsfwTests::direct);
        check("safe text and spoilers are preserved", NsfwTests::safeContent);
        check("crosspost cannot retain copied adult media", NsfwTests::crosspost);
        check("malformed or overly nested JSON is rejected", NsfwTests::malformed);
        check("disabled interceptor passes bytes unchanged", NsfwTests::disabled);
        check("unrelated domains pass through", NsfwTests::unrelated);
        check("search request and JSON response are filtered", NsfwTests::transport);
        check("activation during a request filters its response", NsfwTests::inFlight);
        check("preferences do not become blocked entities", NsfwTests::prefs);
        check("nested account preferences are restricted without removing account data", NsfwTests::nestedPrefs);
        check("blocked and malformed responses fail closed", NsfwTests::closed);
        check("RedGIFs requests are stopped before transport", NsfwTests::redgifsTransport);
        check("RedGIFs links are removed without keyword filtering", NsfwTests::redgifsLinks);
        check("binary media is left untouched", NsfwTests::binary);
        System.out.println("PASS: " + passed + " host checks (synthetic fixtures; no device access)");
    }

    private static void latch() {
        class MemoryStore implements PermanentNsfwDisabler.Store {
            boolean value;
            boolean writable;
            int writes;
            public boolean read() { return value; }
            public boolean enable() {
                writes++;
                if (!writable) return false;
                value = true;
                return true;
            }
        }
        MemoryStore store = new MemoryStore();
        PermanentNsfwDisabler lock = new PermanentNsfwDisabler(store);
        require(!lock.isEnabled() && !lock.enable() && !lock.isEnabled());
        store.writable = true;
        PermanentNsfwDisabler secondProcess = new PermanentNsfwDisabler(store);
        require(lock.enable() && lock.isEnabled());
        require(secondProcess.isEnabled());
        require(new PermanentNsfwDisabler(store).isEnabled());
        int writes = store.writes;
        require(lock.enable() && writes == store.writes);
    }

    private static void listing() throws Exception {
        JSONObject result = new JSONObject(NsfwJsonFilter.filter("""
            {"kind":"Listing","data":{"after":"next","before":null,"children":[
              {"kind":"t3","data":{"id":"safe","over_18":false}},
              {"kind":"t3","data":{"id":"adult","over_18":true,"url":"adult.jpg"}}
            ]}}
            """));
        require(result.getJSONObject("data").getJSONArray("children").length() == 1);
        require(result.getJSONObject("data").getString("after").equals("next"));
        require(!result.toString().contains("adult.jpg"));
    }

    private static void graphql() throws Exception {
        String filtered = NsfwJsonFilter.filter("""
            {"data":{"search":{"edges":[
              {"cursor":"a","node":{"__typename":"Post","id":"safe","isNsfw":false}},
              {"cursor":"b","node":{"__typename":"Post","id":"adult","isNsfw":true}},
              {"cursor":"c","node":{"__typename":"Subreddit","over18":true}},
              {"cursor":"d","node":{"__typename":"User","subreddit":{"over18":true}}}
            ],"pageInfo":{"endCursor":"d","hasNextPage":true}}}}
            """);
        JSONObject search = new JSONObject(filtered).getJSONObject("data").getJSONObject("search");
        require(search.getJSONArray("edges").length() == 1);
        require(search.getJSONObject("pageInfo").getString("endCursor").equals("d"));
    }

    private static void direct() throws Exception {
        for (String input : new String[]{
                "{\"over_18\":true,\"id\":\"post\"}",
                "{\"kind\":\"t5\",\"data\":{\"over18\":true}}",
                "{\"kind\":\"t2\",\"data\":{\"subreddit\":{\"over_18\":true}}}"}) {
            require(NsfwJsonFilter.filter(input) == null);
        }
    }

    private static void safeContent() throws Exception {
        String input = "{\"data\":[{\"title\":\"Discussing NSFW settings\",\"spoiler\":true," +
                "\"over_18\":false,\"url\":\"safe.jpg\"}],\"nullable\":null}";
        JSONObject actual = new JSONObject(NsfwJsonFilter.filter(input));
        require(actual.similar(new JSONObject(input)));
        require(NsfwJsonFilter.filter("[]").equals("[]"));
    }

    private static void crosspost() throws Exception {
        require(NsfwJsonFilter.filter("""
            {"url":"copied.jpg","crosspost_parent_list":[{"over_18":true}]}
            """) == null);
        require(NsfwJsonFilter.filter("""
            {"kind":"t3","data":{"url":"copied.jpg","crosspost_parent_list":[{"over_18":true}]}}
            """) == null);
    }

    private static void malformed() throws Exception {
        for (String input : new String[]{"not json", "{} trailing", "[".repeat(140) + "]".repeat(140)}) {
            try {
                NsfwJsonFilter.filter(input);
                throw new AssertionError("Accepted malformed/deep input");
            } catch (org.json.JSONException expected) { }
        }
    }

    private static void disabled() throws Exception {
        String input = "{\"over_18\":true}";
        Response result = response(new RedditNsfwInterceptor(() -> false),
                "https://oauth.reddit.com/search.json?include_over_18=on", input, "application/json", null, null);
        try (result) { require(result.body().string().equals(input)); }
    }

    private static void unrelated() throws Exception {
        for (String host : new String[]{"reddit.com.example.org", "notreddit.com", "example.org"}) {
            try (Response result = response(new RedditNsfwInterceptor(() -> true),
                    "https://" + host + "/search", "{\"over_18\":true}", "application/json", null, null)) {
                require(result.body().string().contains("true"));
            }
        }
    }

    private static void transport() throws Exception {
        AtomicReference<Request> sent = new AtomicReference<>();
        try (Response result = response(new RedditNsfwInterceptor(() -> true),
                "https://oauth.reddit.com/search.json?q=cats&include_over_18=on&include_over_18=on",
                "[{\"id\":1,\"over_18\":true},{\"id\":2,\"over_18\":false}]",
                "application/json", sent, null)) {
            require(new JSONArray(result.body().string()).length() == 1);
            require(sent.get().url().queryParameterValues("include_over_18").size() == 1);
            require(sent.get().url().queryParameter("include_over_18").equals("off"));
            require(sent.get().url().queryParameter("q").equals("cats"));
            require(result.header("Content-Length") == null && result.header("ETag") == null);
        }
    }

    private static void inFlight() throws Exception {
        AtomicBoolean locked = new AtomicBoolean();
        try (Response result = response(new RedditNsfwInterceptor(locked::get),
                "https://gql.reddit.com/", "[{\"isNsfw\":true}]", "application/json",
                null, () -> locked.set(true))) {
            require(result.body().string().equals("[]"));
        }
    }

    private static void prefs() throws Exception {
        try (Response result = response(new RedditNsfwInterceptor(() -> true),
                "https://oauth.reddit.com/api/v1/me/prefs", "{\"over_18\":true,\"lang\":\"de\"}",
                "application/json", null, null)) {
            JSONObject prefs = new JSONObject(result.body().string());
            require(!prefs.getBoolean("over_18"));
            require(prefs.getString("lang").equals("de"));
        }
    }

    private static void nestedPrefs() throws Exception {
        JSONObject result = new JSONObject(NsfwJsonFilter.filter("""
            {"data":{"preferences":{"over_18":true,"search_include_over_18":true,"lang":"de"}}}
            """));
        JSONObject prefs = result.getJSONObject("data").getJSONObject("preferences");
        require(!prefs.getBoolean("over_18") && !prefs.getBoolean("search_include_over_18"));
        require(prefs.getString("lang").equals("de"));
    }

    private static void closed() throws Exception {
        for (String input : new String[]{"{\"over_18\":true}", "invalid json"}) {
            try {
                response(new RedditNsfwInterceptor(() -> true), "https://oauth.reddit.com/comments/id",
                        input, "application/json", null, null).close();
                throw new AssertionError("Uninspected response escaped");
            } catch (IOException expected) { }
        }
    }

    private static void redgifsTransport() throws Exception {
        for (String host : new String[]{"redgifs.com", "api.redgifs.com", "media.redgifs.com", "REDGIFS.COM."}) {
            AtomicReference<Request> sent = new AtomicReference<>();
            try {
                response(new RedditNsfwInterceptor(() -> true), "https://" + host + "/video",
                        "binary", "video/mp4", sent, null).close();
                throw new AssertionError("Blocked host reached transport");
            } catch (IOException expected) {
                require(sent.get() == null);
            }
            try (Response result = response(new RedditNsfwInterceptor(() -> false),
                    "https://" + host + "/video", "binary", "video/mp4", sent, null)) {
                require(result.body().string().equals("binary"));
            }
        }
        for (String host : new String[]{"notredgifs.com", "redgifs.com.example.org"}) {
            try (Response result = response(new RedditNsfwInterceptor(() -> true),
                    "https://" + host + "/video", "binary", "video/mp4", null, null)) {
                require(result.body().string().equals("binary"));
            }
        }
    }

    private static void redgifsLinks() throws Exception {
        require(NsfwJsonFilter.filter("{\"data\":{\"url\":\"https://www.redgifs.com/watch/example\"}}") == null);
        require(NsfwJsonFilter.filter("{\"domain\":\"redgifs.com\"}") == null);
        require(NsfwJsonFilter.filter("{\"url_overridden_by_dest\":\"https://media.redgifs.com/example.mp4\"}") == null);
        require(NsfwJsonFilter.filter("{\"title\":\"Discuss redgifs.com settings\",\"url\":\"https://example.org/redgifs.com\"}") != null);
        require(NsfwJsonFilter.filter("{\"url\":\"https://redgifs.com@example.org/video\"}") != null);
    }

    private static void binary() throws Exception {
        try (Response result = response(new RedditNsfwInterceptor(() -> true),
                "https://reddit.com/image.png", "binary", "image/png", null, null)) {
            require(result.body().string().equals("binary"));
        }
    }

    private static Response response(Interceptor interceptor, String url, String body, String type,
                                     AtomicReference<Request> sent, Runnable beforeResponse) throws IOException {
        Request request = new Request.Builder().url(url).build();
        Interceptor.Chain chain = (Interceptor.Chain) Proxy.newProxyInstance(
                Interceptor.Chain.class.getClassLoader(), new Class[]{Interceptor.Chain.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("request")) return request;
                    if (method.getName().equals("proceed")) {
                        Request actual = (Request) args[0];
                        if (sent != null) sent.set(actual);
                        if (beforeResponse != null) beforeResponse.run();
                        return new Response.Builder().request(actual).protocol(Protocol.HTTP_1_1)
                                .code(200).message("OK").header("Content-Length", "100")
                                .header("ETag", "original")
                                .body(ResponseBody.create(MediaType.get(type), body)).build();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return interceptor.intercept(chain);
    }

    private interface Test { void run() throws Exception; }
    private static void check(String name, Test test) throws Exception {
        test.run();
        passed++;
        System.out.println("PASS " + name);
    }
    private static void require(boolean value) {
        if (!value) throw new AssertionError("Expectation failed");
    }
}
