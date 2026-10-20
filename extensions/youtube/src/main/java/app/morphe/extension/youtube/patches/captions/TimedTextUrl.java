/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.captions;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Validates YouTube timedtext URLs without changing their signed query bytes. */
final class TimedTextUrl {
    private final String url;
    private final URI uri;
    private final Map<String, String> fields;

    private TimedTextUrl(String url, URI uri, Map<String, String> fields) {
        this.url = url;
        this.uri = uri;
        this.fields = fields;
    }

    static URI trustedUri(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || !("youtube.com".equalsIgnoreCase(host)
                    || host.toLowerCase(Locale.ROOT).endsWith(".youtube.com"))
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !"/api/timedtext".equals(uri.getRawPath())
                    || uri.getRawQuery() == null) return null;
            return uri;
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return null;
        }
    }

    static TimedTextUrl parse(String url) {
        URI uri = trustedUri(url);
        return uri == null ? null : parse(url, uri);
    }

    static TimedTextUrl parse(String url, URI uri) {
        try {
            Map<String, String> fields = new HashMap<>();
            for (String part : uri.getRawQuery().split("&", -1)) {
                int equals = part.indexOf('=');
                String rawKey = equals < 0 ? part : part.substring(0, equals);
                String key = URLDecoder.decode(rawKey, "UTF-8");
                if (!key.equals("v") && !key.equals("lang") && !key.equals("kind")
                        && !key.equals("name") && !key.equals("tlang")) continue;
                if (!key.equals(rawKey) || fields.containsKey(key)) return null;
                String rawValue = equals < 0 ? "" : part.substring(equals + 1);
                fields.put(key, URLDecoder.decode(rawValue, "UTF-8"));
            }
            return new TimedTextUrl(url, uri, fields);
        } catch (IllegalArgumentException | NullPointerException | java.io.UnsupportedEncodingException ignored) {
            return null;
        }
    }

    String field(String name) {
        return fields.get(name);
    }

    boolean hasQueryContent() {
        return !uri.getRawQuery().isEmpty();
    }

    /** Stable source identity across different native tlang values; keep every other signed byte. */
    String sourceIdentity(String target) {
        int offset = targetValueOffset(target);
        return offset < 0 ? null : url.substring(0, offset) + '\0'
                + url.substring(offset + target.length());
    }

    /** Offset of the exact raw target value; never match opaque values or URL fragments. */
    int targetValueOffset(String expected) {
        int question = url.indexOf('?');
        if (question < 0) return -1;
        int end = url.indexOf('#', question + 1);
        if (end < 0) end = url.length();
        int offset = question + 1;
        for (String part : url.substring(offset, end).split("&", -1)) {
            if (part.equals("tlang=" + expected)) return offset + "tlang=".length();
            offset += part.length() + 1;
        }
        return -1;
    }
}
