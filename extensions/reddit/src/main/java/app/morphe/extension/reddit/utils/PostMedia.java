/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5193
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.utils;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.reddit.domain.model.Link;
import com.reddit.domain.model.PostGallery;
import com.reddit.domain.model.PostGalleryItem;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Finds and downloads the media of posts.
 */
public final class PostMedia {

    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 15_000;
    public static final int BUFFER_SIZE = 8192;
    private static final String DEFAULT_EXTENSION = "bin";

    /**
     * @return The original post of a crosspost, which has the content, or the post itself.
     */
    public static Link getContentPost(Link link) {
        List<Link> crossPosts = link.getCrossPostParentList();
        return crossPosts != null && !crossPosts.isEmpty()
                ? crossPosts.get(0)
                : link;
    }

    /**
     * @return The download urls of the gallery items of a post, or an empty list if it's not a gallery.
     */
    public static List<String> getGalleryUrls(Link post) {
        PostGallery gallery = post.getGallery();
        List<PostGalleryItem> items = gallery == null ? null : gallery.getItems();
        if (items == null) {
            return Collections.emptyList();
        }

        List<String> urls = new ArrayList<>(items.size());
        for (PostGalleryItem item : items) {
            String url = item.getUrl();
            if (url != null && !url.trim().isEmpty()) {
                urls.add(toDownloadUrl(url.trim()));
            }
        }
        return urls;
    }

    /**
     * @return The url with a protocol and without HTML escapes.
     */
    public static String toDownloadUrl(String url) {
        if (url.startsWith("//")) {
            // Protocol-relative URL.
            url = "https:" + url;
        }
        // Reddit returns HTML-escaped gallery URLs.
        return url.replace("&amp;", "&");
    }

    /**
     * @return A connection that has an OK response.
     */
    public static HttpURLConnection openConnection(String urlString) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        conn.setReadTimeout(READ_TIMEOUT_MILLIS);
        conn.setInstanceFollowRedirects(true);

        final int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new IOException("HTTP " + responseCode + " for " + urlString);
        }
        return conn;
    }

    /**
     * @return The content type of the response without parameters, or null if there is none.
     */
    @Nullable
    public static String getContentType(HttpURLConnection conn) {
        String contentType = conn.getContentType();
        if (contentType == null) {
            return null;
        }

        final int semicolon = contentType.indexOf(';');
        if (semicolon != -1) {
            contentType = contentType.substring(0, semicolon);
        }
        contentType = contentType.trim().toLowerCase(Locale.ROOT);
        return contentType.isEmpty() ? null : contentType;
    }

    /**
     * @return The file extension for the content type, or else of the url path.
     */
    public static String getExtension(@Nullable String contentType, String urlString) {
        if (contentType != null) {
            switch (contentType) {
                case "image/jpeg":
                    return "jpg";
                case "image/png":
                    return "png";
                case "image/gif":
                    return "gif";
                case "image/webp":
                    return "webp";
                case "video/mp4":
                    return "mp4";
                default:
                    break;
            }
        }
        String extFromPath = getExtensionFromUrlPath(urlString);
        if (extFromPath != null) {
            return extFromPath;
        }
        return DEFAULT_EXTENSION;
    }

    @Nullable
    private static String getExtensionFromUrlPath(String urlString) {
        String path = Uri.parse(urlString).getPath();
        if (path != null) {
            final int lastSlash = path.lastIndexOf('/');
            String filename = lastSlash != -1 ? path.substring(lastSlash + 1) : path;
            final int lastDot = filename.lastIndexOf('.');
            if (lastDot != -1 && lastDot < filename.length() - 1) {
                return filename.substring(lastDot + 1).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    public static String getMimeTypeFromExtension(String ext) {
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "mp4" -> "video/mp4";
            default -> "application/octet-stream";
        };
    }
}
