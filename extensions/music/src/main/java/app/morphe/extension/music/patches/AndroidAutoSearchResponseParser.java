/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3852
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.BROWSE;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.BROWSE_ID;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.COMPONENT_MODEL;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.COMPONENT_TYPE;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.ELEMENT;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.ELEMENT_TYPE;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.IMAGE_CONTENT;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.INNERTUBE_COMMAND;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.ITEM_SECTION;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.ITEM_SECTION_CONTENTS;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.MENU;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.MENU_CONTENT;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.MENU_ENDPOINT;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.MUSIC_SHELF;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.MUSIC_SHELF_CONTENTS;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.NEW_ELEMENT;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.PLAYLIST_ID;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.RESULT_IMAGE;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.RESULT_MENU;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.RESULT_ROWS;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.RESULT_ROWS_ITEMS;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.RESULT_SUBTITLE;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.RESULT_TAP;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.RESULT_TITLE;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.SECTION_LIST;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.SECTION_LIST_CONTENTS;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.SHELF_ITEM;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.SHELF_ITEM_IMAGE;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.SHELF_ITEM_MENU;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.SHELF_ITEM_SUBTITLE;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.SHELF_ITEM_TAP;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.SHELF_ITEM_TITLE;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.TAB;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.TAB_CONTENTS;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.TEXT_CONTENT;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.THUMBNAIL;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.THUMBNAIL_CONTENTS;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.TOP_RESULT;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.TOP_RESULT_CONTENT;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.TOP_RESULT_MENU;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.TOP_RESULT_PRIMARY;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.TOP_RESULT_RELATED;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.TOP_RESULT_TAP;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.WATCH;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.WATCH_PLAYLIST;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.artwork;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.episodeSubtitle;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.isPlayable;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.menuSubtitle;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.playCommand;
import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.textRuns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads titles, artwork, and playback actions from phone search responses. */
final class AndroidAutoSearchResponseParser {
    private static final int SEARCH_CONTENTS = 4;
    private static final int TABBED_SEARCH = 60_498_879;
    private static final int SEARCH_TABS = 1;
    private static final int TAB_ID = 11;
    private static final int TAB_COMMAND = 1;
    private static final int MESSAGE = 58_508_690;

    private static final int COMPACT_RESULT_CARD = 1_297;
    private static final int COMPACT_RESULT_CONTENT = 1;
    private static final int COMPACT_RESULT_HEADER = 1;
    private static final int COMPACT_RESULT_ROWS = 2;

    private static final int TOP_RESULT_COMPONENTS = 9;
    private static final int TOP_RESULT_CARD = 1;
    private static final int TOP_RESULT_RELATED_ROW = 2;

    private static final int SEARCH = 48_687_709;
    private static final int SEARCH_PARAMS = 2;

    private static final int BROWSE_SUPPORTED_CONFIG = 6;
    private static final int BROWSE_MUSIC_CONFIG = 172_183_029;
    private static final int MUSIC_PAGE_TYPE = 1;
    private static final int PAGE_TYPE_PROFILE = 5;

    private static final int WATCH_SUPPORTED_CONFIG = 136_656_028;
    private static final int WATCH_MUSIC_CONFIG = 136_657_325;
    private static final int MUSIC_VIDEO_TYPE = 2;
    private static final int VIDEO_TYPE_OFFICIAL_MUSIC_VIDEO = 2;
    private static final int VIDEO_TYPE_USER_GENERATED = 3;
    private static final int VIDEO_TYPE_UPLOADED_TRACK = 6;

    enum ResultCategory {
        TOP_RESULT("search_shelf_top_result_title"),
        SONGS("library_songs_shelf_title"),
        ALBUMS("library_albums_shelf_title"),
        ARTISTS("library_artists_shelf_title"),
        PLAYLISTS("library_playlists_shelf_title"),
        VIDEOS("morphe_music_android_auto_videos"),
        PODCASTS("offline_podcasts_shelf_title"),
        EPISODES("library_episodes_shelf_title"),
        UPLOADED_SONGS("morphe_music_android_auto_uploaded_songs"),
        UPLOADED_ALBUMS("morphe_music_android_auto_uploaded_albums"),
        UPLOADED_ARTISTS("morphe_music_android_auto_uploaded_artists"),
        OTHER("morphe_music_android_auto_other_results");

