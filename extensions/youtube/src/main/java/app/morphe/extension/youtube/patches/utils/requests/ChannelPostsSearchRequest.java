/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4078
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import android.net.Uri;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Posts of a channel that have the search.
 * <p>
 * The search tab of a channel lists no posts, so the posts tab is listed page by page,
 * newest first, and the text of each post is searched here.
 */
public final class ChannelPostsSearchRequest {

    public static final class ChannelPostResult {
        public final String postId;
        /** Text of the post, as written. */
        public final String text;
        /** Image or video of the post, or empty if it has none. */
        public final String thumbnailUrl;
        /** Number of images of the post, 0 if it has none. */
        public final int imageCount;
        /** Localized age of the post, such as "2 days ago". */
        public final String publishedTime;
        /** Localized like count, such as "1.2K likes", or empty. */
        public final String likes;
        /** Text of the post and of its attachments, which the search is matched with. */
        final String searchText;

        private ChannelPostResult(String postId, String text, String thumbnailUrl, int imageCount,
                                  String publishedTime, String likes, String searchText) {
            this.postId = postId;
            this.text = text;
            this.thumbnailUrl = thumbnailUrl;
            this.imageCount = imageCount;
            this.publishedTime = publishedTime;
            this.likes = likes;
            this.searchText = searchText;
        }
    }

    public interface ChannelPostsCallback {
        /**
         * Called on a background thread after each page of the posts tab,
         * and on the calling thread if the search is cached.
         *
         * @param posts       Posts that have the search, so far.
         * @param channelName Empty if not known.
         * @param isComplete  Whether this is the last page.
         */
        void onPostsPage(List<ChannelPostResult> posts, String channelName, boolean isComplete);
    }

    /**
     * Each page of the posts tab lists 10 posts. The tab itself ends after about 40 pages.
     */
    private static final int MAX_POSTS_TAB_PAGES = 60;

    /**
     * Parts of a response that list the posts of the tab and the next page of it.
     * The header lists other pages too, such as the about panel of the channel.
     */
    private static final String[] POST_LIST_KEYS = {
            "contents", "onResponseReceivedEndpoints", "onResponseReceivedActions"};

    /**
     * Parts of a post list that have continuations, which are not the next page of the list.
     */
    private static final String[] NOT_POST_LIST_KEYS = {
            "backstagePostThreadRenderer", "backstagePostRenderer", "postRenderer",
            "header", "engagementPanel"};

    private static final String[] POST_RENDERER_KEYS = {"backstagePostRenderer", "postRenderer"};

    /**
     * A post shared from another channel is not a post of this channel,
     * and its own text is only a comment on it.
     */
    private static final String SHARED_POST_RENDERER_KEY = "sharedPostRenderer";

    private static final int THUMBNAIL_MIN_WIDTH = 200;

