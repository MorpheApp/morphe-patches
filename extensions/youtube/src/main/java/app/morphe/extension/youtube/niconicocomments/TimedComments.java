/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4430
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.niconicocomments;

import static app.morphe.extension.youtube.niconicocomments.YouTubeChatSource.getObjectPath;

import android.graphics.Color;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comments of a regular video that contain a time, such as "1:23 nice", shown at that time.
 */
final class TimedComments {
    /**
     * A time such as 1:23 or 1:02:03 that is not part of a longer number, such as 12:345.
     */
    private static final Pattern TIME = Pattern.compile(
            "(?<![\\d:])(?:(\\d{1,2}):)?(\\d{1,2}):([0-5]\\d)(?![\\d:])");
    /**
     * Includes the full width and no-break spaces.
     */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
    /**
     * Separators between a time and its text, such as in "1:23 - chorus".
     */
    private static final Pattern EDGE_SEPARATORS = Pattern.compile(
            "^[\\s\\p{Z}\\-–—|/・:：~〜]+|[\\s\\p{Z}\\-–—|/・:：~〜]+$");
    private static final String COMMENTS_SECTION_ID = "comment-item-section";

    @SuppressWarnings("ClassCanBeRecord")
    static final class Page {
        final List<NiconicoComment> comments;
        @Nullable
        final String continuation;

        Page(List<NiconicoComment> comments, @Nullable String continuation) {
            this.comments = comments;
            this.continuation = continuation;
        }
    }

    @SuppressWarnings("ClassCanBeRecord")
    private static final class TimeMatch {
        final int start;
        final int end;
        final long timeMs;

        TimeMatch(int start, int end, long timeMs) {
            this.start = start;
            this.end = end;
            this.timeMs = timeMs;
        }
    }

    private TimedComments() {
    }

    /**
     * @return The continuation of the comments section of a watch page, or null if the video has no comments.
     */
    @Nullable
    static String findCommentsContinuation(JSONObject initialData) {
        JSONObject results = getObjectPath(initialData,
                "contents", "twoColumnWatchNextResults", "results", "results");
        JSONArray contents = results == null ? null : results.optJSONArray("contents");
        if (contents == null) {
            return null;
        }
        for (int i = 0; i < contents.length(); i++) {
            JSONObject section = getObjectPath(contents.optJSONObject(i), "itemSectionRenderer");
            if (section != null && COMMENTS_SECTION_ID.equals(section.optString("sectionIdentifier"))) {
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
        JSONObject batchUpdate = getObjectPath(response, "frameworkUpdates", "entityBatchUpdate");
        JSONArray mutations = batchUpdate == null ? null : batchUpdate.optJSONArray("mutations");
        for (int i = 0; mutations != null && i < mutations.length(); i++) {
            JSONObject properties = getObjectPath(mutations.optJSONObject(i),
                    "payload", "commentEntityPayload", "properties");
            if (properties == null || properties.optInt("replyLevel") != 0) {
                continue;
            }
            JSONObject content = properties.optJSONObject("content");
            comments.addAll(parseComment(
                    properties.optString("commentId"),
                    content == null ? "" : content.optString("content"),
                    videoLengthMs
            ));
        }

        // The last item of the comment list loads the next page. Replies have their own items inside the threads.
        String continuation = null;
        JSONArray endpoints = response.optJSONArray("onResponseReceivedEndpoints");
        for (int i = 0; endpoints != null && i < endpoints.length(); i++) {
            JSONObject endpoint = endpoints.optJSONObject(i);
            JSONObject command = getObjectPath(endpoint, "appendContinuationItemsAction");
            if (command == null) {
                command = getObjectPath(endpoint, "reloadContinuationItemsCommand");
            }
            String token = getContinuationToken(command == null ? null : command.optJSONArray("continuationItems"));
            if (token != null) {
                continuation = token;
            }
        }
        return new Page(comments, continuation);
    }

    /**
     * Makes one comment for each time in the text. The text after a time is shown at that time,
     * so a list of times shows each part at its own time.
     */
    static List<NiconicoComment> parseComment(String commentId, String text, long videoLengthMs) {
        String flat = WHITESPACE.matcher(text).replaceAll(" ");
        List<TimeMatch> times = new ArrayList<>();
        Matcher matcher = TIME.matcher(flat);
        while (matcher.find()) {
            long timeMs = parseTimeMs(matcher);
            if (timeMs >= 0 && (videoLengthMs <= 0 || timeMs <= videoLengthMs)) {
                times.add(new TimeMatch(matcher.start(), matcher.end(), timeMs));
            }
        }
        if (times.isEmpty()) {
            return Collections.emptyList();
        }

        // Shown at the times that have no text of their own.
        StringBuilder withoutTimes = new StringBuilder();
        int previousEnd = 0;
        for (TimeMatch time : times) {
            withoutTimes.append(flat, previousEnd, time.start).append(' ');
            previousEnd = time.end;
        }
        withoutTimes.append(flat, previousEnd, flat.length());
        String wholeText = clean(WHITESPACE.matcher(withoutTimes).replaceAll(" "));

        List<NiconicoComment> comments = new ArrayList<>(times.size());
        for (int i = 0; i < times.size(); i++) {
            TimeMatch time = times.get(i);
            int end = i + 1 < times.size() ? times.get(i + 1).start : flat.length();
            String segment = clean(flat.substring(time.end, end));
            if (i == 0) {
                // Text before the first time, such as "best part 1:23".
                String prefix = clean(flat.substring(0, time.start));
                if (!prefix.isEmpty()) {
                    // A time inside a sentence, such as "the hole at 1:23?", stays in it.
                    segment = segment.isEmpty() ? prefix : clean(flat.substring(0, end));
                }
            }
            if (segment.isEmpty()) {
                segment = wholeText;
            }
            if (!segment.isEmpty() && segment.length() <= YouTubeChatSource.MAX_COMMENT_LENGTH) {
                comments.add(new NiconicoComment(commentId + "#" + i, segment, false, Color.TRANSPARENT, time.timeMs));
            }
        }
        return comments;
    }

    /**
     * @return The time in milliseconds, or -1 if the minutes of a time with hours are not valid.
     */
    private static long parseTimeMs(Matcher matcher) {
        long minutes = Long.parseLong(Objects.requireNonNull(matcher.group(2)));
        long seconds = Long.parseLong(Objects.requireNonNull(matcher.group(3)));
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
        return EDGE_SEPARATORS.matcher(text).replaceAll("");
    }

    @Nullable
    private static String getContinuationToken(@Nullable JSONArray items) {
        if (items == null || items.length() == 0) {
            return null;
        }
        JSONObject command = getObjectPath(items.optJSONObject(items.length() - 1),
                "continuationItemRenderer", "continuationEndpoint", "continuationCommand");
        String token = command == null ? "" : command.optString("token");
        return token.isEmpty() ? null : token;
    }
}