        final String titleResource;
        ResultCategory(String titleResource) { this.titleResource = titleResource; }
    }

    record SearchResult(String title, String subtitle, String artworkUrl, byte[] playCommandBytes,
                        boolean preservePlaybackCommand, ResultCategory category) {}
    static List<SearchResult> parseSearchResults(byte[] response, boolean uploaded) {
        ProtoMessage root = new ProtoMessage(response);
        ProtoMessage pageContents = root.messageField(SEARCH_CONTENTS);
        if (!pageContents.messageField(MESSAGE).isEmpty()) return Collections.emptyList();
        ProtoMessage sectionList = pageContents.messageField(SECTION_LIST);
        if (sectionList.isEmpty()) {
            String requestedTabId = uploaded ? "music_search_upload" : "music_search_catalog";
            boolean hasCatalogTab = false;
            boolean hasUploadTab = false;
            for (ProtoMessage tabWrapper : pageContents.messageField(TABBED_SEARCH).repeatedMessageField(SEARCH_TABS)) {
                ProtoMessage tab = tabWrapper.messageField(TAB);
                hasCatalogTab |= tab.stringField(TAB_ID).equals("music_search_catalog");
                hasUploadTab |= tab.stringField(TAB_ID).equals("music_search_upload");
                if (!tab.stringField(TAB_ID).isEmpty() && !tab.stringField(TAB_ID).equals(requestedTabId)) continue;
                ProtoMessage tabContents = tab.messageField(TAB_CONTENTS);
                if (!tabContents.messageField(MESSAGE).isEmpty()) return Collections.emptyList();
                sectionList = tabContents.messageField(SECTION_LIST);
                if (!sectionList.isEmpty()) break;
            }
            // Accounts without uploads omit that tab from an otherwise valid search response.
            if (uploaded && hasCatalogTab && !hasUploadTab) return Collections.emptyList();
        }
        if (sectionList.isEmpty()) throw new IllegalArgumentException("Unrecognized search contents");
        return readSections(sectionList);
    }

    static String uploadedSearchParams(byte[] response) {
        ProtoMessage tabbedSearch = new ProtoMessage(response).messagePath(SEARCH_CONTENTS, TABBED_SEARCH);
        for (ProtoMessage tabWrapper : tabbedSearch.repeatedMessageField(SEARCH_TABS)) {
            ProtoMessage tab = tabWrapper.messageField(TAB);
            if (tab.stringField(TAB_ID).equals("music_search_upload")) {
                return tab.messagePath(TAB_COMMAND, SEARCH).stringField(SEARCH_PARAMS);
            }
        }
        return "";
    }

    private static List<SearchResult> readSections(ProtoMessage sectionList) {
        List<SearchResult> results = new ArrayList<>();
        for (ProtoMessage sectionRenderer : sectionList.repeatedMessageField(SECTION_LIST_CONTENTS)) {
            for (ProtoMessage childRenderer : sectionRenderer.messageField(MUSIC_SHELF).repeatedMessageField(MUSIC_SHELF_CONTENTS))
                addShelfSearchResult(results, childRenderer.messageField(SHELF_ITEM));
            for (ProtoMessage childRenderer : sectionRenderer.messageField(ITEM_SECTION).repeatedMessageField(ITEM_SECTION_CONTENTS)) {
                ProtoMessage model = childRenderer.messagePath(ELEMENT, NEW_ELEMENT, ELEMENT_TYPE, COMPONENT_TYPE, COMPONENT_MODEL);
                for (ProtoMessage row : model.messageField(RESULT_ROWS)
                        .repeatedMessageField(RESULT_ROWS_ITEMS)) {
                    addRowSearchResult(results, row, false);
                }
                ProtoMessage topResult = model.messagePath(TOP_RESULT, TOP_RESULT_CONTENT);
                addTopSearchResult(results, topResult.messageField(TOP_RESULT_PRIMARY));
                for (ProtoMessage row : topResult.repeatedMessageField(TOP_RESULT_RELATED))
                    addRowSearchResult(results, row, false);
                // Some responses carry top result cards and related rows in this component list.
                for (ProtoMessage component : topResult.repeatedMessageField(TOP_RESULT_COMPONENTS)) {
                    addTopSearchResult(results, component.messageField(TOP_RESULT_CARD));
                    addRowSearchResult(results, component.messageField(TOP_RESULT_RELATED_ROW), false);
                }
                // Compact cards also carry collections such as Listen again.
                // An empty header groups the rows under Top result for display.
                // A nonempty header keeps each row in its own category.
                ProtoMessage card = model.messagePath(COMPACT_RESULT_CARD, COMPACT_RESULT_CONTENT);
                boolean groupAsTopResult = card.messageField(COMPACT_RESULT_HEADER).stringField(TEXT_CONTENT).isEmpty();
                for (ProtoMessage row : card.repeatedMessageField(COMPACT_RESULT_ROWS))
                    addRowSearchResult(results, row, groupAsTopResult);
            }
        }
        return Collections.unmodifiableList(results);
    }

