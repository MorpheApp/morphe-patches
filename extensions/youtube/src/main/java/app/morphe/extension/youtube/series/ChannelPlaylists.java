package app.morphe.extension.youtube.series;

import org.json.*;

import java.io.IOException;
import java.util.*;

/** Public playlist-tab discovery. Channel listings supply candidates, not membership proof. */
final class ChannelPlaylists {
    static final int MAX_PAGES = 2, MAX_ITEMS = 96;

    interface Request {
        JSONObject browse(JSONObject payload) throws IOException, JSONException;
    }

    static JSONObject fetch(String channel, Request request) throws IOException, JSONException {
        JSONArray rows = new JSONArray();
        if (!channel.matches("UC[A-Za-z0-9_-]{22}")) return result(rows);
        JSONObject home = request.browse(new JSONObject().put("browseId", channel));
        if (!channel.equals(
                CatalogParser.object(
                                CatalogParser.object(home, "metadata"), "channelMetadataRenderer")
                        .optString("externalId"))) return result(rows);
        JSONObject endpoint = null;
        JSONArray tabs = tabs(home);
        for (int i = 0; i < tabs.length(); i++) {
            JSONObject tab = CatalogParser.object(tabs.optJSONObject(i), "tabRenderer");
            JSONObject browse =
                    CatalogParser.object(CatalogParser.object(tab, "endpoint"), "browseEndpoint");
            if ("Playlists".equals(tab.optString("title"))
                    && channel.equals(browse.optString("browseId"))
                    && !browse.optString("params").isEmpty()) endpoint = browse;
        }
        if (endpoint == null) return result(rows);
        JSONObject payload =
                new JSONObject()
                        .put("browseId", channel)
                        .put("params", endpoint.getString("params"));
        Set<String> tokens = new HashSet<>(), ids = new HashSet<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            JSONObject response = request.browse(payload);
            Page parsed = new Page(rows, ids);
            if (page == 0) {
                if (!channel.equals(
                        CatalogParser.object(
                                        CatalogParser.object(response, "metadata"),
                                        "channelMetadataRenderer")
                                .optString("externalId"))) return result(rows);
                JSONArray pageTabs = tabs(response);
                for (int i = 0; i < pageTabs.length(); i++) {
                    JSONObject tab = CatalogParser.object(pageTabs.optJSONObject(i), "tabRenderer");
                    if (tab.optBoolean("selected") && "Playlists".equals(tab.optString("title")))
                        parsed.collect(CatalogParser.object(tab, "content"), 0);
                }
            } else {
                JSONArray actions = response.optJSONArray("onResponseReceivedActions");
                if (actions != null)
                    for (int i = 0; i < actions.length(); i++)
                        parsed.collect(
                                CatalogParser.object(
                                                actions.optJSONObject(i),
                                                "appendContinuationItemsAction")
                                        .optJSONArray("continuationItems"),
                                0);
                parsed.collect(
                        CatalogParser.object(
                                CatalogParser.object(response, "continuationContents"),
                                "gridContinuation"),
                        0);
            }
            if (parsed.tokens.size() != 1 || rows.length() >= MAX_ITEMS) break;
            String next = parsed.tokens.iterator().next();
            if (!tokens.add(next)) break;
            payload = new JSONObject().put("continuation", next);
        }
        return result(rows);
    }

    private static JSONArray tabs(JSONObject response) {
        JSONObject contents = CatalogParser.object(response, "contents");
        JSONObject browse = contents.optJSONObject("twoColumnBrowseResultsRenderer");
        if (browse == null)
            browse = CatalogParser.object(contents, "singleColumnBrowseResultsRenderer");
        JSONArray tabs = browse.optJSONArray("tabs");
        return tabs == null ? new JSONArray() : tabs;
    }

    private static JSONObject result(JSONArray rows) throws JSONException {
        return new JSONObject().put("contents", rows);
    }

    private static final class Page {
        final JSONArray rows;
        final Set<String> ids, tokens = new LinkedHashSet<>();
        int visits;

        Page(JSONArray rows, Set<String> ids) {
            this.rows = rows;
            this.ids = ids;
        }

        void collect(Object node, int depth) throws JSONException {
            if (depth > 16 || visits++ >= 4000 || rows.length() >= MAX_ITEMS) return;
            if (node instanceof JSONArray) {
                JSONArray array = (JSONArray) node;
                for (int i = 0; i < array.length(); i++) collect(array.opt(i), depth + 1);
                return;
            }
            if (!(node instanceof JSONObject)) return;
            JSONObject object = (JSONObject) node;
            JSONObject lockup = object.optJSONObject("lockupViewModel");
            if (lockup != null
                    && "LOCKUP_CONTENT_TYPE_PLAYLIST".equals(lockup.optString("contentType"))) {
                String id = lockup.optString("contentId");
                String title =
                        CatalogParser.object(
                                        CatalogParser.object(
                                                CatalogParser.object(lockup, "metadata"),
                                                "lockupMetadataViewModel"),
                                        "title")
                                .optString("content");
                if (PlaylistDiscovery.eligible(id) && ids.add(id))
                    rows.put(
                            new JSONObject()
                                    .put(
                                            "playlistRenderer",
                                            new JSONObject()
                                                    .put("playlistId", id)
                                                    .put(
                                                            "title",
                                                            new JSONObject()
                                                                    .put("simpleText", title))));
            }
            for (String renderer : new String[] {"gridPlaylistRenderer", "playlistRenderer"}) {
                JSONObject playlist = object.optJSONObject(renderer);
                if (playlist != null
                        && PlaylistDiscovery.eligible(playlist.optString("playlistId"))
                        && ids.add(playlist.optString("playlistId")))
                    rows.put(new JSONObject().put(renderer, playlist));
            }
            String next =
                    CatalogParser.object(
                                    CatalogParser.object(
                                            CatalogParser.object(
                                                    object, "continuationItemRenderer"),
                                            "continuationEndpoint"),
                                    "continuationCommand")
                            .optString("token");
            if (!next.isEmpty()) tokens.add(next);
            JSONArray continuations = object.optJSONArray("continuations");
            if (continuations != null)
                for (int i = 0; i < continuations.length(); i++) {
                    next =
                            CatalogParser.object(
                                            continuations.optJSONObject(i), "nextContinuationData")
                                    .optString("continuation");
                    if (!next.isEmpty()) tokens.add(next);
                }
            // Only list wrappers; do not traverse navigation, recommendations or video menus.
            for (String key :
                    new String[] {
                        "sectionListRenderer",
                        "itemSectionRenderer",
                        "gridRenderer",
                        "richGridRenderer",
                        "richItemRenderer",
                        "content",
                        "contents",
                        "items"
                    }) collect(object.opt(key), depth + 1);
        }
    }

    private ChannelPlaylists() {}
}
