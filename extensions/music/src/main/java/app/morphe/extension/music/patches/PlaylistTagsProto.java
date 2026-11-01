/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import androidx.annotation.Nullable;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads playlist ids and titles from the serialized protos of Library grid items and flyout menus.
 */
final class PlaylistTagsProto {

    // Fields of the Library grid item (MusicTwoRowItemRenderer).
    private static final int GRID_ITEM_TITLE = 3;
    private static final int GRID_ITEM_NAVIGATION = 5;
    private static final int GRID_ITEM_SECONDARY_NAVIGATION = 10;

    // Fields of formatted text.
    private static final int TEXT_RUNS = 1;
    private static final int TEXT_RUN_TEXT = 1;

    // Command extensions. Same numbers as AndroidAutoBrowseResponseParser.
    private static final int BROWSE_ENDPOINT = 48_687_626;
    private static final int BROWSE_ID = 2;
    private static final int WATCH_PLAYLIST_ENDPOINT = 52_666_186;
    private static final int WATCH_PLAYLIST_ID = 1;

    /**
     * Browse id of a playlist page.
     */
    private static final String PLAYLIST_BROWSE_PREFIX = "VL";
    /**
     * Radio and mix playlists, which are generated from the item and are not the item itself.
     */
    private static final String RADIO_PLAYLIST_PREFIX = "RD";
    /**
     * Album playlists. Albums are shown with their album browse id in the Library, so they can't be filtered.
     */
    private static final String ALBUM_PLAYLIST_PREFIX = "OLAK5uy";
    private static final Pattern PLAYLIST_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{2,}");

    /**
     * Nesting limit of the generic scan, so malformed data cannot recurse deeply.
     */
    private static final int MAX_SCAN_DEPTH = 32;

    /**
     * Longest value the menu scan compares with the known playlists. Playlist ids are about 34 characters.
     */
    private static final int MAX_ID_LENGTH = 64;

    record GridItem(String playlistId, String title) {
    }

    private PlaylistTagsProto() {
    }

    /**
     * @return The playlist of a Library grid item, or null if the item is not a playlist,
     *         such as an artist or a podcast.
     */
    @Nullable
    static GridItem readGridItem(byte[] item) {
        String playlistId = null;
        String title = "";
        try {
            ProtoMessage message = new ProtoMessage(item);
            for (int field : new int[]{GRID_ITEM_NAVIGATION, GRID_ITEM_SECONDARY_NAVIGATION}) {
                playlistId = playlistIdFromBrowseId(
                        message.messagePath(field, BROWSE_ENDPOINT).stringField(BROWSE_ID));
                if (playlistId != null) break;
            }

            StringBuilder text = new StringBuilder();
            for (ProtoMessage run : message.messageField(GRID_ITEM_TITLE).repeatedMessageField(TEXT_RUNS)) {
                text.append(run.stringField(TEXT_RUN_TEXT));
            }
            title = text.toString();
        } catch (IllegalArgumentException ignored) {
            // The field layout changed. Fall back to scanning the whole item.
        }

        if (playlistId == null) {
            ScanResult scan = new ScanResult();
            scan(item, 0, item.length, 0, scan);
            playlistId = scan.firstBrowsePlaylistId;
        }

        return playlistId == null ? null : new GridItem(playlistId, title);
    }

    /**
     * @param knownPlaylistIds Playlists seen in the Library. The menu of an empty playlist has no
     *                         action to play or open it, but it keeps the id in other actions.
     * @return The playlist a flyout menu belongs to, or null if the menu is not for a playlist.
     */
    @Nullable
    static String findMenuPlaylistId(byte[] menu, Set<String> knownPlaylistIds) {
        ScanResult scan = new ScanResult();
        scan(menu, 0, menu.length, 0, scan);

        String best = null;
        int bestScore = 0;
        for (Map.Entry<String, Integer> entry : scan.scores.entrySet()) {
            if (entry.getValue() > bestScore) {
                best = entry.getKey();
                bestScore = entry.getValue();
            }
        }
        if (best != null) return best;

        for (String text : scan.texts) {
            if (knownPlaylistIds.contains(text)) return text;
            String id = playlistIdFromBrowseId(text);
            if (id != null && knownPlaylistIds.contains(id)) return id;
        }
        return null;
    }