    private static void addShelfSearchResult(List<SearchResult> results, ProtoMessage renderer) {
        String title = textRuns(renderer.messageField(SHELF_ITEM_TITLE));
        ProtoMessage tapCommand = renderer.messageField(SHELF_ITEM_TAP);
        boolean tapStartsPlayback = isPlayable(tapCommand);
        if (title.isEmpty() || (!tapStartsPlayback && tapCommand.messageField(BROWSE).isEmpty())) return;
        String artworkUrl = artwork(renderer.messagePath(SHELF_ITEM_IMAGE, THUMBNAIL, THUMBNAIL_CONTENTS));
        String subtitle = textRuns(renderer.messageField(SHELF_ITEM_SUBTITLE));
        ResultCategory category = resultCategory(tapCommand);
        ProtoMessage menu = renderer.messagePath(SHELF_ITEM_MENU, MENU);
        ProtoMessage playCommand = playCommand(tapCommand, menu, renderer, false);
        subtitle = podcastSubtitle(menu, category, subtitle);
        addSearchResult(results, title, subtitle, artworkUrl, tapCommand, playCommand, category, false);
    }

    private static void addRowSearchResult(List<SearchResult> results, ProtoMessage renderer, boolean groupAsTopResult) {
        String title = renderer.stringField(RESULT_TITLE);
        ProtoMessage tapCommand = renderer.messagePath(RESULT_TAP, INNERTUBE_COMMAND);
        boolean tapStartsPlayback = isPlayable(tapCommand);
        if (title.isEmpty() || (!tapStartsPlayback && tapCommand.messageField(BROWSE).isEmpty())) return;
        String artworkUrl = artwork(renderer.messagePath(RESULT_IMAGE, IMAGE_CONTENT));
        String subtitle = renderer.stringField(RESULT_SUBTITLE);
        ResultCategory category = resultCategory(tapCommand);
        ProtoMessage menu = tapStartsPlayback ? ProtoMessage.EMPTY : renderer.messagePath(RESULT_MENU,
                INNERTUBE_COMMAND, MENU_ENDPOINT, MENU_CONTENT, MENU);
        ProtoMessage playCommand = playCommand(tapCommand, menu, renderer, false);
        if (!tapStartsPlayback) subtitle = podcastSubtitle(menu, category, subtitle);
        if (category == ResultCategory.EPISODES) subtitle = episodeSubtitle(renderer, subtitle);
        addSearchResult(results, title, subtitle, artworkUrl, tapCommand, playCommand, category, groupAsTopResult);
    }

    private static void addTopSearchResult(List<SearchResult> results, ProtoMessage renderer) {
        String title = renderer.stringField(RESULT_TITLE);
        ProtoMessage tapCommand = renderer.messagePath(TOP_RESULT_TAP, INNERTUBE_COMMAND);
        boolean tapStartsPlayback = isPlayable(tapCommand);
        if (title.isEmpty() || (!tapStartsPlayback && tapCommand.messageField(BROWSE).isEmpty())) return;
        String artworkUrl = artwork(renderer.messagePath(RESULT_IMAGE, IMAGE_CONTENT));
        String subtitle = renderer.stringField(RESULT_SUBTITLE);
        ResultCategory category = resultCategory(tapCommand);
        ProtoMessage menu = tapStartsPlayback ? ProtoMessage.EMPTY : renderer.messagePath(TOP_RESULT_MENU,
                INNERTUBE_COMMAND, MENU_ENDPOINT, MENU_CONTENT, MENU);
        ProtoMessage playCommand = playCommand(tapCommand, menu, renderer, true);
        if (!tapStartsPlayback) subtitle = podcastSubtitle(menu, category, subtitle);
        if (category == ResultCategory.EPISODES) subtitle = episodeSubtitle(renderer, subtitle);
        addSearchResult(results, title, subtitle, artworkUrl, tapCommand, playCommand, category, true);
    }

