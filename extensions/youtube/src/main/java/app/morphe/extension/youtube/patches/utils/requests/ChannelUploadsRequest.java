/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3684
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.patches.utils.requests.ChannelSearchRequest.ChannelSearchResult;

/**
 * The uploads of a channel, from the playlist that lists all of them newest first,
 * and the uploads whose title has every word of a search.
 * <p>
 * The channel search stops after a different number of pages every time and leaves out
 * different videos, so a sort of its results never shows the same first videos twice.
 * The uploads playlist always lists every video, Short and live stream in the order they were
 * published, so it makes the results of a sort complete and sorts them by date exactly.
 */
public final class ChannelUploadsRequest {

    public static final class Uploads {
        /** Uploads whose title has the search, newest first. */
        public final List<ChannelSearchResult> matches;
        /**
         * Position of every listed upload, from 0 for the newest one. The largest channels
         * have more uploads than are listed, which are all older.
         */
        public final Map<String, Integer> positions;
        /**
         * Rounded view count of the listed uploads, such as 8.5 million for "8.5M views".
         * Used for uploads whose exact view count could not be fetched.
         */
        public final Map<String, Long> roundedViewCounts;

        /**
         * No uploads, until they are listed.
         */
        public Uploads() {
            this(Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap());
        }

        private Uploads(List<ChannelSearchResult> matches, Map<String, Integer> positions,
                        Map<String, Long> roundedViewCounts) {
            this.matches = matches;
            this.positions = positions;
            this.roundedViewCounts = roundedViewCounts;
        }
    }

    public interface Callback {
        /**
         * Called on a background thread with the uploads listed so far.
         *
         * @param isComplete Whether the playlist was listed, or could not be.
         */
        void onUploads(Uploads uploads, boolean isComplete);
    }

    /**
     * Each page has 100 uploads, so only the largest channels are partly listed.
     */
    private static final int MAX_PAGES = 200;

