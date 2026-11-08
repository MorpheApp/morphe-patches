/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3851
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** A {@link WordReadingTable} in the dictionary directory, opened on first use and kept open. */
final class WordTableFile {

    /** How many entries a source reads between two progress reports. */
    static final int PROGRESS_EVERY = 20000;
    private final String name;
    private final byte[] magic;
    private final int slotCount;
    @Nullable
    private volatile WordReadingTable table;

    /**
     * @param name      File name, which carries the source version so a new one is rebuilt.
     * @param magic     Says which dictionary wrote the file.
     * @param slotCount Room to spare, since streaming a source does not say how many entries
     *                  there will be.
     */
    WordTableFile(String name, byte[] magic, int slotCount) {
        this.name = name;
        this.magic = magic;
        this.slotCount = slotCount;
    }

    @Nullable
    File file() {
        File directory = RomanizationDict.directory();
        return directory == null ? null : new File(directory, name);
    }

    /** Null when the table has not been built. */
    @Nullable
    WordReadingTable open() {
        WordReadingTable open = table;
        if (open != null) return open;

        File file = file();
        if (file == null) return null;

        if (!file.isFile()) {
            unpackFromAssetsIfPresent(file);
        }

        if (!file.isFile()) {
            return null;
        }

        if (file.length() < WordReadingTable.HEADER_BYTES) {
            Logger.printDebug(() -> "Reading table file is truncated or empty, ignoring: " + file);
            return null;
        }

        open = WordReadingTable.open(file.toPath(), magic);
        if (open != null) {
            table = open;
        }
        return open;
    }

    private void unpackFromAssetsIfPresent(File target) {
        if (target == null || target.isFile()) {
            return;
        }
        Context context = Utils.getContext();
        if (context == null) {
            return;
        }
        AssetManager assets = context.getAssets();
        if (assets == null) {
            return;
        }
        unpackFromAssets(assets, name, target);
    }

    static boolean unpackFromAssets(AssetManager assets, String name, File target) {
        if (assets == null || target == null || name == null) {
            return false;
        }
        String[] possiblePaths = new String[]{
                "morphe_lyrics/romandict/" + name,
                "romandict/" + name,
                name
        };
        for (String assetPath : possiblePaths) {
            try (InputStream in = assets.open(assetPath)) {
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    return false;
                }
                File unpacking = new File(target.getPath() + ".unpacking");
                try (OutputStream out = new FileOutputStream(unpacking)) {
                    byte[] buffer = new byte[1 << 16];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }
                if (unpacking.length() >= WordReadingTable.HEADER_BYTES) {
                    RomanizationDict.commit(unpacking, target);
                    Logger.printDebug(() -> "Extracted dictionary from assets: " + assetPath + " -> " + target);
                    return true;
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    unpacking.delete();
                }
            } catch (IOException ignored) {
                // Not present under this path in assets, try next
            }
        }
        return false;
    }

    /** Drops the cached table so a later open re-reads from disk, or finds nothing. */
    void clear() {
        table = null;
    }

    WordReadingTable.Builder newBuilder(File built) throws IOException {
        return new WordReadingTable.Builder(built.toPath(), magic, slotCount);
    }

    /** Downloads the source unless an earlier attempt left it behind, and builds the table. */
    void build(File directory, String url, String archiveName, Source source,
               RomanizationDict.Listener listener) throws IOException {
        build(directory, url, archiveName, source, listener, Long.MAX_VALUE);
    }

    /**
     * As {@link #build(File, String, String, Source, RomanizationDict.Listener)}, refusing a
     * source larger than {@code maxBytes}.
     */
    void build(File directory, String url, String archiveName, Source source,
               RomanizationDict.Listener listener, long maxBytes) throws IOException {
        File target = new File(directory, name);
        if (target.isFile()) return;

        File archive = new File(directory, archiveName);
        if (!archive.isFile()) {
            RomanizationDict.download(url, archive, archiveName, listener, maxBytes);
        }

        File built = new File(directory, name + ".building");
        WordReadingTable.Builder builder = newBuilder(built);
        try {
            source.addTo(archive, builder, listener);
            builder.finish(0);
        } catch (IOException | RuntimeException ex) {
            // A source that cannot be read must not leave a partial table behind that the next
            // open would take for a finished one.
            //noinspection ResultOfMethodCallIgnored
            built.delete();
            throw ex;
        }
        //noinspection ResultOfMethodCallIgnored
        archive.delete();

        RomanizationDict.commit(built, target);
        table = null;
    }

    /** Reads a downloaded source into the table being built. */
    interface Source {
        void addTo(File archive, WordReadingTable.Builder builder,
                   RomanizationDict.Listener listener) throws IOException;
    }
}
