package app.morphe.extension.reddit.patches.archive;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Optional presentation data. A malformed preview never makes the text feed unusable. */
public final class ArchivePostDetails {
    private static final Set<String> IMAGE_HOSTS = Set.of("i.redd.it", "preview.redd.it", "external-preview.redd.it");
    public final String title, author, subreddit, flair;
    public final Long commentCount;
    public final boolean nsfw, spoiler, video;
    public final int galleryCount;
    public final Preview preview;
    public final List<Preview> gallery;

    private ArchivePostDetails(JSONObject row, Preview preview, int galleryCount, List<Preview> gallery) {
        title = text(row, "title"); author = text(row, "author"); subreddit = text(row, "subreddit");
        flair = text(row, "link_flair_text"); commentCount = count(row, "num_comments");
        nsfw = row.optBoolean("over_18"); spoiler = row.optBoolean("spoiler");
        video = row.optBoolean("is_video") || text(row, "post_hint").contains("video");
        this.preview = preview; this.galleryCount = galleryCount;
        this.gallery = Collections.unmodifiableList(gallery);
    }

    static ArchivePostDetails parse(JSONObject row) {
        Preview preview = null;
        int galleryCount = 0;
        List<Preview> galleryPreviews = new ArrayList<>();
        JSONObject gallery = row.optJSONObject("gallery_data"), metadata = row.optJSONObject("media_metadata");
        JSONArray galleryItems = gallery == null ? null : gallery.optJSONArray("items");
        if (galleryItems != null) {
            galleryCount = Math.min(100, galleryItems.length());
            // Preserve gallery order. Never substitute a later image for the cover.
            JSONObject first = galleryItems.optJSONObject(0);
            JSONObject media = first == null || metadata == null ? null : metadata.optJSONObject(first.optString("media_id"));
            if (media != null) preview = choose(media.optJSONArray("p"), media.optJSONObject("s"), "u", "x", "y");
            for (int i = 0; i < galleryCount; i++) {
                JSONObject entry = galleryItems.optJSONObject(i);
                JSONObject image = entry == null || metadata == null ? null : metadata.optJSONObject(entry.optString("media_id"));
                galleryPreviews.add(image == null ? null : choose(image.optJSONArray("p"), image.optJSONObject("s"), "u", "x", "y"));
            }
        }
        if (preview == null) {
            JSONObject previews = row.optJSONObject("preview");
            JSONArray images = previews == null ? null : previews.optJSONArray("images");
            JSONObject first = images == null ? null : images.optJSONObject(0);
            if (first != null) preview = choose(first.optJSONArray("resolutions"), first.optJSONObject("source"), "url", "width", "height");
        }
        if (preview == null) {
            String thumbnail = imageUrl(text(row, "thumbnail"));
            if (thumbnail != null) preview = new Preview(thumbnail, dimension(row, "thumbnail_width"), dimension(row, "thumbnail_height"));
        }
        if (preview == null && "image".equals(text(row, "post_hint"))) {
            String url = imageUrl(text(row, "url"));
            if (url != null) preview = new Preview(url, 0, 0);
        }
        return new ArchivePostDetails(row, preview, galleryCount, galleryPreviews);
    }

    private static Preview choose(JSONArray variants, JSONObject source, String urlKey, String widthKey, String heightKey) {
        Preview best = null;
        if (variants != null) {
            for (int i = 0; i < Math.min(variants.length(), 32); i++) {
                Preview candidate = candidate(variants.optJSONObject(i), urlKey, widthKey, heightKey);
                if (candidate == null) continue;
                if (best == null || (best.width < 1080 && candidate.width > best.width)
                        || (candidate.width >= 1080 && candidate.width < best.width)) best = candidate;
            }
        }
        Preview original = candidate(source, urlKey, widthKey, heightKey);
        if (best == null) return original;
        return best;
    }

    private static Preview candidate(JSONObject row, String urlKey, String widthKey, String heightKey) {
        if (row == null) return null;
        String url = imageUrl(text(row, urlKey));
        int width = dimension(row, widthKey), height = dimension(row, heightKey);
        return url == null || width == 0 || height == 0 ? null : new Preview(url, width, height);
    }

    private static int dimension(JSONObject row, String key) {
        int value = row.optInt(key, 0);
        return value > 0 && value <= 32768 ? value : 0;
    }

    public static String imageUrl(String value) {
        if (value == null || value.length() > 4096) return null;
        value = value.replace("&amp;", "&");
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !IMAGE_HOSTS.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))
                    || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null) return null;
            return uri.toASCIIString();
        } catch (Exception ignored) { return null; }
    }

    static Long count(JSONObject row, String key) {
        Object value = row.opt(key);
        return value instanceof Number && ((Number) value).longValue() >= 0 ? ((Number) value).longValue() : null;
    }

    private static String text(JSONObject row, String key) {
        Object value = row.opt(key);
        return value instanceof String ? ((String) value).substring(0, Math.min(((String) value).length(), 4096)) : "";
    }

    public int characters() {
        int size = title.length() + author.length() + subreddit.length() + flair.length() + (preview == null ? 0 : preview.url.length());
        for (Preview image : gallery) if (image != null) size += image.url.length();
        return size;
    }

    public static final class Preview {
        public final String url;
        public final int width, height;
        Preview(String url, int width, int height) { this.url = url; this.width = width; this.height = height; }
    }
}
