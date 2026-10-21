/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3852
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AndroidAutoBrowseResponseParser {
    // Protobuf field numbers for YTM's phone Home, Library, and search responses.
    private static final int BROWSE_CONTENTS = 9;
    private static final int BROWSE_CONTINUATION = 10;
    private static final int SINGLE_COLUMN_BROWSE = 58_173_949;
    private static final int BROWSE_TABS = 1;
    static final int TAB = 58_174_010;
    static final int TAB_CONTENTS = 4;

    static final int SECTION_LIST = 49_399_797;
    static final int SECTION_LIST_CONTENTS = 1;
    private static final int SECTION_LIST_CONTINUATIONS = 2;
    private static final int NEXT_CONTINUATION = 52_047_593;
    private static final int CONTINUATION_TOKEN = 1;

    // ItemSectionRenderer wraps child renderers, including rows, cards, and titled groups.
    static final int ITEM_SECTION = 50_195_462;
    static final int ITEM_SECTION_CONTENTS = 1;

    private static final int GRID = 51_805_506;
    private static final int GRID_ITEMS = 1;

    static final int ELEMENT = 153_515_154;
    static final int NEW_ELEMENT = 172_660_663;
    static final int ELEMENT_TYPE = 1;
    static final int COMPONENT_TYPE = 168_777_401;
    static final int COMPONENT_MODEL = 5;

    static final int MUSIC_SHELF = 91_303_872;
    static final int MUSIC_SHELF_CONTENTS = 2;
    private static final int PLAYLIST_SHELF = 175_617_300;
    private static final int PLAYLIST_SHELF_CONTENTS = 3;
    static final int SHELF_ITEM = 161_429_595;
    static final int SHELF_ITEM_IMAGE = 1;
    static final int SHELF_ITEM_TITLE = 3;
    static final int SHELF_ITEM_SUBTITLE = 4;
    static final int SHELF_ITEM_TAP = 5;
    static final int SHELF_ITEM_MENU = 9;

    static final int RESULT_ROWS = 410_090_561;
    static final int RESULT_ROWS_ITEMS = 1;
    static final int RESULT_IMAGE = 1;
    static final int RESULT_TITLE = 2;
    static final int RESULT_SUBTITLE = 3;
    static final int RESULT_TAP = 4;
    static final int RESULT_MENU = 5;

    static final int TOP_RESULT = 1_223;
    static final int TOP_RESULT_CONTENT = 1;
    static final int TOP_RESULT_PRIMARY = 5;
    static final int TOP_RESULT_RELATED = 7;
    static final int TOP_RESULT_TAP = 8;
    static final int TOP_RESULT_MENU = 9;
    private static final int TOP_RESULT_BUTTONS = 14;
    private static final int TOP_RESULT_BUTTON_ITEMS = 1;
    private static final int TOP_RESULT_BUTTON_CONTENT = 2;
    private static final int TOP_RESULT_BUTTON_TAP = 6;

    private static final int GRID_ITEM = 1_105;
    private static final int GRID_ITEM_CONTENT = 1;
    private static final int CARD_TITLE = 1;
    private static final int CARD_SUBTITLE = 2;
    private static final int CARD_IMAGE = 3;

    private static final int CARD_SHELF = 404_005_902;
    private static final int CARD_SHELF_CONTENT = 1;
    private static final int CARD_SHELF_ITEMS = 3;
    private static final int CARD_SHELF_HEADER = 5;
    private static final int ROW_SHELF = 405_953_475;
    private static final int ROW_SHELF_HEADER = 5;
    private static final int ROW_SHELF_ITEMS = 6;
    private static final int SHELF_HEADER_TITLE = 1;
    private static final int SHELF_HEADER_TAP = 5;

    private static final int SPEED_DIAL = 487_343_630;
    private static final int SPEED_DIAL_CONTENT = 5;
    private static final int SPEED_DIAL_ITEMS = 1;
    private static final int SPEED_DIAL_HEADER = 2;
    private static final int SPEED_DIAL_TITLE = 1;
    private static final int SPEED_DIAL_IMAGE = 2;
    private static final int SPEED_DIAL_MENU = 4;
    private static final int SPEED_DIAL_TAP = 9;
    private static final int SPEED_DIAL_PLAY = 10;

    static final int INNERTUBE_COMMAND = 169_495_254;
    static final int BROWSE = 48_687_626;
    static final int BROWSE_ID = 2;
    static final int WATCH = 48_687_757;
    private static final int WATCH_VIDEO_ID = 1;
    private static final int WATCH_PLAYLIST_ID = 2;
    static final int WATCH_PLAYLIST = 52_666_186;
    static final int PLAYLIST_ID = 1;

    static final int MENU_ENDPOINT = 98_150_882;
    static final int MENU_CONTENT = 1;
    static final int MENU = 66_439_850;
    private static final int MENU_ITEMS = 1;
    private static final int MENU_NAVIGATION_ITEM = 66_441_108;
    private static final int MENU_NAVIGATION_ENDPOINT = 3;
    private static final int MENU_SERVICE_ITEM = 66_441_155;
    private static final int MENU_TOGGLE_SERVICE_ITEM = 77_258_115;
    private static final int MENU_SERVICE_ENDPOINT = 3;

    private static final int YOUTUBE_MODEL = 413_471_385;
    private static final int VIEW_MODEL = 1;
    private static final int MENU_HEADER_CONTENT = 1_051;
    private static final int MENU_HEADER_SUBTITLE = 3;

    private static final int MENU_BUTTON_CAROUSEL = 33_556_355;
    private static final int MENU_CAROUSEL_BUTTONS = 4;
    private static final int MENU_BUTTON_TAP = 3;

    private static final int SERIAL_COMMAND = 170_382_688;
    private static final int SERIAL_COMMANDS = 1;
    private static final int QUEUE_ADD = 163_162_354;
    private static final int QUEUE_ADD_ITEMS = 1;
    private static final int QUEUE_ITEM_COMMAND = 4;

    private static final int TEXT_RUNS = 1;
    private static final int TEXT_RUN_TEXT = 1;
    static final int TEXT_CONTENT = 1;

    static final int IMAGE_CONTENT = 1;
    static final int THUMBNAIL = 164_480_666;
    static final int THUMBNAIL_CONTENTS = 1;
    private static final int THUMBNAILS = 1;
    private static final int THUMBNAIL_URL = 1;

    private static final int EPISODE_PROGRESS = 18;
    private static final int EPISODE_PROGRESS_CONTAINER = 23;
    private static final int EPISODE_NESTED_PROGRESS = 9;
    private static final int PROGRESS_DURATION = 2;
    private static final int PROGRESS_FALLBACK_DURATION = 6;

    /**
     * Phone album taps open the track list; playCommandBytes can hold a Play action for Auto to use instead.
     * shelfTitle groups Home recommendations under headings such as Quick picks.
     */
    record BrowseItem(String title, String subtitle, String artworkUrl, byte[] tapCommandBytes, boolean tapStartsPlayback,
                      byte[] playCommandBytes, String shelfTitle, String shelfBrowseId) {
        BrowseItem(String title, String subtitle, String artworkUrl, byte[] tapCommandBytes, boolean tapStartsPlayback,
                   byte[] playCommandBytes) {
            this(title, subtitle, artworkUrl, tapCommandBytes, tapStartsPlayback, playCommandBytes, "", "");
        }
    }

    record HomePage(List<BrowseItem> items, String continuation) {}

    /** Reads phone Home or Speed dial responses and returns playable items with the next continuation token. */
    static HomePage homePage(byte[] response) {
        ProtoMessage sectionList = sectionList(response);
        List<BrowseItem> items = new ArrayList<>();
        for (BrowseItem item : readSections(sectionList)) {
            String browseId = new ProtoMessage(item.tapCommandBytes()).messageField(BROWSE).stringField(BROWSE_ID);
            if (!item.tapStartsPlayback()
                    && !browseId.startsWith("UC")
                    && !browseId.startsWith("MPRE")
                    && !browseId.startsWith("VL")) {
                continue;
            }
            byte[] playCommandBytes = item.tapStartsPlayback() ? item.tapCommandBytes() : item.playCommandBytes();
            if (isPlayable(new ProtoMessage(playCommandBytes))) {
                items.add(item);
            }
        }
        String continuation = "";
        for (ProtoMessage command : sectionList.repeatedMessageField(SECTION_LIST_CONTINUATIONS)) {
            continuation = command.messageField(NEXT_CONTINUATION).stringField(CONTINUATION_TOKEN);
            if (!continuation.isEmpty()) break;
        }
        return new HomePage(List.copyOf(items), continuation);
    }

    /**
     * Reads responses for phone Library categories or podcast shows and returns their browse items.
     * The Playlists folder uses YTM's native grid loader instead.
     */
    static List<BrowseItem> libraryItems(byte[] response) {
        return readSections(sectionList(response));
    }

    // Response layouts shared by Home and Library.

    private static ProtoMessage sectionList(byte[] response) {
        ProtoMessage root = new ProtoMessage(response);
        // Home's later shelves arrive as a section-list continuation, without the initial tab wrapper.
        ProtoMessage continuedSections = root.messagePath(BROWSE_CONTINUATION, SECTION_LIST);
        if (!continuedSections.isEmpty()) return continuedSections;
        ProtoMessage pageContents = root.messageField(BROWSE_CONTENTS);
        ProtoMessage sectionList = pageContents.messageField(SECTION_LIST);
        if (sectionList.isEmpty()) {
            for (ProtoMessage tabWrapper : pageContents.messageField(SINGLE_COLUMN_BROWSE).repeatedMessageField(BROWSE_TABS)) {
                sectionList = tabWrapper.messagePath(TAB, TAB_CONTENTS, SECTION_LIST);
                if (!sectionList.isEmpty()) break;
            }
        }
        if (sectionList.isEmpty()) throw new IllegalArgumentException("Unrecognized browse contents");
        return sectionList;
    }

    private static List<BrowseItem> readSections(ProtoMessage sectionList) {
        List<BrowseItem> items = new ArrayList<>();
        for (ProtoMessage sectionRenderer : sectionList.repeatedMessageField(SECTION_LIST_CONTENTS)) {
            readShelf(items, sectionRenderer.messageField(MUSIC_SHELF), MUSIC_SHELF_CONTENTS);
            readShelf(items, sectionRenderer.messageField(PLAYLIST_SHELF), PLAYLIST_SHELF_CONTENTS);
            List<ProtoMessage> childRenderers = new ArrayList<>(sectionRenderer.messageField(ITEM_SECTION).repeatedMessageField(ITEM_SECTION_CONTENTS));
            childRenderers.addAll(sectionRenderer.messageField(GRID).repeatedMessageField(GRID_ITEMS));
            for (ProtoMessage childRenderer : childRenderers) {
                ProtoMessage model = childRenderer.messagePath(ELEMENT, NEW_ELEMENT, ELEMENT_TYPE, COMPONENT_TYPE, COMPONENT_MODEL);
                int firstShelfItemIndex = items.size();
                String shelfTitle = model.messagePath(SPEED_DIAL, SPEED_DIAL_CONTENT, SPEED_DIAL_HEADER)
                        .stringField(SHELF_HEADER_TITLE);
                if (shelfTitle.isEmpty()) {
                    shelfTitle = model.messagePath(CARD_SHELF, CARD_SHELF_CONTENT, CARD_SHELF_HEADER)
                            .stringField(SHELF_HEADER_TITLE);
                }
                if (shelfTitle.isEmpty()) {
                    shelfTitle = model.messagePath(ROW_SHELF, ROW_SHELF_HEADER).stringField(SHELF_HEADER_TITLE);
                }
                String shelfBrowseId = model.messagePath(SPEED_DIAL, SPEED_DIAL_CONTENT, SPEED_DIAL_HEADER,
                        SHELF_HEADER_TAP, INNERTUBE_COMMAND, BROWSE).stringField(BROWSE_ID);
                readSpeedDial(items, model.messagePath(SPEED_DIAL, SPEED_DIAL_CONTENT));
                // Full collection pages use grid cards rather than Home's compact cards.
                addGridCard(items, model.messagePath(GRID_ITEM, GRID_ITEM_CONTENT));
                for (ProtoMessage row : model.messageField(RESULT_ROWS).repeatedMessageField(RESULT_ROWS_ITEMS)) {
                    addBrowseRow(items, row);
                }
                readTopResult(items, model.messagePath(TOP_RESULT, TOP_RESULT_CONTENT));
                for (ProtoMessage row : model.messageField(ROW_SHELF).repeatedMessageField(ROW_SHELF_ITEMS))
                    addBrowseRow(items, row);
                for (ProtoMessage card : model.messagePath(CARD_SHELF, CARD_SHELF_CONTENT).repeatedMessageField(CARD_SHELF_ITEMS)) {
                    addGridCard(items, card);
                }
                setShelfMetadata(items, firstShelfItemIndex, shelfTitle, shelfBrowseId);
            }
        }
        return Collections.unmodifiableList(items);
    }

    private static void readSpeedDial(List<BrowseItem> items, ProtoMessage speedDial) {
        for (ProtoMessage card : speedDial.repeatedMessageField(SPEED_DIAL_ITEMS)) {
            ProtoMessage tapCommand = card.messagePath(SPEED_DIAL_TAP, INNERTUBE_COMMAND);
            if (card.messageField(SPEED_DIAL_TAP).isEmpty()) {
                ProtoMessage playCommand = card.messagePath(SPEED_DIAL_PLAY, INNERTUBE_COMMAND);
                if (isPlayable(playCommand)) tapCommand = playCommand;
            }
            boolean tapStartsPlayback = isPlayable(tapCommand);
            if (!tapStartsPlayback && tapCommand.messageField(BROWSE).isEmpty()) continue;
            String title = card.stringField(SPEED_DIAL_TITLE);
            if (title.isEmpty()) continue;
            ProtoMessage menu = card.messagePath(SPEED_DIAL_MENU, INNERTUBE_COMMAND, MENU_ENDPOINT, MENU_CONTENT, MENU);
            String subtitle = menuSubtitle(menu);
            String artworkUrl = artwork(card.messagePath(SPEED_DIAL_IMAGE, IMAGE_CONTENT));
            byte[] tapCommandBytes = tapCommand.serializedBytes();
            byte[] playCommandBytes = card.messagePath(SPEED_DIAL_PLAY, INNERTUBE_COMMAND).serializedBytes();
            items.add(new BrowseItem(title, subtitle, artworkUrl, tapCommandBytes, tapStartsPlayback, playCommandBytes));
        }
    }

    private static void readTopResult(List<BrowseItem> items, ProtoMessage topResult) {
        addTopBrowseResult(items, topResult.messageField(TOP_RESULT_PRIMARY));
        for (ProtoMessage row : topResult.repeatedMessageField(TOP_RESULT_RELATED))
            addBrowseRow(items, row);
    }

    private static void setShelfMetadata(List<BrowseItem> items, int firstItemIndex, String shelfTitle, String shelfBrowseId) {
        if (shelfTitle.isEmpty()) return;
        for (int i = firstItemIndex; i < items.size(); i++) {
            BrowseItem item = items.get(i);
            items.set(i, new BrowseItem(item.title(), item.subtitle(), item.artworkUrl(), item.tapCommandBytes(),
                    item.tapStartsPlayback(), item.playCommandBytes(), shelfTitle, shelfBrowseId));
        }
    }

    private static void readShelf(List<BrowseItem> items, ProtoMessage shelf, int contentsField) {
        for (ProtoMessage childRenderer : shelf.repeatedMessageField(contentsField)) {
            ProtoMessage shelfItem = childRenderer.messageField(SHELF_ITEM);
            ProtoMessage tapCommand = shelfItem.messageField(SHELF_ITEM_TAP);
            boolean tapStartsPlayback = isPlayable(tapCommand);
            String title = textRuns(shelfItem.messageField(SHELF_ITEM_TITLE));
            if (title.isEmpty() || (!tapStartsPlayback && tapCommand.messageField(BROWSE).isEmpty())) continue;
            ProtoMessage menu = shelfItem.messagePath(SHELF_ITEM_MENU, MENU);
            items.add(new BrowseItem(title, textRuns(shelfItem.messageField(SHELF_ITEM_SUBTITLE)),
                    artwork(shelfItem.messagePath(SHELF_ITEM_IMAGE, THUMBNAIL, THUMBNAIL_CONTENTS)), tapCommand.serializedBytes(), tapStartsPlayback,
                    playCommand(tapCommand, menu, shelfItem, false).serializedBytes()));
        }
    }

    private static void addGridCard(List<BrowseItem> items, ProtoMessage renderer) {
        addRenderer(items, renderer, CARD_TITLE, CARD_SUBTITLE, CARD_IMAGE, RESULT_TAP, RESULT_MENU, false);
    }

    private static void addBrowseRow(List<BrowseItem> items, ProtoMessage renderer) {
        addRenderer(items, renderer, RESULT_TITLE, RESULT_SUBTITLE, RESULT_IMAGE, RESULT_TAP, RESULT_MENU, false);
    }

    private static void addTopBrowseResult(List<BrowseItem> items, ProtoMessage renderer) {
        addRenderer(items, renderer, RESULT_TITLE, RESULT_SUBTITLE, RESULT_IMAGE, TOP_RESULT_TAP, TOP_RESULT_MENU, true);
    }

    /** Grid cards, result rows, and top results differ only in the field numbers of their contents. */
    private static void addRenderer(List<BrowseItem> items, ProtoMessage renderer, int titleField, int subtitleField,
                                    int imageField, int tapField, int menuField, boolean topResultCard) {
        String title = renderer.stringField(titleField);
        ProtoMessage tapCommand = renderer.messagePath(tapField, INNERTUBE_COMMAND);
        boolean tapStartsPlayback = isPlayable(tapCommand);
        if (title.isEmpty() || (!tapStartsPlayback && tapCommand.messageField(BROWSE).isEmpty())) return;
        String artworkUrl = artwork(renderer.messagePath(imageField, IMAGE_CONTENT));
        ProtoMessage menu = tapStartsPlayback ? ProtoMessage.EMPTY : renderer.messagePath(menuField,
                INNERTUBE_COMMAND, MENU_ENDPOINT, MENU_CONTENT, MENU);
        ProtoMessage playCommand = playCommand(tapCommand, menu, renderer, topResultCard);
        String subtitle = renderer.stringField(subtitleField);
        if (tapCommand.messageField(BROWSE).stringField(BROWSE_ID).startsWith("MPED")) {
            subtitle = episodeSubtitle(renderer, subtitle);
        }
        items.add(new BrowseItem(title, subtitle, artworkUrl, tapCommand.serializedBytes(), tapStartsPlayback,
                playCommand.serializedBytes()));
    }

    // Text, artwork, and playback helpers also used by search.

    static ProtoMessage playCommand(ProtoMessage tapCommand, ProtoMessage menu, ProtoMessage renderer,
                                    boolean topResultCard) {
        if (isPlayable(tapCommand)) return tapCommand;
        ProtoMessage command = menuPlayCommand(menu);
        if (topResultCard) command = topResultPlayCommand(renderer, command);
        if (!isPlayable(command) && tapCommand.messageField(BROWSE).stringField(BROWSE_ID).startsWith("MPED"))
            command = episodePlayCommand(menu);
        return command;
    }

    static String menuSubtitle(ProtoMessage menu) {
        // Compact phone cards keep their creator or collection subtitle in the menu header.
        for (ProtoMessage menuItem : menu.repeatedMessageField(MENU_ITEMS)) {
            String subtitle = menuItem.messagePath(ELEMENT, NEW_ELEMENT, ELEMENT_TYPE, COMPONENT_TYPE, COMPONENT_MODEL,
                    YOUTUBE_MODEL, VIEW_MODEL, MENU_HEADER_CONTENT, MENU_HEADER_SUBTITLE).stringField(TEXT_CONTENT);
            if (!subtitle.isEmpty()) return subtitle;
        }
        return "";
    }

    static String textRuns(ProtoMessage text) {
        StringBuilder value = new StringBuilder();
        for (ProtoMessage run : text.repeatedMessageField(TEXT_RUNS)) value.append(run.stringField(TEXT_RUN_TEXT));
        return value.toString();
    }

    static String artwork(ProtoMessage thumbnails) {
        String url = "";
        for (ProtoMessage thumbnail : thumbnails.repeatedMessageField(THUMBNAILS)) {
            String candidate = thumbnail.stringField(THUMBNAIL_URL);
            if (candidate.startsWith("https://")) url = candidate;
        }
        return url;
    }

    // Top results expose their primary play action separately from the menu.
    static ProtoMessage topResultPlayCommand(ProtoMessage renderer, ProtoMessage fallback) {
        for (ProtoMessage button : renderer.messageField(TOP_RESULT_BUTTONS).repeatedMessageField(TOP_RESULT_BUTTON_ITEMS)) {
            ProtoMessage action = button.messagePath(TOP_RESULT_BUTTON_CONTENT, TOP_RESULT_BUTTON_TAP, INNERTUBE_COMMAND);
            if (isPlayable(action)) return action;
        }
        return fallback;
    }

    static String episodeSubtitle(ProtoMessage renderer, String subtitle) {
        // Phone episode cards keep localized duration and remaining time outside their subtitle.
        ProtoMessage progress = renderer.messageField(EPISODE_PROGRESS);
        if (progress.isEmpty()) progress = renderer.messagePath(EPISODE_PROGRESS_CONTAINER, EPISODE_NESTED_PROGRESS);
        String duration = progress.stringField(PROGRESS_DURATION);
        if (duration.isEmpty()) duration = progress.stringField(PROGRESS_FALLBACK_DURATION);
        if (duration.isEmpty() || subtitle.contains(duration)) return subtitle;
        return subtitle.isEmpty() ? duration : subtitle + " • " + duration;
    }

    /** Uploaded albums and artists need an ordered playlist command that marks the playlist as uploaded. */
    static boolean isUploadedCollection(String browseId) {
        return browseId.startsWith("FEmusic_library_privately_owned_release_detail")
                || browseId.startsWith("FEmusic_library_privately_owned_artist");
    }

    static boolean isPlayable(ProtoMessage command) {
        ProtoMessage watch = command.messageField(WATCH);
        return !watch.stringField(WATCH_VIDEO_ID).isEmpty() || !watch.stringField(WATCH_PLAYLIST_ID).isEmpty()
                || !command.messageField(WATCH_PLAYLIST).stringField(PLAYLIST_ID).isEmpty();
    }

    /** Reads an existing play command from the menu, avoiding a separate request for playback details. */
    static ProtoMessage menuPlayCommand(ProtoMessage menu) {
        for (ProtoMessage menuItem : menu.repeatedMessageField(MENU_ITEMS)) {
            ProtoMessage command = menuItem.messagePath(MENU_NAVIGATION_ITEM, MENU_NAVIGATION_ENDPOINT);
            if (isPlayable(command)) return command;
            for (ProtoMessage button : menuItem.messagePath(ELEMENT, NEW_ELEMENT, ELEMENT_TYPE, COMPONENT_TYPE,
                    COMPONENT_MODEL, MENU_BUTTON_CAROUSEL).repeatedMessageField(MENU_CAROUSEL_BUTTONS)) {
                for (ProtoMessage action : button.messagePath(MENU_BUTTON_TAP, SERIAL_COMMAND).repeatedMessageField(SERIAL_COMMANDS)) {
                    command = action.messageField(INNERTUBE_COMMAND);
                    if (isPlayable(command)) return command;
                }
            }
        }
        return ProtoMessage.EMPTY;
    }

    static ProtoMessage episodePlayCommand(ProtoMessage menu) {
        // Use the Watch command inside Play next or Add to queue to play the episode without adding it to an existing queue.
        for (ProtoMessage menuItem : menu.repeatedMessageField(MENU_ITEMS)) {
            for (int rendererField : new int[]{MENU_SERVICE_ITEM, MENU_TOGGLE_SERVICE_ITEM}) {
                for (ProtoMessage queueItem : menuItem.messagePath(rendererField, MENU_SERVICE_ENDPOINT, QUEUE_ADD).repeatedMessageField(QUEUE_ADD_ITEMS)) {
                    ProtoMessage command = queueItem.messageField(QUEUE_ITEM_COMMAND);
                    if (!command.messageField(WATCH).stringField(WATCH_VIDEO_ID).isEmpty()) return command;
                }
            }
        }
        return ProtoMessage.EMPTY;
    }

}
