/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.soundproof.data;

import android.util.JsonReader;
import android.util.JsonToken;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.soundproof.domain.ArtistDirectory;
import app.morphe.extension.music.patches.soundproof.domain.ArtistRecord;
import app.morphe.extension.shared.Logger;

/**
 * Reads the {@code artists.json} file of SoundProof-Dumps.
 * <p>
 * The file is a 1+ MB array, so it is streamed instead of loaded as a JSON tree. This is the
 * boundary of the feature: entries that break the {@link ArtistRecord} rules are dropped here,
 * while a malformed file fails as a whole so that it never replaces a good cached copy.
 */
final class SoundProofDumpParser {

    private SoundProofDumpParser() {
    }

    static ArtistDirectory parse(InputStream jsonStream) throws IOException {
        List<ArtistRecord> artists = new ArrayList<>();
        int droppedEntryCount = 0;
        try (JsonReader reader = new JsonReader(new InputStreamReader(jsonStream, StandardCharsets.UTF_8))) {
            reader.beginArray();
            while (reader.hasNext()) {
                ArtistRecord artist = readEntry(reader);
                if (artist == null) {
                    droppedEntryCount++;
                } else {
                    artists.add(artist);
                }
            }
            reader.endArray();
        } catch (IllegalStateException | NumberFormatException ex) {
            // JsonReader reports unexpected tokens with unchecked exceptions.
            throw new IOException("Malformed SoundProof dump", ex);
        }
        final int dropped = droppedEntryCount;
        Logger.printDebug(() -> "SoundProof dump parsed: " + artists.size() + " artists, " + dropped + " dropped");
        return new ArtistDirectory(artists);
    }

    /**
     * @return The entry, or null when it is incomplete or invalid.
     */
    @Nullable
    private static ArtistRecord readEntry(JsonReader reader) throws IOException {
        @Nullable String id = null;
        @Nullable String name = null;
        int humanVotes = -1;
        int aiVotes = -1;
        boolean verified = false;

        reader.beginObject();
        while (reader.hasNext()) {
            String field = reader.nextName();
            if (reader.peek() == JsonToken.NULL) {
                reader.skipValue();
                continue;
            }
            switch (field) {
                case "id" -> id = reader.nextString();
                case "name" -> name = reader.nextString();
                case "human_votes" -> humanVotes = reader.nextInt();
                case "ai_votes" -> aiVotes = reader.nextInt();
                case "verified" -> verified = reader.nextBoolean();
                default -> reader.skipValue();
            }
        }
        reader.endObject();

        return toRecordOrNull(id, name, humanVotes, aiVotes, verified);
    }

    @Nullable
    private static ArtistRecord toRecordOrNull(@Nullable String id, @Nullable String name,
                                               int humanVotes, int aiVotes, boolean verified) {
        if (id == null || name == null) {
            return null;
        }
        try {
            return new ArtistRecord(id, name, humanVotes, aiVotes, verified);
        } catch (IllegalArgumentException ex) {
            Logger.printDebug(() -> "SoundProof entry dropped: " + ex.getMessage());
            return null;
        }
    }
}
