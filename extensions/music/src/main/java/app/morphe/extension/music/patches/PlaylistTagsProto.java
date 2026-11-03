/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4304
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import androidx.annotation.Nullable;

import com.google.protobuf.WireFormat;

import app.morphe.extension.shared.Logger;

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
        } catch (IllegalArgumentException ex) {
            // Items that are not a grid item, such as elements. The whole item is scanned instead.
            Logger.printDebug(() -> "Could not read the grid item fields: " + ex.getMessage());
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
     * Scanning a length delimited field stops where it does not parse as a message, such as a string.
     */
    private static void scan(byte[] data, int start, int end, int depth, ScanResult result) {
        if (depth > MAX_SCAN_DEPTH) return;

        WireReader reader = new WireReader(data, start, end);
        while (reader.position < end) {
            long readTag = reader.readTag();
            if (readTag < 0) return;
            int tag = (int) readTag;

            int wireType = WireFormat.getTagWireType(tag);
            if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                if (!reader.skipValue(wireType)) return;
                continue;
            }

            int valueStart = reader.readLengthDelimited();
            if (valueStart < 0) return;
            int valueEnd = reader.position;
            int length = valueEnd - valueStart;

            int fieldNumber = WireFormat.getTagFieldNumber(tag);
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
    }

    /**
     * @return The first string of a field in a message, or null if the message has none or does not parse.
     */
    @Nullable
    private static String readStringField(byte[] data, int start, int end, int fieldNumber) {
        WireReader reader = new WireReader(data, start, end);
        while (reader.position < end) {
            long readTag = reader.readTag();
            if (readTag < 0) return null;
            int tag = (int) readTag;

            int wireType = WireFormat.getTagWireType(tag);
            if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                if (!reader.skipValue(wireType)) return null;
                continue;
            }

            int valueStart = reader.readLengthDelimited();
            if (valueStart < 0) return null;
            if (WireFormat.getTagFieldNumber(tag) == fieldNumber) {
                return new String(data, valueStart, reader.position - valueStart, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * Reads protobuf wire data that may not be a message, such as a string or packed values,
     * the same way as CodedInputStream. Instead of throwing, a read returns -1 or false if the data
     * does not parse. Groups are not read, because the protos of the app do not use them.
     */
    private static final class WireReader {
        /**
         * Longest varint. A longer one is malformed.
         */
        private static final int MAX_VARINT_BYTES = 10;

        private final byte[] data;
        private final int end;
        int position;

        WireReader(byte[] data, int start, int end) {
            this.data = data;
            this.position = start;
            this.end = end;
        }

        /**
         * @return The lower 32 bits of the varint as an unsigned value, or -1 if the varint is malformed.
         */
        private long readVarint32() {
            long result = 0;
            for (int i = 0; i < MAX_VARINT_BYTES && position < end; i++) {
                byte b = data[position++];
                result |= (long) (b & 0x7F) << (7 * i);
                if (b >= 0) return result & 0xFFFFFFFFL;
            }
            return -1;
        }

        /**
         * @return The tag as an unsigned value, or -1 if it is malformed.
         */
        long readTag() {
            long tag = readVarint32();
            return tag < 0 || WireFormat.getTagFieldNumber((int) tag) == 0 ? -1 : tag;
        }

        /**
         * Skips a length delimited value.
         *
         * @return The start of the value, or -1 if it is malformed.
         */
        int readLengthDelimited() {
            long length = readVarint32();
            if (length < 0 || length > end - position) return -1;
            int valueStart = position;
            position += (int) length;
            return valueStart;
        }

        /**
         * Skips a value that is not length delimited.
         *
         * @return False if the value is malformed or is a group.
         */
        boolean skipValue(int wireType) {
            switch (wireType) {
                case WireFormat.WIRETYPE_VARINT:
                    return readVarint32() >= 0;
                case WireFormat.WIRETYPE_FIXED64:
                    return skipBytes(8);
                case WireFormat.WIRETYPE_FIXED32:
                    return skipBytes(4);
                default:
                    return false;
            }
        }

        private boolean skipBytes(int count) {
            if (count > end - position) return false;
            position += count;
            return true;
        }
    }
}
