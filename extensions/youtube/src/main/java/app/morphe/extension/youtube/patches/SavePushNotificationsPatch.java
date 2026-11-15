/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4844
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import com.google.protobuf.MessageLite;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Saves the proto of received push notifications, to find the fields of each notification type.
 * <p>
 * The notifications are saved to a file and not to the debug log, because they are usually
 * received while the app is in the background, and the process is killed before the log is exported.
 * <p>
 * Each line of the file is one notification: the date it was received and the Base64 encoded
 * PushNotificationSupportedRenderers proto.
 */
@SuppressWarnings("unused")
public final class SavePushNotificationsPatch {

    static final String FILE_NAME = "morphe_push_notifications.txt";

    /**
     * The previous file, kept when the current file is full.
     */
    static final String PREVIOUS_FILE_NAME = "morphe_push_notifications_previous.txt";

    /**
     * Size limit of each file.
     */
    static final long MAX_FILE_BYTES = 1024 * 1024;

    private static File getDirectory() {
        return Utils.getContext().getFilesDir();
    }

    /**
     * Injection point.
     *
     * @param renderers PushNotificationSupportedRenderers of a received push notification.
     */
    public static void savePushNotification(MessageLite renderers) {
        if (!Settings.DEBUG_SAVE_PUSH_NOTIFICATIONS.get()) {
            return;
        }

        try {
            String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date());
            String proto = Base64.getEncoder().encodeToString(renderers.toByteArray());

            append(getDirectory(), date + " " + proto + "\n");
        } catch (Exception ex) {
            Logger.printException(() -> "savePushNotification failure", ex);
        }
    }

    /**
     * @return All saved notifications, oldest first.
     */
    public static String getSavedPushNotifications() throws IOException {
        return read(getDirectory());
    }

    public static void clearSavedPushNotifications() throws IOException {
        clear(getDirectory());
    }

    static synchronized void append(File directory, String line) throws IOException {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        File file = new File(directory, FILE_NAME);

        if (file.isFile() && file.length() + bytes.length > MAX_FILE_BYTES) {
            Files.move(file.toPath(), new File(directory, PREVIOUS_FILE_NAME).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(bytes);
        }
    }

    static synchronized String read(File directory) throws IOException {
        StringBuilder builder = new StringBuilder();

        for (String name : new String[]{PREVIOUS_FILE_NAME, FILE_NAME}) {
            File file = new File(directory, name);
            if (file.isFile()) {
                builder.append(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            }
        }

        return builder.toString();
    }

    static synchronized void clear(File directory) throws IOException {
        Files.deleteIfExists(new File(directory, FILE_NAME).toPath());
        Files.deleteIfExists(new File(directory, PREVIOUS_FILE_NAME).toPath());
    }
}