    @Nullable
    private static String playlistIdFromBrowseId(@Nullable String browseId) {
        if (browseId == null || !browseId.startsWith(PLAYLIST_BROWSE_PREFIX)) return null;
        String id = browseId.substring(PLAYLIST_BROWSE_PREFIX.length());
        return isPlaylistId(id) ? id : null;
    }

    private static boolean isPlaylistId(String id) {
        return !id.startsWith(RADIO_PLAYLIST_PREFIX)
                && !id.startsWith(ALBUM_PLAYLIST_PREFIX)
                && PLAYLIST_ID_PATTERN.matcher(id).matches();
    }

    private static final class ScanResult {
        @Nullable
        String firstBrowsePlaylistId;
        /**
         * Playlist id to score. A browse endpoint to the playlist page counts more than a play action.
         */
        final Map<String, Integer> scores = new HashMap<>();
        /**
         * Short values that look like ids, in the order they appear.
         */
        final Set<String> texts = new LinkedHashSet<>();

        void add(String playlistId, int score) {
            Integer current = scores.get(playlistId);
            scores.put(playlistId, current == null ? score : current + score);
        }
    }

    /**
     * Walks every nested message and collects playlist browse endpoints and watch playlist endpoints.
     * Length delimited fields that do not parse as a message are skipped.
     */
    private static void scan(byte[] data, int start, int end, int depth, ScanResult result) {
        if (depth > MAX_SCAN_DEPTH) return;
        try {
            CodedInputStream input = CodedInputStream.newInstance(data, start, end - start);
            for (int tag; (tag = input.readTag()) != 0; ) {
                int fieldNumber = WireFormat.getTagFieldNumber(tag);
                int wireType = WireFormat.getTagWireType(tag);
                if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    if (wireType == WireFormat.WIRETYPE_START_GROUP
                            || wireType == WireFormat.WIRETYPE_END_GROUP) {
                        return;
                    }
                    input.skipField(tag);
                    continue;
                }

                int length = input.readRawVarint32();
                int valueStart = start + input.getTotalBytesRead();
                if (length < 0 || valueStart + length > end) return;
                input.skipRawBytes(length);
                int valueEnd = valueStart + length;

                if (fieldNumber == BROWSE_ENDPOINT) {
                    String id = playlistIdFromBrowseId(readStringField(data, valueStart, valueEnd, BROWSE_ID));
                    if (id != null) {
                        if (result.firstBrowsePlaylistId == null) result.firstBrowsePlaylistId = id;
                        result.add(id, 2);
                    }
                } else if (fieldNumber == WATCH_PLAYLIST_ENDPOINT) {
                    String id = readStringField(data, valueStart, valueEnd, WATCH_PLAYLIST_ID);
                    if (id != null && isPlaylistId(id)) result.add(id, 1);
                } else if (length >= 2 && length <= MAX_ID_LENGTH) {
                    String text = new String(data, valueStart, length, StandardCharsets.UTF_8);
                    if (PLAYLIST_ID_PATTERN.matcher(text).matches()) result.texts.add(text);
                }

                scan(data, valueStart, valueEnd, depth + 1, result);
            }
        } catch (Exception ignored) {
            // Not a message, such as a string or packed values.
        }
    }

    @Nullable
    private static String readStringField(byte[] data, int start, int end, int fieldNumber) {
        try {
            CodedInputStream input = CodedInputStream.newInstance(data, start, end - start);
            for (int tag; (tag = input.readTag()) != 0; ) {
                if (WireFormat.getTagFieldNumber(tag) == fieldNumber
                        && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    int length = input.readRawVarint32();
                    int valueStart = start + input.getTotalBytesRead();
                    if (length < 0 || valueStart + length > end) return null;
                    return new String(data, valueStart, length, StandardCharsets.UTF_8);
                }
                input.skipField(tag);
            }
        } catch (Exception ignored) {
            // Malformed.
        }
        return null;
    }
}