    private static void addSearchResult(List<SearchResult> results, String title, String subtitle, String artworkUrl,
                                      ProtoMessage tapCommand, ProtoMessage playCommand,
                                      ResultCategory category, boolean groupAsTopResult) {
        boolean podcast = category == ResultCategory.PODCASTS;
        if (!isPlayable(playCommand) && podcast) {
            String browseId = tapCommand.messageField(BROWSE).stringField(BROWSE_ID);
            playCommand = new ProtoMessage(AndroidAutoPatch.orderedPlaylistCommand(browseId.substring(4), false));
        }
        boolean uploadedCollection = category == ResultCategory.UPLOADED_ALBUMS
                || category == ResultCategory.UPLOADED_ARTISTS;
        if (uploadedCollection) playCommand = new ProtoMessage(AndroidAutoPatch.orderedPlaylistCommand(
                playCommand.messageField(WATCH_PLAYLIST).stringField(PLAYLIST_ID), true));
        results.add(new SearchResult(title, subtitle, artworkUrl,
                playCommand.serializedBytes(), isPlayable(tapCommand) || podcast || uploadedCollection,
                groupAsTopResult ? ResultCategory.TOP_RESULT : category));
    }

    private static String podcastSubtitle(ProtoMessage menu, ResultCategory category, String subtitle) {
        if (category != ResultCategory.PODCASTS && category != ResultCategory.EPISODES) return subtitle;
        String creator = menuSubtitle(menu);
        if (creator.isEmpty()) return subtitle;
        return category == ResultCategory.PODCASTS || subtitle.isEmpty() ? creator : subtitle + " • " + creator;
    }

    private static ResultCategory resultCategory(ProtoMessage tapCommand) {
        String browseId = tapCommand.messageField(BROWSE).stringField(BROWSE_ID);
        if (browseId.startsWith("FEmusic_library_privately_owned_release_detail")) return ResultCategory.UPLOADED_ALBUMS;
        if (browseId.startsWith("FEmusic_library_privately_owned_artist")) return ResultCategory.UPLOADED_ARTISTS;
        if (browseId.startsWith("MPRE")) return ResultCategory.ALBUMS;
        if (browseId.startsWith("MPSP")) return ResultCategory.PODCASTS;
        if (browseId.startsWith("UC")) {
            // Artist and profile pages share the UC prefix; the page type distinguishes them.
            long pageType = tapCommand.messagePath(BROWSE, BROWSE_SUPPORTED_CONFIG, BROWSE_MUSIC_CONFIG).varintField(MUSIC_PAGE_TYPE);
            return pageType == PAGE_TYPE_PROFILE ? ResultCategory.OTHER : ResultCategory.ARTISTS;
        }
        if (browseId.startsWith("VL")) return ResultCategory.PLAYLISTS;
        if (browseId.startsWith("MPED")) return ResultCategory.EPISODES;
        ProtoMessage watchCommand = tapCommand.messageField(WATCH);
        if (!watchCommand.isEmpty()) {
            // YTM uses a Watch command to play either a song or a video; MusicVideoType chooses the category heading.
            long videoType = watchCommand.messagePath(WATCH_SUPPORTED_CONFIG, WATCH_MUSIC_CONFIG).varintField(MUSIC_VIDEO_TYPE);
            if (videoType == VIDEO_TYPE_UPLOADED_TRACK) return ResultCategory.UPLOADED_SONGS;
            return videoType == VIDEO_TYPE_OFFICIAL_MUSIC_VIDEO || videoType == VIDEO_TYPE_USER_GENERATED
                    ? ResultCategory.VIDEOS : ResultCategory.SONGS;
        }
        return ResultCategory.OTHER;
    }

}
