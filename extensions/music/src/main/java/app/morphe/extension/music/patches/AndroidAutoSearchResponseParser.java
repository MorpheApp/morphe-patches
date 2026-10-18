/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.music.patches.AndroidAutoBrowseResponseParser.*;

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

    private static final int COMPACT_RESULT_CARD = 1_297;
    private static final int COMPACT_RESULT_CONTENT = 1;
    private static final int COMPACT_RESULT_HEADER = 1;
    private static final int COMPACT_RESULT_ROWS = 2;

    private static final int TOP_RESULT_COMPONENTS = 9;
    private static final int TOP_RESULT_CARD = 1;
    private static final int TOP_RESULT_RELATED_ROW = 2;

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
        for (ProtoMessage tabWrapper : new ProtoMessage(response).messagePath(SEARCH_CONTENTS, TABBED_SEARCH).repeatedMessageField(SEARCH_TABS)) {
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
            readShelf(results, sectionRenderer.messageField(MUSIC_SHELF));
            for (ProtoMessage childRenderer : sectionRenderer.messageField(ITEM_SECTION).repeatedMessageField(ITEM_SECTION_CONTENTS)) {
                ProtoMessage model = childRenderer.messagePath(ELEMENT, NEW_ELEMENT, ELEMENT_TYPE, COMPONENT_TYPE, COMPONENT_MODEL);
                for (ProtoMessage row : model.messageField(RESULT_ROWS)
                        .repeatedMessageField(RESULT_ROWS_ITEMS)) {
                    addSearchResult(results, row, RESULT_TAP, false);
                }
                ProtoMessage topResult = model.messagePath(TOP_RESULT, TOP_RESULT_CONTENT);
                addSearchResult(results, topResult.messageField(TOP_RESULT_PRIMARY), TOP_RESULT_TAP, true);
                for (ProtoMessage row : topResult.repeatedMessageField(TOP_RESULT_RELATED))
                    addSearchResult(results, row, RESULT_TAP, false);
                // Some responses put the top match and related results in a nested list.
                // Reading only the fields above would miss them.
                for (ProtoMessage component : topResult.repeatedMessageField(TOP_RESULT_COMPONENTS)) {
                    addSearchResult(results, component.messageField(TOP_RESULT_CARD), TOP_RESULT_TAP, true);
                    addSearchResult(results, component.messageField(TOP_RESULT_RELATED_ROW), RESULT_TAP, false);
                }
                // Compact cards also carry multi-row collections such as Listen again.
                // Only untitled cards are top results; named collections retain each row's category.
                ProtoMessage card = model.messagePath(COMPACT_RESULT_CARD, COMPACT_RESULT_CONTENT);
                boolean groupAsTopResult = card.messageField(COMPACT_RESULT_HEADER).stringField(TEXT_CONTENT).isEmpty();
                for (ProtoMessage row : card.repeatedMessageField(COMPACT_RESULT_ROWS))
                    addSearchResult(results, row, RESULT_TAP, groupAsTopResult);
            }
        }
        return Collections.unmodifiableList(results);
    }

    private static void readShelf(List<SearchResult> results, ProtoMessage shelf) {
        for (ProtoMessage childRenderer : shelf.repeatedMessageField(MUSIC_SHELF_CONTENTS)) {
            ProtoMessage shelfItem = childRenderer.messageField(SHELF_ITEM);
            ProtoMessage tapCommand = shelfItem.messageField(SHELF_ITEM_TAP);
            boolean tapStartsPlayback = isPlayable(tapCommand);
            String title = textRuns(shelfItem.messageField(SHELF_ITEM_TITLE));
            if (title.isEmpty() || (!tapStartsPlayback && tapCommand.messageField(BROWSE).isEmpty())) continue;
            // If tapping opens a page on the phone, use its menu's play action.
            ProtoMessage menu = shelfItem.messagePath(SHELF_ITEM_MENU, MENU);
            ProtoMessage playCommand = tapStartsPlayback ? tapCommand : menuPlayCommand(menu);
            ResultCategory category = resultCategory(tapCommand);
            if (!isPlayable(playCommand) && category == ResultCategory.EPISODES)
                playCommand = episodePlayCommand(menu);
            boolean podcast = category == ResultCategory.PODCASTS;
            if (!isPlayable(playCommand) && podcast) playCommand = podcastPlayCommand(tapCommand);
            boolean uploadedCollection = category == ResultCategory.UPLOADED_ALBUMS
                    || category == ResultCategory.UPLOADED_ARTISTS;
            if (uploadedCollection) playCommand = playlistPlayCommand(
                    playCommand.messageField(WATCH_PLAYLIST).stringField(PLAYLIST_ID), true);
            String subtitle = podcastSubtitle(menu, category, textRuns(shelfItem.messageField(SHELF_ITEM_SUBTITLE)));
            results.add(new SearchResult(title, subtitle,
                    artwork(shelfItem.messagePath(SHELF_ITEM_IMAGE, THUMBNAIL, THUMBNAIL_CONTENTS)),
                    playCommand.serializedBytes(), tapStartsPlayback || podcast || uploadedCollection, category));
        }
    }

    private static void addSearchResult(List<SearchResult> results, ProtoMessage renderer,
                                        int tapField, boolean groupAsTopResult) {
        boolean topResultCard = tapField == TOP_RESULT_TAP;
        String title = renderer.stringField(RESULT_TITLE);
        ProtoMessage tapCommand = renderer.messagePath(tapField, INNERTUBE_COMMAND);
        boolean tapStartsPlayback = isPlayable(tapCommand);
        if (title.isEmpty() || (!tapStartsPlayback && tapCommand.messageField(BROWSE).isEmpty())) return;
        String artworkUrl = artwork(renderer.messagePath(RESULT_IMAGE, IMAGE_CONTENT));
        String subtitle = renderer.stringField(RESULT_SUBTITLE);
        ResultCategory category = resultCategory(tapCommand);
        ProtoMessage playCommand = tapCommand;
        if (!tapStartsPlayback) {
            ProtoMessage menu = renderer.messagePath(
                    topResultCard ? TOP_RESULT_MENU : RESULT_MENU,
                    INNERTUBE_COMMAND, MENU_ENDPOINT, MENU_CONTENT, MENU);
            // Use the menu's play action without loading the result's phone page.
            playCommand = menuPlayCommand(menu);
            if (topResultCard) playCommand = topResultPlayCommand(renderer, playCommand);
            if (!isPlayable(playCommand) && category == ResultCategory.EPISODES)
                playCommand = episodePlayCommand(menu);
            subtitle = podcastSubtitle(menu, category, subtitle);
        }
        if (category == ResultCategory.EPISODES) subtitle = episodeSubtitle(renderer, subtitle);
        boolean podcast = category == ResultCategory.PODCASTS;
        if (!isPlayable(playCommand) && podcast) playCommand = podcastPlayCommand(tapCommand);
        boolean uploadedCollection = category == ResultCategory.UPLOADED_ALBUMS
                || category == ResultCategory.UPLOADED_ARTISTS;
        if (uploadedCollection) playCommand = playlistPlayCommand(
                playCommand.messageField(WATCH_PLAYLIST).stringField(PLAYLIST_ID), true);
        results.add(new SearchResult(title, subtitle, artworkUrl,
                playCommand.serializedBytes(), tapStartsPlayback || podcast || uploadedCollection,
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

    private static ProtoMessage podcastPlayCommand(ProtoMessage tapCommand) {
        String browseId = tapCommand.messageField(BROWSE).stringField(BROWSE_ID);
        return playlistPlayCommand(browseId.substring(4), false);
    }

    private static ProtoMessage playlistPlayCommand(String playlistId, boolean uploaded) {
        return new ProtoMessage(AndroidAutoPatch.orderedPlaylistCommand(playlistId, uploaded));
    }

}
