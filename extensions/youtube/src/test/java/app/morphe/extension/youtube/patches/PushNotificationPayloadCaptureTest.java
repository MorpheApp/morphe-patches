package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Test;

public class PushNotificationPayloadCaptureTest {
    @Test
    public void appendsAndReadsPayloadsFromPersistentStorage() throws IOException {
        File directory = Files.createTempDirectory("shorts-payloads").toFile();

        PushNotificationPayloadCapture.append(directory, "first payload");
        PushNotificationPayloadCapture.append(directory, "second payload");

        String contents = PushNotificationPayloadCapture.read(directory);
        assertTrue(contents.contains("first payload"));
        assertTrue(contents.contains("second payload"));
    }

    @Test
    public void clearRemovesCapturedFiles() throws IOException {
        File directory = Files.createTempDirectory("shorts-payloads-clear").toFile();
        PushNotificationPayloadCapture.append(directory, "payload");

        PushNotificationPayloadCapture.clear(directory);

        assertTrue(PushNotificationPayloadCapture.read(directory).isEmpty());
        assertFalse(new File(directory, PushNotificationPayloadCapture.CAPTURE_FILE_NAME).exists());
    }

    @Test
    public void keepsRecentPayloadsWhenCaptureRollsOver() throws IOException {
        File directory = Files.createTempDirectory("shorts-payloads-rollover").toFile();
        String filler = "x".repeat(8_000);

        for (int i = 0; i < 300; i++) {
            PushNotificationPayloadCapture.append(directory, "payload-" + i + filler);
        }

        String contents = PushNotificationPayloadCapture.read(directory);
        assertTrue(contents.contains("payload-299"));
        assertFalse(contents.contains("payload-0"));
        File capture = new File(directory, PushNotificationPayloadCapture.CAPTURE_FILE_NAME);
        File backup = new File(directory, PushNotificationPayloadCapture.CAPTURE_FILE_NAME + ".1");
        assertTrue(capture.length() <= PushNotificationPayloadCapture.MAX_FILE_BYTES);
        assertTrue(backup.isFile());
        assertTrue(backup.length() <= PushNotificationPayloadCapture.MAX_FILE_BYTES);
    }
}
