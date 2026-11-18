/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4722
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class DeviceLyricsTabTest {

    /** Tag of a string field: the field number shifted by 3, with the length delimited wire type 2. */
    private static byte[] stringField(int fieldNumber, String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        byte[] field = new byte[text.length + 2];
        field[0] = (byte) (fieldNumber << 3 | 2);
        field[1] = (byte) text.length;
        System.arraycopy(text, 0, field, 2, text.length);
        return field;
    }

    @Test
    public void addedTabRendererIsRecognized() throws Exception {
        assertTrue(DeviceLyricsTab.isAddedTabRenderer(DeviceLyricsTab.createTabRenderer("Lyrics")));
        assertTrue(DeviceLyricsTab.isAddedTabRenderer(DeviceLyricsTab.createTabRenderer("Letras")));
    }

    @Test
    public void appTabRendererIsNotRecognized() {
        byte[] title = stringField(2, "Lyrics");
        byte[] identifier = stringField(11, "lyrics");
        byte[] renderer = new byte[title.length + identifier.length];
        System.arraycopy(title, 0, renderer, 0, title.length);
        System.arraycopy(identifier, 0, renderer, title.length, identifier.length);

        assertFalse(DeviceLyricsTab.isAddedTabRenderer(title));
        assertFalse(DeviceLyricsTab.isAddedTabRenderer(renderer));
        assertFalse(DeviceLyricsTab.isAddedTabRenderer(new byte[0]));
    }
}
