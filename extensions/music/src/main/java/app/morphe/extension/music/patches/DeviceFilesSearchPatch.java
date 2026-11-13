/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4725
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Device files search with the media provider, because GmsCore has no app search index.
 */
@SuppressWarnings("unused")
public final class DeviceFilesSearchPatch {

    /**
     * Search index types of the Device files search tabs.
     */
    private static final String TYPE_SONGS = "internal.3p:MusicRecording";
    private static final String TYPE_ALBUMS = "internal.3p:MusicAlbum";
    private static final String TYPE_ARTISTS = "internal.3p:MusicGroup";

    /**
     * Media provider query parameter that matches each word of the query
     * against the title, album and artist.
     */
    private static final String FILTER_QUERY_PARAMETER = "filter";

    private static final String[] ID_PROJECTION = {MediaStore.Audio.AudioColumns._ID};

    private static final String SONGS_SELECTION = MediaStore.Audio.AudioColumns.IS_MUSIC
            + " != 0 AND " + MediaStore.Audio.AudioColumns.TITLE + " IS NOT NULL";

    /**
     * Injection point.
     *
     * @return Search the app runs on its own executor, with the content uris of the results.
     */
    public static Callable<List<Uri>> search(String query, String[] types) {
        return () -> {
            try {
                List<Uri> results = new ArrayList<>();
                for (String type : types) {
                    switch (type) {
                        case TYPE_SONGS -> addResults(results,
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, query, SONGS_SELECTION);
                        case TYPE_ALBUMS -> addResults(results,
                                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, query, null);
                        case TYPE_ARTISTS -> addResults(results,
                                MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI, query, null);
                        default -> throw new IllegalArgumentException("Unknown search type: " + type);
                    }
                }
                return results;
            } catch (Exception ex) {
                Logger.printException(() -> "search failure", ex);
                throw ex;
            }
        };
    }

    private static void addResults(List<Uri> results, Uri collection, String query,
                                   @Nullable String selection) {
        Uri filtered = collection.buildUpon()
                .appendQueryParameter(FILTER_QUERY_PARAMETER, query)
                .build();

        try (Cursor cursor = Utils.getContext().getContentResolver().query(
                filtered, ID_PROJECTION, selection, null, null)) {
            if (cursor == null) return;

            while (cursor.moveToNext()) {
                results.add(ContentUris.withAppendedId(collection, cursor.getLong(0)));
            }
        }
    }
}
