package app.morphe.extension.reddit.patches.nsfw;

import org.json.JSONException;

import java.io.IOException;
import java.io.Reader;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Candidate OkHttp boundary; does not claim to cover Cronet, WebViews or local databases. */
public final class RedditNsfwInterceptor implements Interceptor {
    private static final long MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final int MAX_JSON_CHARACTERS = 8 * 1024 * 1024;
    private final BooleanSupplier enabled;

    public RedditNsfwInterceptor(BooleanSupplier enabled) {
        this.enabled = enabled;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        if (enabled.getAsBoolean() && BlockedNsfwHosts.isRedgifsHost(request.url().host())) {
            throw new IOException("Content unavailable");
        }
        if (!isRedditHost(request.url().host())) return chain.proceed(request);

        if (enabled.getAsBoolean()) request = restrictSearch(request);
        Response response = chain.proceed(request);
        // Check again: activation may have happened while the request was in flight.
        if (!enabled.getAsBoolean()) return response;

        ResponseBody body = response.body();
        if (body == null) return response;
        MediaType contentType = body.contentType();
        if (contentType == null || !contentType.subtype().toLowerCase(Locale.ROOT).contains("json")) {
            return response;
        }

        try (body) {
            if (response.header("Content-Encoding") != null) {
                throw new IOException("Cannot inspect encoded Reddit JSON");
            }
            String input = readJson(body);
            // Preferences aren't content entities. Do not classify an account preference as a post.
            if (request.url().encodedPath().equals("/api/v1/me/prefs")) {
                org.json.JSONObject prefs = new org.json.JSONObject(input);
                prefs.put("over_18", false);
                prefs.put("search_include_over_18", false);
                input = prefs.toString();
            }
            String filtered = NsfwJsonFilter.filter(input);
            if (filtered == null) throw new IOException("Content unavailable");
            return response.newBuilder()
                    .removeHeader("Content-Length")
                    .removeHeader("Transfer-Encoding")
                    .removeHeader("ETag")
                    .body(ResponseBody.create(contentType, filtered))
                    .build();
        } catch (JSONException e) {
            // Never return the uninspected original JSON after a filtering failure.
            throw new IOException("Cannot inspect Reddit content", e);
        }
    }

    private static String readJson(ResponseBody body) throws IOException {
        if (body.contentLength() > MAX_JSON_BYTES) throw new IOException("Reddit JSON exceeds filter limit");
        // Reddit obfuscates Okio. Keep all extension calls on OkHttp's stable Java APIs.
        try (Reader reader = body.charStream()) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (text.length() + count > MAX_JSON_CHARACTERS) {
                    throw new IOException("Reddit JSON exceeds filter limit");
                }
                text.append(buffer, 0, count);
            }
            return text.toString();
        }
    }

    static boolean isRedditHost(String host) {
        return "reddit.com".equals(host) || host.endsWith(".reddit.com");
    }

    private static Request restrictSearch(Request request) {
        String path = request.url().encodedPath();
        HttpUrl.Builder url = request.url().newBuilder();
        boolean changed = false;
        for (String key : new String[]{"include_over_18", "include_nsfw"}) {
            if (request.url().queryParameter(key) != null) {
                url.setQueryParameter(key, "false");
                changed = true;
            }
        }
        if (path.equals("/search") || path.equals("/search.json") || path.endsWith("/search.json")) {
            url.setQueryParameter("include_over_18", "off");
            changed = true;
        }
        return changed ? request.newBuilder().url(url.build()).build() : request;
    }
}
