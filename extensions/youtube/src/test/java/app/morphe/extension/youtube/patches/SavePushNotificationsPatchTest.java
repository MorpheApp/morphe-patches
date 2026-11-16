/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4844
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class SavePushNotificationsPatchTest {

    @Test
    public void readsLinesInOrder() throws IOException {
        File directory = Files.createTempDirectory("push-notifications").toFile();

        SavePushNotificationsPatch.append(directory, "first\n");
        SavePushNotificationsPatch.append(directory, "second\n");

        assertEquals("first\nsecond\n", SavePushNotificationsPatch.read(directory));
    }

    @Test
    public void clearDeletesFiles() throws IOException {
        File directory = Files.createTempDirectory("push-notifications-clear").toFile();
        for (int i = 0; i < 300; i++) {
            SavePushNotificationsPatch.append(directory, "x".repeat(8_000) + "\n");
        }

        SavePushNotificationsPatch.clear(directory);

        assertTrue(SavePushNotificationsPatch.read(directory).isEmpty());
        assertFalse(new File(directory, SavePushNotificationsPatch.FILE_NAME).exists());
        assertFalse(new File(directory, SavePushNotificationsPatch.PREVIOUS_FILE_NAME).exists());
    }

    @Test
    public void keepsNewestLinesWhenFull() throws IOException {
        File directory = Files.createTempDirectory("push-notifications-full").toFile();
        String filler = "x".repeat(8_000);

        for (int i = 0; i < 300; i++) {
            SavePushNotificationsPatch.append(directory, "line-" + i + " " + filler + "\n");
        }

        String saved = SavePushNotificationsPatch.read(directory);
        assertTrue(saved.contains("line-299 "));
        assertFalse(saved.contains("line-0 "));

        File file = new File(directory, SavePushNotificationsPatch.FILE_NAME);
        File previous = new File(directory, SavePushNotificationsPatch.PREVIOUS_FILE_NAME);
        assertTrue(file.length() <= SavePushNotificationsPatch.MAX_FILE_BYTES);
        assertTrue(previous.isFile());
        assertTrue(previous.length() <= SavePushNotificationsPatch.MAX_FILE_BYTES);
    }
}
