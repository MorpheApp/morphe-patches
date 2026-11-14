package app.morphe.extension.youtube.patches;

import android.content.Context;

import com.google.protobuf.MessageLite;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import app.morphe.extension.shared.Logger;

/** Bounded, app-private storage for opt-in YouTube push-payload diagnostics. */
public final class PushNotificationPayloadCapture {
    static final String CAPTURE_FILE_NAME = "push-notification-payloads.log";
    static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final int MAX_PAYLOAD_CHARS = 900_000;
    private static final String BACKUP_FILE_NAME = CAPTURE_FILE_NAME + ".1";
    private static final Object LOCK = new Object();

    private PushNotificationPayloadCapture() {}

    public static void capture(Context context, MessageLite payload, boolean filterEnabled, boolean isShorts) {
        if (context == null || payload == null) return;

        String serialized = Base64.getEncoder().encodeToString(payload.toByteArray());
        if (serialized.length() > MAX_PAYLOAD_CHARS) {
            serialized = serialized.substring(0, MAX_PAYLOAD_CHARS) + "… [truncated]";
        }
        String decision = filterEnabled && isShorts ? "suppressed" : "passed";
        String entry = "timestamp_ms=" + System.currentTimeMillis()
                + " type=" + payload.getClass().getName()
                + " filter_enabled=" + filterEnabled
                + " classified_shorts=" + isShorts
                + " decision=" + decision
                + "\nprotobuf_base64=" + serialized;
        try {
            append(context.getFilesDir(), entry);
        } catch (IOException | RuntimeException ex) {
            Logger.printException(() -> "Could not persist push notification payload", ex);
        }
    }

    public static String readCapturedPayloads(Context context) throws IOException {
        return read(context.getFilesDir());
    }

    public static void clearCapturedPayloads(Context context) throws IOException {
        clear(context.getFilesDir());
    }

    static void append(File filesDirectory, String payload) throws IOException {
        byte[] bytes = (payload + "\n\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Payload entry exceeds capture file limit");
        }

        synchronized (LOCK) {
            if (!filesDirectory.isDirectory() && !filesDirectory.mkdirs() && !filesDirectory.isDirectory()) {
                throw new IOException("Could not create app files directory");
            }

            File capture = new File(filesDirectory, CAPTURE_FILE_NAME);
            File backup = new File(filesDirectory, BACKUP_FILE_NAME);
            if (capture.length() + bytes.length > MAX_FILE_BYTES && capture.exists()) {
                if (backup.exists() && !backup.delete()) {
                    throw new IOException("Could not replace prior capture file");
                }
                if (!capture.renameTo(backup)) {
                    throw new IOException("Could not rotate capture file");
                }
            }

            try (FileOutputStream output = new FileOutputStream(capture, true)) {
                output.write(bytes);
                output.flush();
            }
        }
    }

    static String read(File filesDirectory) throws IOException {
        synchronized (LOCK) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            copyIfPresent(new File(filesDirectory, BACKUP_FILE_NAME), output);
            copyIfPresent(new File(filesDirectory, CAPTURE_FILE_NAME), output);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    static void clear(File filesDirectory) throws IOException {
        synchronized (LOCK) {
            deleteIfPresent(new File(filesDirectory, CAPTURE_FILE_NAME));
            deleteIfPresent(new File(filesDirectory, BACKUP_FILE_NAME));
        }
    }

    private static void copyIfPresent(File file, ByteArrayOutputStream output) throws IOException {
        if (!file.isFile()) return;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
    }

    private static void deleteIfPresent(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("Could not delete capture file");
        }
    }
}
