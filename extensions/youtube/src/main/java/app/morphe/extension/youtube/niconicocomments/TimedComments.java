/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4430
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.niconicocomments;

import android.graphics.Color;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comments of a regular video that contain a time, such as "1:23 nice", shown at that time.
 */
final class TimedComments {
    private static final Pattern TIME = Pattern.compile(
            "(?<![\\d:])(?:(\\d{1,2}):)?(\\d{1,2}):([0-5]\\d)(?![\\d:])");
    private static final Pattern EDGE_SEPARATORS = Pattern.compile(
            "^[\\s\\-–—|/・:：~〜]+|[\\s\\-–—|/・:：~〜]+$");
    private static final int MAX_COMMENT_LENGTH = 200;

    static final class Page {
        final List<NiconicoComment> comments;
        @Nullable
        final String continuation;

        Page(List<NiconicoComment> comments, @Nullable String continuation) {
            this.comments = comments;
            this.continuation = continuation;
        }
    }

    private TimedComments() {
    }

    /**
     * @return The continuation of the comments section of a watch page, or null if the video has no comments.
     */
    @Nullable
    static String findCommentsContinuation(@Nullable JSONObject initialData) {
        JSONObject results = initialData == null ? null : initialData.optJSONObject("contents");
        results = results == null ? null : results.optJSONObject("twoColumnWatchNextResults");
        results = results == null ? null : results.optJSONObject("results");
        results = results == null ? null : results.optJSONObject("results");
        JSONArray contents = results == null ? null : results.optJSONArray("contents");
        if (contents == null) {
            return null;
        }
        for (int i = 0; i < contents.length(); i++) {
            JSONObject section = contents.optJSONObject(i);
            section = section == null ? null : section.optJSONObject("itemSectionRenderer");
            if (section != null && "comment-item-section".equals(section.optString("sectionIdentifier"))) {
                return getContinuationToken(section.optJSONArray("contents"));
            }
        }
        return null;
    }

    /**
     * Parses a page of top level comments from the next endpoint.
     */
    static Page parsePage(JSONObject response, long videoLengthMs) {
        List<NiconicoComment> comments = new ArrayList<>();
        JSONObject updates = response.optJSONObject("frameworkUpdates");
        updates = updates == null ? null : updates.optJSONObject("entityBatchUpdate");
        JSONArray mutations = updates == null ? null : updates.optJSONArray("mutations");
        if (mutations != null) {
            for (int i = 0; i < mutations.length(); i++) {
                JSONObject payload = mutations.optJSONObject(i);
                payload = payload == null ? null : payload.optJSONObject("payload");
                JSONObject entity = payload == null ? null : payload.optJSONObject("commentEntityPayload");
                JSONObject properties = entity == null ? null : entity.optJSONObject("properties");
                if (properties == null || properties.optInt("replyLevel") != 0) {
                    continue;
                }
                JSONObject content = properties.optJSONObject("content");
                comments.addAll(parseComment(
                        properties.optString("commentId"),
                        content == null ? "" : content.optString("content"),
                        videoLengthMs));
            }
        }

        // The last item of the comment list loads the next page. Replies have their own items inside the threads.
        String continuation = null;
        JSONArray endpoints = response.optJSONArray("onResponseReceivedEndpoints");
        if (endpoints != null) {
            for (int i = 0; i < endpoints.length(); i++) {
                JSONObject endpoint = endpoints.optJSONObject(i);
                if (endpoint == null) {
                    continue;
                }
                for (String key : new String[]{"reloadContinuationItemsCommand", "appendContinuationItemsAction"}) {
                    JSONObject command = endpoint.optJSONObject(key);
                    String token = getContinuationToken(command == null ? null : command.optJSONArray("continuationItems"));
                    if (token != null) {
                        continuation = token;
                    }
                }
            }
        }
        return new Page(comments, continuation);
    }

    /**
     * Makes one comment for each time in the text. The text after a time is shown at that time,
     * so a list of times shows each part at its own time.
     */
    static List<NiconicoComment> parseComment(String commentId, String text, long videoLengthMs) {
        String flat = text.replaceAll("\\s+", " ").trim();
        List<int[]> ranges = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        Matcher matcher = TIME.matcher(flat);
        while (matcher.find()) {
            long timeMs = parseTimeMs(matcher);
            if (timeMs >= 0 && (videoLengthMs <= 0 || timeMs <= videoLengthMs)) {
                ranges.add(new int[]{matcher.start(), matcher.end()});
                times.add(timeMs);
            }
        }
        if (times.isEmpty()) {
            return Collections.emptyList();
        }

        StringBuilder withoutTimes = new StringBuilder();
        int previousEnd = 0;
        for (int[] range : ranges) {
            withoutTimes.append(flat, previousEnd, range[0]).append(' ');
            previousEnd = range[1];
        }
        withoutTimes.append(flat.substring(previousEnd));
        String fallback = clean(withoutTimes.toString().replaceAll("\\s+", " "));

        List<NiconicoComment> comments = new ArrayList<>(times.size());
        for (int i = 0; i < times.size(); i++) {
            int end = i + 1 < ranges.size() ? ranges.get(i + 1)[0] : flat.length();
            String segment = clean(flat.substring(ranges.get(i)[1], end));
            if (i == 0) {
                String prefix = clean(flat.substring(0, ranges.get(0)[0]));
                if (!prefix.isEmpty()) {
                    segment = segment.isEmpty() ? prefix : prefix + " " + segment;
                }
            }
            if (segment.isEmpty()) {
                segment = fallback;
            }
            if (segment.isEmpty() || segment.length() > MAX_COMMENT_LENGTH) {
                continue;
            }
            comments.add(new NiconicoComment(commentId + "#" + i, segment, false, Color.TRANSPARENT, times.get(i)));
        }
        return comments;
    }

    private static long parseTimeMs(Matcher matcher) {
        long minutes = Long.parseLong(matcher.group(2));
        long seconds = Long.parseLong(matcher.group(3));
        String hours = matcher.group(1);
        if (hours == null) {
            return (minutes * 60 + seconds) * 1000;
        }
        if (minutes >= 60) {
            return -1;
        }
        return (Long.parseLong(hours) * 3600 + minutes * 60 + seconds) * 1000;
    }

    private static String clean(String text) {
        return EDGE_SEPARATORS.matcher(text.trim()).replaceAll("").trim();
    }

    @Nullable
    private static String getContinuationToken(@Nullable JSONArray items) {
        JSONObject last = items == null || items.length() == 0 ? null : items.optJSONObject(items.length() - 1);
        JSONObject renderer = last == null ? null : last.optJSONObject("continuationItemRenderer");
        JSONObject endpoint = renderer == null ? null : renderer.optJSONObject("continuationEndpoint");
        JSONObject command = endpoint == null ? null : endpoint.optJSONObject("continuationCommand");
        String token = command == null ? "" : command.optString("token");
        return token.isEmpty() ? null : token;
    }
}
