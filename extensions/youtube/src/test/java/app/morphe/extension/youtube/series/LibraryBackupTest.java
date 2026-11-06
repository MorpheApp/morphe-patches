/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4415
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class LibraryBackupTest {
    private static TrackerModels.Series series(String id, boolean watchedOnAdvance) {
        return new TrackerModels.Series(id, "Music", "epoch", 1, "", -1, 0, "", "complete", "", 0, 0,
                List.of(new TrackerModels.Episode(1, "aaaaaaaaaaa", "Song", 180_000, true)),
                Map.of(), false, 0, false, false, watchedOnAdvance, "");
    }

    @Test
    public void optionIsRestored() {
        String text = LibraryBackup.encode(List.of(series("PLon", true), series("PLoff", false)));
        List<TrackerModels.Series> restored = LibraryBackup.decode(text).series();
        assertTrue(restored.get(0).watchedOnAdvance());
        assertFalse(restored.get(1).watchedOnAdvance());
    }

    @Test
    public void earlierBackupWithoutTheOptionIsOff() throws Exception {
        JSONObject document = new JSONObject(LibraryBackup.encode(List.of(series("PLon", true))));
        document.getJSONArray("series").getJSONObject(0).remove("watchedOnAdvance");
        assertFalse(LibraryBackup.decode(document.toString()).series().get(0).watchedOnAdvance());
    }
}
