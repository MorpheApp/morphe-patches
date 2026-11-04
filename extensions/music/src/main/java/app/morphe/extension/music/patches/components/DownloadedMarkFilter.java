/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4414
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.components;

import androidx.annotation.Nullable;

import com.google.protobuf.WireFormat;

import java.nio.charset.StandardCharsets;

import app.morphe.extension.music.patches.DownloadsPatch;
import app.morphe.extension.music.patches.downloads.DownloadedMark;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;

/**
 * Finds the song rows of a downloaded track, so the Downloads patch can mark their texts.
 * Nothing is hidden here.
 */
@SuppressWarnings("unused")
public final class DownloadedMarkFilter extends Filter {

    /** In the path of a song row and of every component inside it. */
    public static final String SONG_ROW_PATH = "music_list_item.";
    /**
     * Separators after {@link #SONG_ROW_PATH} in the path of the row itself, such as
     * "music_list_item.eml-fe|e1bdbb31ea4fcde4|CellType|". The components inside the row have more.
     */
    private static final int ROW_PATH_SEPARATORS = 3;

    /** A one byte tag and a one byte length, which is all a field of a video id needs. */
    private static final int FIELD_HEADER_LENGTH = 2;

    /**
     * The downloaded song of the row being built on this thread, or null if the row is not of a
     * downloaded song. The components of a row are built one after the other on the same thread,
     * the row itself first, and each text right after its own component.
     */
    private static final ThreadLocal<String> downloadedRow = new ThreadLocal<>();

    public DownloadedMarkFilter() {
        addPathCallbacks(new StringFilterGroup(Settings.DOWNLOADS_MARK, SONG_ROW_PATH));
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface, String identifier, String accessibility,
                              CharSequence path, byte[] buffer, BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup, FilterContentType contentType,
                              int contentIndex) {
        if (!DownloadsPatch.inAppDownloads()) return false;

        if (isRow(path, contentIndex)) {
            String videoId = downloadedIdIn(buffer);
            downloadedRow.set(videoId);
            if (videoId != null) Logger.printDebug(() -> "Downloaded song row of " + videoId);
        }
        return false;
    }

    private static boolean isRow(CharSequence path, int matchIndex) {
        int separators = 0;
        for (int i = matchIndex, length = path.length(); i < length; i++) {
            if (path.charAt(i) == '|') separators++;
        }
        return separators == ROW_PATH_SEPARATORS;
    }

    /**
     * @return The downloaded song of the row whose text is being built on this thread, or null if
     *         the row is not of a downloaded song.
     */
    @Nullable
    public static String downloadedRow() {
        return downloadedRow.get();
    }

    /**
     * Looks through the component of a song row for a video id of a downloaded track. The row
     * holds its song in the video id of its play endpoint and of the endpoints of its menu, each
     * a length delimited field of 11 characters.
     *
     * @return The video id, or null if the row is not of a downloaded song.
     */
    @Nullable
    private static String downloadedIdIn(byte[] buffer) {
        final int videoIdLength = SongMenuFilter.VIDEO_ID_LENGTH;
        for (int i = 0, last = buffer.length - FIELD_HEADER_LENGTH - videoIdLength; i <= last; i++) {
            if (buffer[i + 1] != videoIdLength || !isStringFieldTag(buffer[i])) continue;
            final int start = i + FIELD_HEADER_LENGTH;
            if (!SongMenuFilter.isVideoId(buffer, start)) continue;

            String videoId = new String(buffer, start, videoIdLength, StandardCharsets.US_ASCII);
            if (DownloadedMark.isDownloaded(videoId)) return videoId;
        }
        return null;
    }

    /** A single byte tag, of the fields 1 to 15, with the length delimited wire type. */
    private static boolean isStringFieldTag(byte tag) {
        return tag > 0 && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED;
    }
}
