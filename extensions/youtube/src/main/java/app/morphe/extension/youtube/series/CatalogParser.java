package app.morphe.extension.youtube.series;

import static app.morphe.extension.youtube.series.TrackerModels.Episode;

import org.json.*;

import java.util.*;

/** Explicit playlist containers only: recommendations and other shelves are never traversed. */
public final class CatalogParser {
    public static final class Page {
        public final String title, continuation, visitorData;
        public final List<Episode> episodes;

        Page(String title, String continuation, String visitor, List<Episode> episodes) {
            this.title = title;
            this.continuation = continuation;
            visitorData = visitor;
            this.episodes = Collections.unmodifiableList(episodes);
        }
    }

    private final List<Episode> entries = new ArrayList<>();
    private final Set<String> continuations = new LinkedHashSet<>();
    private final int offset;
    private boolean recognized;

    private CatalogParser(int offset) {
        this.offset = offset;
    }

    public static Page parse(String json, int offset, boolean continuationPage)
            throws JSONException {
        JSONObject root = new JSONObject(json);
        if (root.has("error")) throw new JSONException("YouTube returned an error response");
        JSONArray alerts = root.optJSONArray("alerts");
        if (alerts != null)
            for (int i = 0; i < alerts.length(); i++) {
                JSONObject alert = object(alerts.optJSONObject(i), "alertRenderer");
                if (alert.optString("type").equals("ERROR"))
                    throw new JSONException(
                            "Catalog unavailable: " + text(alert.optJSONObject("text")));
            }
        CatalogParser parser = new CatalogParser(offset);
        if (continuationPage) {
            JSONObject container =
                    object(root, "continuationContents")
                            .optJSONObject("playlistVideoListContinuation");
            if (container != null) parser.list(container);
            else {
                JSONArray actions = root.optJSONArray("onResponseReceivedActions");
                if (actions != null)
                    for (int i = 0; i < actions.length(); i++) {
                        JSONObject action =
                                object(actions.optJSONObject(i), "appendContinuationItemsAction");
                        if (action.has("continuationItems")) {
                            if (parser.recognized)
                                throw new JSONException("Ambiguous playlist containers");
                            parser.recognized = true;
                            parser.items(action.getJSONArray("continuationItems"));
                        }
                    }
            }
        } else {
            JSONObject contents = object(root, "contents");
            JSONObject browse = contents.optJSONObject("singleColumnBrowseResultsRenderer");
            if (browse == null) browse = contents.optJSONObject("twoColumnBrowseResultsRenderer");
            JSONArray tabs = browse == null ? null : browse.optJSONArray("tabs");
            if (tabs != null)
                for (int i = 0; i < tabs.length(); i++) {
                    JSONObject tab = object(tabs.optJSONObject(i), "tabRenderer");
                    if (tab.optBoolean("selected", tabs.length() == 1))
                        parser.section(object(tab, "content"), 0);
                }
        }
        if (!parser.recognized)
            throw new JSONException("Unrecognized playlist response; cached episodes were kept");
        if (parser.continuations.size() > 1)
            throw new JSONException("Ambiguous playlist continuation");
        String title = object(object(root, "header"), "pageHeaderRenderer").optString("pageTitle");
        if (title.isEmpty())
            title =
                    text(
                            object(root, "header").optJSONObject("playlistHeaderRenderer") == null
                                    ? null
                                    : object(object(root, "header"), "playlistHeaderRenderer")
                                            .optJSONObject("title"));
        if (title.isEmpty())
            title = object(object(root, "metadata"), "playlistMetadataRenderer").optString("title");
        String next = parser.continuations.isEmpty() ? "" : parser.continuations.iterator().next();
        return new Page(
                title,
                next,
                object(root, "responseContext").optString("visitorData"),
                parser.entries);
    }

    private void section(JSONObject node, int depth) throws JSONException {
        if (depth > 12) throw new JSONException("Playlist nesting exceeds supported depth");
        if (node.has("playlistVideoListRenderer")) {
            list(node.getJSONObject("playlistVideoListRenderer"));
            return;
        }
        for (String wrapper : new String[] {"sectionListRenderer", "itemSectionRenderer"}) {
            JSONObject container = node.optJSONObject(wrapper);
            if (container == null) continue;
            JSONArray contents = container.optJSONArray("contents");
            if (contents != null)
                for (int i = 0; i < contents.length(); i++) {
                    JSONObject child = contents.optJSONObject(i);
                    if (child != null) section(child, depth + 1);
                }
        }
    }

    private void list(JSONObject container) throws JSONException {
        JSONArray contents = container.optJSONArray("contents");
        if (contents == null) throw new JSONException("Playlist contents are missing");
        if (recognized) throw new JSONException("Ambiguous playlist containers");
        recognized = true;
        items(contents);
        JSONArray next = container.optJSONArray("continuations");
        if (next != null)
            for (int i = 0; i < next.length(); i++) {
                String token =
                        object(next.optJSONObject(i), "nextContinuationData")
                                .optString("continuation");
                if (!token.isEmpty()) continuations.add(token);
            }
    }

    private void items(JSONArray items) throws JSONException {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) throw new JSONException("Invalid playlist item");
            JSONObject video = item.optJSONObject("playlistVideoRenderer");
            if (video != null) {
                String id = video.optString("videoId");
                if (!id.matches("[A-Za-z0-9_-]{11}")) id = "";
                String title = text(video.optJSONObject("title"));
                // Keep absent titles empty; the UI selects a fallback in the current locale.
                long duration = 0;
                try {
                    duration =
                            Math.multiplyExact(
                                    Long.parseLong(video.optString("lengthSeconds")), 1000L);
                } catch (NumberFormatException | ArithmeticException ignored) {
                }
                entries.add(
                        new Episode(
                                offset + entries.size() + 1,
                                id,
                                title,
                                Math.max(0, duration),
                                video.optBoolean("isPlayable", !id.isEmpty())
                                        && !"[Private video]".equals(title)
                                        && !"[Deleted video]".equals(title),
                                text(video.optJSONObject("videoInfo"))));
            } else if ("Unavailable videos will be hidden during playback"
                    .equals(text(object(item, "messageRenderer").optJSONObject("text")))) {
                // Informational banner from Show unavailable videos. The entries remain below it.
                // A banner saying videos are hidden is deliberately not accepted as a full catalog.
            } else if (item.has("continuationItemRenderer")) {
                String token =
                        object(
                                        object(
                                                object(item, "continuationItemRenderer"),
                                                "continuationEndpoint"),
                                        "continuationCommand")
                                .optString("token");
                if (token.isEmpty()) throw new JSONException("Missing continuation token");
                continuations.add(token);
            } else {
                // Unknown content inside the list must not silently shrink its episode count.
                throw new JSONException("Unsupported playlist row");
            }
        }
    }

    static JSONObject object(JSONObject node, String key) {
        JSONObject value = node == null ? null : node.optJSONObject(key);
        return value == null ? new JSONObject() : value;
    }

    static String text(JSONObject text) {
        if (text == null) return "";
        if (text.has("simpleText")) return text.optString("simpleText");
        StringBuilder result = new StringBuilder();
        JSONArray runs = text.optJSONArray("runs");
        if (runs != null)
            for (int i = 0; i < runs.length(); i++)
                result.append(objectAt(runs, i).optString("text"));
        return result.toString();
    }

    private static JSONObject objectAt(JSONArray array, int index) {
        JSONObject v = array.optJSONObject(index);
        return v == null ? new JSONObject() : v;
    }
}