    /**
     * Complete searches only.
     */
    private static final Map<String, List<ChannelPostResult>> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(10));

    private ChannelPostsSearchRequest() {
    }

    /**
     * @return Whether the post has every word of the search.
     */
    public static boolean matches(ChannelPostResult post, String query) {
        return ChannelUploadsRequest.matches(foldTurkishI(post.searchText), foldTurkishI(query));
    }

    /**
     * Dotted and dotless i are the same letter for the search, so that a search typed
     * with either keyboard finds both, such as "ISTANBUL" and "istanbul".
     * Each character is replaced by one character, so positions in the text stay the same.
     */
    public static String foldTurkishI(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0, length = text.length(); i < length; i++) {
            final char c = text.charAt(i);
            builder.append(c == 'I' || c == 'İ' || c == 'ı' ? 'i' : c);
        }
        return builder.toString();
    }

    /**
     * Adds the posts of a page of the posts tab to the list, in the order they are listed.
     *
     * @param seenPostIds Ids of the posts already in the list.
     */
    private static void collectPagePosts(JSONObject json, List<ChannelPostResult> posts, Set<String> seenPostIds) {
        for (String key : POST_LIST_KEYS) {
            collectPosts(json.opt(key), posts, seenPostIds);
        }
    }

    private static void collectPosts(@Nullable Object json, List<ChannelPostResult> posts, Set<String> seenPostIds) {
        if (json instanceof JSONObject object) {
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (SHARED_POST_RENDERER_KEY.equals(key)) continue;

                Object value = object.opt(key);
                if (isPostRendererKey(key) && value instanceof JSONObject post) {
                    ChannelPostResult result = parsePost(post);
                    if (result != null && seenPostIds.add(result.postId)) {
                        posts.add(result);
                    }
                    continue;
                }
                collectPosts(value, posts, seenPostIds);
            }
        } else if (json instanceof JSONArray array) {
            for (int i = 0, length = array.length(); i < length; i++) {
                collectPosts(array.opt(i), posts, seenPostIds);
            }
        }
    }

    private static boolean isPostRendererKey(String key) {
        for (String postKey : POST_RENDERER_KEYS) {
            if (postKey.equals(key)) return true;
        }
        return false;
    }

    @Nullable
    private static ChannelPostResult parsePost(JSONObject post) {
        String postId = post.optString("postId");
        if (postId.isEmpty()) {
            return null;
        }

        String text = parseText(post.optJSONObject("contentText"));
        String publishedTime = parseText(post.optJSONObject("publishedTimeText"));

        JSONObject voteCount = post.optJSONObject("voteCount");
        String likes = accessibilityLabel(voteCount);
        if (likes.isEmpty()) {
            likes = parseText(voteCount);
        }

        StringBuilder searchText = new StringBuilder(text);
        String thumbnailUrl = "";
        int imageCount = 0;

        JSONObject attachment = post.optJSONObject("backstageAttachment");
        if (attachment != null) {
            JSONObject image = attachment.optJSONObject("backstageImageRenderer");
            JSONObject multiImage = attachment.optJSONObject("postMultiImageRenderer");
            JSONObject video = attachment.optJSONObject("videoRenderer");
            JSONObject poll = attachment.optJSONObject("pollRenderer");

            if (image != null) {
                imageCount = 1;
                thumbnailUrl = imageUrl(image);
            } else if (multiImage != null) {
                JSONArray images = multiImage.optJSONArray("images");
                if (images != null) {
                    imageCount = images.length();
                    JSONObject first = images.optJSONObject(0);
                    if (first != null) {
                        JSONObject firstImage = first.optJSONObject("backstageImageRenderer");
                        if (firstImage != null) thumbnailUrl = imageUrl(firstImage);
                    }
                }
            } else if (video != null) {
                JSONObject thumbnail = video.optJSONObject("thumbnail");
                if (thumbnail != null) {
                    thumbnailUrl = ChannelSearchRequest.widestThumbnail(thumbnail.optJSONArray("thumbnails"));
                }
                appendSearchText(searchText, parseText(video.optJSONObject("title")));
            } else if (poll != null) {
                JSONArray choices = poll.optJSONArray("choices");
                if (choices != null) {
                    for (int i = 0, length = choices.length(); i < length; i++) {
                        JSONObject choice = choices.optJSONObject(i);
                        if (choice != null) {
                            appendSearchText(searchText, parseText(choice.optJSONObject("text")));
                        }
                    }
                }
            }
        }

        return new ChannelPostResult(postId, text.trim(), thumbnailUrl, imageCount,
                publishedTime, likes, searchText.toString());
    }

    private static void appendSearchText(StringBuilder searchText, String value) {
        if (!value.isEmpty()) {
            searchText.append('\n').append(value);
        }
    }

    /**
     * The smallest image that is still sharp in a row, as images of posts are listed up to
     * their original size.
     */
    private static String imageUrl(JSONObject imageRenderer) {
        JSONObject image = imageRenderer.optJSONObject("image");
        JSONArray thumbnails = image == null ? null : image.optJSONArray("thumbnails");
        if (thumbnails == null || thumbnails.length() == 0) {
            return "";
        }

        String best = "";
        int bestWidth = Integer.MAX_VALUE;
        String widest = "";
        int widestWidth = -1;
        for (int i = 0, length = thumbnails.length(); i < length; i++) {
            JSONObject entry = thumbnails.optJSONObject(i);
            if (entry == null) continue;
            String url = entry.optString("url");
            final int width = entry.optInt("width");
            if (url.isEmpty()) continue;
            if (width >= THUMBNAIL_MIN_WIDTH && width < bestWidth) {
                best = url;
                bestWidth = width;
            }
            if (width > widestWidth) {
                widest = url;
                widestWidth = width;
            }
        }
        String url = best.isEmpty() ? widest : best;
        return url.startsWith("//") ? "https:" + url : url;
    }

    /**
     * Searches the posts tab of the channel. Does nothing if the search is cancelled.
     */
    public static void fetchPostsTab(String channelId, String query, BooleanSupplier isCancelled,
                                     ChannelPostsCallback callback) {
        String key = channelId + "\n" + query;
        List<ChannelPostResult> cached = cache.get(key);
        if (cached != null) {
            callback.onPostsPage(cached, "", true);
            return;
        }

        Utils.runOnBackgroundThread(() -> {
            List<ChannelPostResult> posts = searchPostsTab(channelId, query, isCancelled, callback);
            if (posts != null && !isCancelled.getAsBoolean()) {
                cache.put(key, posts);
            }
        });
    }

    /**
     * @return The posts that have the search, or null if the search did not complete.
     */
    @Nullable
    private static List<ChannelPostResult> searchPostsTab(String channelId, String query,
                                                          BooleanSupplier isCancelled,
                                                          ChannelPostsCallback callback) {
        Utils.verifyOffMainThread();

        final long startTime = System.currentTimeMillis();
        Locale locale = Requester.getAppLocale();
        List<ChannelPostResult> matches = new ArrayList<>();
        List<ChannelPostResult> pagePosts = new ArrayList<>();
        Set<String> seenPostIds = new HashSet<>();
        int scannedPosts = 0;
        int page = 0;

        try {
            JSONObject json = ChannelSearchRoutes.fetch(ChannelSearchRoutes.CHANNEL_SEARCH,
                    ChannelSearchRoutes.createPostsTabBody(channelId, ChannelSearchRoutes.POSTS_TAB_PARAMS, locale));
            if (json == null) {
                Logger.printDebug(() -> "Posts tab could not be fetched: " + channelId);
                callback.onPostsPage(matches, "", true);
                return null;
            }

            // The tabs of the response tell the params of the posts tab, if the ones sent were not taken.
            String postsTabParams = findPostsTabParams(json);
            if (postsTabParams == null) {
                Logger.printDebug(() -> "Channel has no posts tab: " + channelId);
                callback.onPostsPage(matches, parseChannelName(json), true);
                return matches;
            }
            if (!isPostsTabSelected(json) && !postsTabParams.isEmpty()) {
                Logger.printDebug(() -> "Posts tab params of the response: " + postsTabParams);
                json = ChannelSearchRoutes.fetch(ChannelSearchRoutes.CHANNEL_SEARCH,
                        ChannelSearchRoutes.createPostsTabBody(channelId, postsTabParams, locale));
                if (json == null) {
                    callback.onPostsPage(matches, "", true);
                    return null;
                }
            }

            String channelName = parseChannelName(json);

            for (page = 1; ; page++) {
                pagePosts.clear();
                collectPagePosts(json, pagePosts, seenPostIds);
                scannedPosts += pagePosts.size();
                for (ChannelPostResult post : pagePosts) {
                    if (matches(post, query)) {
                        matches.add(post);
                    }
                }

                String nextToken = findNextPageToken(json);
                final boolean isLast = nextToken == null || page >= MAX_POSTS_TAB_PAGES
                        // A page without new posts would only repeat.
                        || (page > 1 && pagePosts.isEmpty());
                callback.onPostsPage(new ArrayList<>(matches), channelName, isLast);
                if (isLast) {
                    return matches;
                }
                if (isCancelled.getAsBoolean()) {
                    Logger.printDebug(() -> "Posts search cancelled");
                    return null;
                }

                json = ChannelSearchRoutes.fetch(ChannelSearchRoutes.CHANNEL_SEARCH,
                        ChannelSearchRoutes.createUploadsContinuationBody(nextToken, locale));
                if (json == null) {
                    // A page that could not be fetched ends the search with the posts so far.
                    callback.onPostsPage(new ArrayList<>(matches), channelName, true);
                    return null;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "searchPostsTab failure", ex);
            callback.onPostsPage(new ArrayList<>(matches), "", true);
            return null;
        } finally {
            final int pages = page;
            final int scanned = scannedPosts;
            Logger.printDebug(() -> "Searched posts tab, pages=" + pages + " posts=" + scanned
                    + " matches=" + matches.size() + " took: " + (System.currentTimeMillis() - startTime) + "ms");
        }
    }

    /**
     * @return The params of the posts tab, empty if the tab has none,
     *         or null if the channel has no posts tab.
     */
    @Nullable
    private static String findPostsTabParams(JSONObject json) {
        JSONArray tabs = findTabs(json);
        if (tabs == null) {
            // Not a tab list, such as a page that is only the posts.
            return "";
        }
        for (int i = 0, length = tabs.length(); i < length; i++) {
            JSONObject tab = tabs.optJSONObject(i);
            JSONObject tabRenderer = tab == null ? null : tab.optJSONObject("tabRenderer");
            if (tabRenderer == null || !isPostsTab(tabRenderer)) continue;

            JSONObject endpoint = tabRenderer.optJSONObject("endpoint");
            JSONObject browse = endpoint == null ? null : endpoint.optJSONObject("browseEndpoint");
            // The params of a tab are url encoded, such as "%3D" for "=".
            return browse == null ? "" : Uri.decode(browse.optString("params"));
        }
        return null;
    }

    private static boolean isPostsTabSelected(JSONObject json) {
        JSONArray tabs = findTabs(json);
        if (tabs == null) {
            return true;
        }
        for (int i = 0, length = tabs.length(); i < length; i++) {
            JSONObject tab = tabs.optJSONObject(i);
            JSONObject tabRenderer = tab == null ? null : tab.optJSONObject("tabRenderer");
            if (tabRenderer != null && tabRenderer.optBoolean("selected")) {
                return isPostsTab(tabRenderer);
            }
        }
        return false;
    }

    private static boolean isPostsTab(JSONObject tabRenderer) {
        JSONObject endpoint = tabRenderer.optJSONObject("endpoint");
        if (endpoint == null) return false;
        JSONObject commandMetadata = endpoint.optJSONObject("commandMetadata");
        JSONObject web = commandMetadata == null ? null : commandMetadata.optJSONObject("webCommandMetadata");
        String url = web == null ? "" : web.optString("url");
        return url.endsWith("/posts") || url.endsWith("/community");
    }

    @Nullable
    private static JSONArray findTabs(JSONObject json) {
        JSONObject contents = json.optJSONObject("contents");
        if (contents == null) return null;
        for (String key : new String[]{"twoColumnBrowseResultsRenderer", "singleColumnBrowseResultsRenderer"}) {
            JSONObject renderer = contents.optJSONObject(key);
            if (renderer != null) return renderer.optJSONArray("tabs");
        }
        return null;
    }

    /**
     * @return Token of the next page of the posts tab, which is listed after the posts of the page.
     */
    @Nullable
    private static String findNextPageToken(JSONObject json) {
        String token = null;
        for (String key : POST_LIST_KEYS) {
            String found = findContinuationToken(json.opt(key));
            if (found != null) token = found;
        }
        return token;
    }

    @Nullable
    private static String findContinuationToken(@Nullable Object json) {
        String token = null;
        if (json instanceof JSONObject object) {
            JSONObject continuation = object.optJSONObject("continuationItemRenderer");
            if (continuation != null) {
                String found = commandToken(continuation);
                if (found != null) token = found;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("continuationItemRenderer".equals(key) || isNotPostListKey(key)) continue;
                String found = findContinuationToken(object.opt(key));
                if (found != null) token = found;
            }
        } else if (json instanceof JSONArray array) {
            for (int i = 0, length = array.length(); i < length; i++) {
                String found = findContinuationToken(array.opt(i));
                if (found != null) token = found;
            }
        }
        return token;
    }

    private static boolean isNotPostListKey(String key) {
        for (String notPostListKey : NOT_POST_LIST_KEYS) {
            if (notPostListKey.equals(key)) return true;
        }
        return false;
    }

    @Nullable
    private static String commandToken(JSONObject continuationItemRenderer) {
        JSONObject endpoint = continuationItemRenderer.optJSONObject("continuationEndpoint");
        if (endpoint == null) return null;
        JSONObject command = endpoint.optJSONObject("continuationCommand");
        if (command == null) return null;
        String token = command.optString("token");
        return token.isEmpty() ? null : token;
    }

    private static String parseChannelName(JSONObject json) {
        JSONObject metadata = json.optJSONObject("metadata");
        JSONObject channel = metadata == null ? null : metadata.optJSONObject("channelMetadataRenderer");
        return channel == null ? "" : channel.optString("title");
    }

    private static String accessibilityLabel(@Nullable JSONObject text) {
        if (text == null) return "";
        JSONObject accessibility = text.optJSONObject("accessibility");
        JSONObject data = accessibility == null ? null : accessibility.optJSONObject("accessibilityData");
        return data == null ? "" : data.optString("label");
    }

    /**
     * Text is either a plain string or a list of runs, depending on the field.
     */
    private static String parseText(@Nullable JSONObject text) {
        if (text == null) {
            return "";
        }

        String simpleText = text.optString("simpleText");
        if (!simpleText.isEmpty()) {
            return simpleText;
        }

        JSONArray runs = text.optJSONArray("runs");
        if (runs == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0, length = runs.length(); i < length; i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) {
                builder.append(run.optString("text"));
            }
        }
        return builder.toString();
    }
}
