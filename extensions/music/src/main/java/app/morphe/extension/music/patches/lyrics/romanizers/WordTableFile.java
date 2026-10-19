/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3851
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;

/** A {@link WordReadingTable} in the dictionary directory, opened on first use and kept open. */
final class WordTableFile {

    /** Reads a downloaded source into the table being built. */
    interface Source {
        void addTo(File archive, WordReadingTable.Builder builder,
                RomanizationDict.Listener listener) throws IOException;
    }

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
        open = WordReadingTable.open(file.toPath(), magic);
        if (open != null) {
            table = open;
        }
        return open;
    }

    WordReadingTable.Builder newBuilder(File built) throws IOException {
        return new WordReadingTable.Builder(built.toPath(), magic, slotCount);
    }

    /** Downloads the source unless an earlier attempt left it behind, and builds the table. */
    void build(File directory, String url, String archiveName, Source source,
            RomanizationDict.Listener listener) throws IOException {
        File target = new File(directory, name);
        if (target.isFile()) return;

        File archive = new File(directory, archiveName);
        if (!archive.isFile()) {
            RomanizationDict.download(url, archive, archiveName, listener);
        }

        File built = new File(directory, name + ".building");
        WordReadingTable.Builder builder = newBuilder(built);
        source.addTo(archive, builder, listener);
        builder.finish(0);
        //noinspection ResultOfMethodCallIgnored
        archive.delete();

        RomanizationDict.commit(built, target);
        table = null;
    }
}
