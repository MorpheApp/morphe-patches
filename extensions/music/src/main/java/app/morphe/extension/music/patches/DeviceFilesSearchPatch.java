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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** Supplies local IDs to YTM's existing Device Files result renderers. */
public final class DeviceFilesSearchPatch {
    private static final String MUSIC_SELECTION = "is_music=1 AND title IS NOT NULL";

    private DeviceFilesSearchPatch() {
    }

    /** Injection point. The app submits this query on its own search executor. */
    public static Callable<List<Uri>> createSearch(String query, String[] types) {
        return () -> {
            try {
                if (types.length != 1) {
                    throw new IllegalArgumentException("Expected one Device Files search type");
                }
                Uri collection;
                String selection = null;
                switch (types[0]) {
                    case "internal.3p:MusicRecording":
                        collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                        selection = MUSIC_SELECTION;
                        break;
                    case "internal.3p:MusicAlbum":
                        collection = MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI;
                        break;
                    case "internal.3p:MusicGroup":
                        collection = MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown Device Files search type");
                }
                // Let MediaProvider handle tokenization and matching, not a separate search index.
                Uri filtered = collection.buildUpon().appendQueryParameter("filter", query).build();
                List<Uri> results = new ArrayList<>();
                try (Cursor cursor = Utils.getContext().getContentResolver().query(
                        filtered, new String[]{"_id"}, selection, null, null)) {
                    if (cursor != null) {
                        while (cursor.moveToNext()) {
                            results.add(ContentUris.withAppendedId(collection, cursor.getLong(0)));
                        }
                    }
                }
                return results;
            } catch (Exception ex) {
                Logger.printException(() -> "Device Files search failure", ex);
                throw ex;
            }
        };
    }
}