    private static final Map<String, Uploads> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(10));

    private ChannelUploadsRequest() {
    }

    /**
     * Calls back after each page that has more matching uploads, and once more when done.
     */
    public static void fetch(String channelId, String query, BooleanSupplier isCancelled, Callback callback) {
        Locale locale = Requester.getAppLocale();
        String key = channelId + "\n" + query + "\n" + locale;

        Uploads cached = cache.get(key);
        if (cached != null) {
            Utils.runOnBackgroundThread(() -> callback.onUploads(cached, true));
            return;
        }

        Utils.runOnBackgroundThread(() -> {
            Uploads uploads = fetchUploads(channelId, query, locale, isCancelled, callback);
            if (uploads == null) {
                callback.onUploads(new Uploads(), true);
            } else if (!isCancelled.getAsBoolean()) {
                cache.put(key, uploads);
                callback.onUploads(uploads, true);
            }
        });
    }

    /**
     * Titles can be translated to the language of the app, and a search is often in English,
     * so an upload matches if either its title in the language of the app or in English does.
     * The view counts are parsed from the English uploads, as their format depends on the language.
     */
    @Nullable
    private static Uploads fetchUploads(String channelId, String query, Locale locale,
                                        BooleanSupplier isCancelled, Callback callback) {
        Utils.verifyOffMainThread();

        final long startTime = System.currentTimeMillis();
        List<String> queryWords = normalizedWords(query);
        List<ChannelSearchResult> uploads = new ArrayList<>();
        List<ChannelSearchResult> matches = new ArrayList<>();
        Map<String, Integer> positions = new HashMap<>();

        Map<String, String> englishTitles = new ConcurrentHashMap<>();
        Map<String, Long> roundedViewCounts = new ConcurrentHashMap<>();
        final boolean isEnglish = "en".equals(locale.getLanguage());
        Future<Boolean> englishFuture = isEnglish ? null
                : Utils.submitOnBackgroundThread(() -> listUploads(channelId, Locale.US, roundedViewCounts,
                        isCancelled, page -> {
                            for (ChannelSearchResult upload : page) {
                                englishTitles.put(upload.videoId, upload.title);
                            }
                        }));

        try {
            final boolean isListed = listUploads(channelId, locale, isEnglish ? roundedViewCounts : null,
                    isCancelled, page -> {
                boolean foundMatches = false;
                for (ChannelSearchResult upload : page) {
                    if (positions.containsKey(upload.videoId)) continue;

                    positions.put(upload.videoId, positions.size());
                    uploads.add(upload);
                    if (matches(upload, queryWords, englishTitles)) {
                        matches.add(upload);
                        foundMatches = true;
                    }
                }
                if (foundMatches) {
                    callback.onUploads(new Uploads(new ArrayList<>(matches), new HashMap<>(positions),
                            new HashMap<>(roundedViewCounts)), false);
                }
            });
            if (!isListed || isCancelled.getAsBoolean()) {
                return null;
            }

            if (englishFuture != null) {
                try {
                    englishFuture.get();
                } catch (Exception ex) {
                    Logger.printInfo(() -> "English channel uploads failed", ex);
                }
                // Uploads listed before their English title can match it now.
                matches.clear();
                for (ChannelSearchResult upload : uploads) {
                    if (matches(upload, queryWords, englishTitles)) {
                        matches.add(upload);
                    }
                }
            }
            return new Uploads(matches, positions, roundedViewCounts);
        } catch (Exception ex) {
            Logger.printException(() -> "Channel uploads failed", ex);
        } finally {
            Logger.printDebug(() -> "Fetched channel uploads: " + positions.size() + " matching: " + query
                    + ", items=" + matches.size() + " took: " + (System.currentTimeMillis() - startTime) + "ms");
        }
        return null;
    }

    private static boolean matches(ChannelSearchResult upload, List<String> queryWords,
                                   Map<String, String> englishTitles) {
        if (matches(upload.title, queryWords)) {
            return true;
        }
        String englishTitle = englishTitles.get(upload.videoId);
        return englishTitle != null && matches(englishTitle, queryWords);
    }

    /**
     * @param roundedViewCounts Filled with the view count of each upload, if not null.
     *                          Only English view counts can be parsed.
     * @param onPage            Called with the uploads of each page, newest first.
     * @return Whether the uploads could be listed. A page after the first one that could not
     *         be fetched leaves the older uploads out.
     */
    private static boolean listUploads(String channelId, Locale locale,
                                       @Nullable Map<String, Long> roundedViewCounts,
                                       BooleanSupplier isCancelled, Consumer<List<ChannelSearchResult>> onPage) {
        JSONObject json = ChannelSearchRoutes.fetch(ChannelSearchRoutes.CHANNEL_SEARCH,
                ChannelSearchRoutes.createUploadsBody(channelId, locale));
        JSONArray items = json == null ? null : playlistItems(json);
        if (items == null) {
            return false;
        }

        for (int pageNumber = 1; ; pageNumber++) {
            if (isCancelled.getAsBoolean()) {
                return false;
            }

            String token = null;
            List<ChannelSearchResult> page = new ArrayList<>(items.length());
            for (int i = 0, length = items.length(); i < length; i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;

                JSONObject continuation = item.optJSONObject("continuationItemViewModel");
                if (continuation != null) {
                    token = continuationToken(continuation);
                    continue;
                }

                ChannelSearchResult upload = parseLockup(item.optJSONObject("lockupViewModel"), roundedViewCounts);
                if (upload != null) {
                    page.add(upload);
                }
            }
            onPage.accept(page);

            if (token == null || pageNumber == MAX_PAGES) {
                return true;
            }

            json = ChannelSearchRoutes.fetch(ChannelSearchRoutes.CHANNEL_SEARCH,
                    ChannelSearchRoutes.createUploadsContinuationBody(token, locale));
            items = json == null ? null : continuationItems(json);
            if (items == null) {
                return true;
            }
        }
    }

    @Nullable
    private static JSONArray playlistItems(JSONObject json) {
        JSONObject contents = json.optJSONObject("contents");
        JSONObject twoColumn = contents == null ? null
                : contents.optJSONObject("twoColumnBrowseResultsRenderer");
        JSONArray tabs = twoColumn == null ? null : twoColumn.optJSONArray("tabs");
        JSONObject tab = tabs == null ? null : tabs.optJSONObject(0);
        JSONObject tabRenderer = tab == null ? null : tab.optJSONObject("tabRenderer");
        JSONObject content = tabRenderer == null ? null : tabRenderer.optJSONObject("content");
        JSONObject sectionList = content == null ? null : content.optJSONObject("sectionListRenderer");
        JSONArray sections = sectionList == null ? null : sectionList.optJSONArray("contents");
        if (sections == null) {
            return null;
        }

        for (int i = 0, length = sections.length(); i < length; i++) {
            JSONObject section = sections.optJSONObject(i);
            JSONObject itemSection = section == null ? null : section.optJSONObject("itemSectionRenderer");
            JSONArray items = itemSection == null ? null : itemSection.optJSONArray("contents");
            if (items != null) {
                return items;
            }
        }
        return null;
    }

    @Nullable
    private static JSONArray continuationItems(JSONObject json) {
        JSONArray actions = json.optJSONArray("onResponseReceivedActions");
        if (actions == null) {
            return null;
        }

        for (int i = 0, length = actions.length(); i < length; i++) {
            JSONObject action = actions.optJSONObject(i);
            JSONObject append = action == null ? null : action.optJSONObject("appendContinuationItemsAction");
            if (append != null) {
                return append.optJSONArray("continuationItems");
            }
        }
        return null;
    }

    @Nullable
    private static String continuationToken(JSONObject continuationItemViewModel) {
        JSONObject continuationCommand = continuationItemViewModel.optJSONObject("continuationCommand");
        JSONObject command = continuationCommand == null ? null
                : continuationCommand.optJSONObject("innertubeCommand");
        JSONObject continuation = command == null ? null : command.optJSONObject("continuationCommand");
        String token = continuation == null ? "" : continuation.optString("token");
        return token.isEmpty() ? null : token;
    }

    @Nullable
    private static ChannelSearchResult parseLockup(@Nullable JSONObject lockup,
                                                   @Nullable Map<String, Long> roundedViewCounts) {
        if (lockup == null) {
            return null;
        }

        String videoId = lockup.optString("contentId");
        JSONObject metadata = lockup.optJSONObject("metadata");
        JSONObject lockupMetadata = metadata == null ? null : metadata.optJSONObject("lockupMetadataViewModel");
        String title = lockupMetadata == null ? "" : textContent(lockupMetadata.optJSONObject("title"));
        if (videoId.isEmpty() || title.isEmpty()) {
            return null;
        }

        JSONObject contentImage = lockup.optJSONObject("contentImage");
        JSONObject thumbnail = contentImage == null ? null : contentImage.optJSONObject("thumbnailViewModel");
        List<String> details = parseDetails(lockupMetadata);
        if (roundedViewCounts != null && !details.isEmpty() && details.get(0).contains("view")) {
            roundedViewCounts.put(videoId, ChannelSearchRequest.parseViewCount("", details.get(0)));
        }

        return ChannelSearchRequest.createResult(videoId, title, parseThumbnail(thumbnail),
                parseLength(thumbnail), details);
    }

    private static String parseThumbnail(@Nullable JSONObject thumbnail) {
        JSONObject image = thumbnail == null ? null : thumbnail.optJSONObject("image");
        return image == null ? "" : ChannelSearchRequest.widestThumbnail(image.optJSONArray("sources"));
    }

    /**
     * @return The length badge on the thumbnail, such as "17:31".
     */
    private static String parseLength(@Nullable JSONObject thumbnail) {
        JSONArray overlays = thumbnail == null ? null : thumbnail.optJSONArray("overlays");
        if (overlays == null) {
            return "";
        }

        for (int i = 0, length = overlays.length(); i < length; i++) {
            JSONObject overlay = overlays.optJSONObject(i);
            JSONObject bottomOverlay = overlay == null ? null
                    : overlay.optJSONObject("thumbnailBottomOverlayViewModel");
            JSONArray badges = bottomOverlay == null ? null : bottomOverlay.optJSONArray("badges");
            JSONObject badge = badges == null ? null : badges.optJSONObject(0);
            JSONObject badgeViewModel = badge == null ? null : badge.optJSONObject("thumbnailBadgeViewModel");
            if (badgeViewModel != null) {
                return badgeViewModel.optString("text");
            }
        }
        return "";
    }

    /**
     * @return The view count and the age of the upload, as shown under the title.
     *         The first row is the channel name, which is left out.
     */
    private static List<String> parseDetails(JSONObject lockupMetadata) {
        List<String> details = new ArrayList<>();
        JSONObject metadata = lockupMetadata.optJSONObject("metadata");
        JSONObject contentMetadata = metadata == null ? null : metadata.optJSONObject("contentMetadataViewModel");
        JSONArray rows = contentMetadata == null ? null : contentMetadata.optJSONArray("metadataRows");
        if (rows == null) {
            return details;
        }

        for (int i = 1, length = rows.length(); i < length; i++) {
            JSONObject row = rows.optJSONObject(i);
            JSONArray parts = row == null ? null : row.optJSONArray("metadataParts");
            if (parts == null) continue;

            for (int j = 0, partsLength = parts.length(); j < partsLength; j++) {
                JSONObject part = parts.optJSONObject(j);
                String text = part == null ? "" : textContent(part.optJSONObject("text"));
                if (!text.isEmpty()) {
                    details.add(text);
                }
            }
        }
        return details;
    }

    private static String textContent(@Nullable JSONObject text) {
        return text == null ? "" : text.optString("content");
    }

    /**
     * Lower case words without accents, so a search matches titles written with or without them.
     */
    private static List<String> normalizedWords(String text) {
        List<String> words = new ArrayList<>();
        for (String word : normalize(text).split("\\s+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    private static String normalize(String text) {
        return Utils.normalizeTextToLowercase(text).replace('’', '\'');
    }

    /**
     * @return Whether the text has every word of the search.
     */
    public static boolean matches(String text, String query) {
        return matches(text, normalizedWords(query));
    }

    private static boolean matches(String text, List<String> queryWords) {
        if (queryWords.isEmpty()) {
            return false;
        }

        String normalizedText = normalize(text);
        for (String word : queryWords) {
            if (!containsWordStart(normalizedText, word)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A word of the search matches the start of a word, so that it matches plurals and longer
     * forms of it, but not words that only contain it, such as "time" in "ultime".
     * Languages written without spaces between words match anywhere.
     */
    private static boolean containsWordStart(String text, String word) {
        switch (Character.UnicodeScript.of(word.codePointAt(0))) {
            case HAN, HIRAGANA, KATAKANA, THAI, LAO, KHMER, MYANMAR -> {
                return text.contains(word);
            }
        }

        for (int index = text.indexOf(word); index >= 0; index = text.indexOf(word, index + 1)) {
            if (index == 0 || !Character.isLetterOrDigit(text.codePointBefore(index))) {
                return true;
            }
        }
        return false;
    }
}
