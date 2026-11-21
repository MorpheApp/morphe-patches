/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.notifications;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.utils.ProtoNode;

/**
 * Logs the notifications response and the unseen state of the ungrouped videos.
 * Does nothing unless verbose logging is enabled.
 */
final class InboxResponseLogger {

    /**
     * Texts of icons and fonts, which every notification repeats.
     */
    private static final Pattern REPEATED_TEXT = Pattern.compile(
            "yt_(outline|fill)_|_black_\\d+|_vd_theme_|_grey600_|product_logo_|sans-serif|YouTubeSans|Roboto|^ic_");
    private static final int MAX_TEXT_LENGTH = 100;
    private static final int MAX_BYTES_LENGTH = 48;
    /**
     * Logcat cuts longer lines.
     */
    private static final int MAX_CHUNK_LENGTH = 3500;

    private static final int ENTITY_STORE = 777;

    /**
     * Logs the stored entities of the response and every notification, before the response is changed.
     * The fields of a notification have paths relative to it.
     */
    static void logResponse(List<ProtoNode> response, List<ProtoNode> notifications, int length) {
        if (!Logger.isVerboseEnabled()) {
            return;
        }
        StringBuilder tree = new StringBuilder()
                .append("Notifications response, ").append(length).append(" bytes\n");
        for (ProtoNode field : response) {
            if (field.getFieldNumber() == ENTITY_STORE && field.children != null) {
                tree.append("Entities:\n");
                appendFields(field.children, "/" + ENTITY_STORE, tree);
            }
        }
        for (int i = 0; i < notifications.size(); i++) {
            List<ProtoNode> fields = notifications.get(i).children;
            if (fields != null) {
                tree.append("Notification ").append(i + 1).append(" of ").append(notifications.size()).append(":\n");
                appendFields(fields, "", tree);
            }
        }
        for (String chunk : chunks(tree)) {
            Logger.printVerbose(() -> chunk);
        }
    }

    /**
     * Logs how the unseen state of an ungrouped video was decided.
     *
     * @param serverUnseen The state the response has for the key, or null if it has none.
     */
    static void logEntry(@Nullable String videoId, @Nullable String key, @Nullable Boolean serverUnseen,
                         boolean groupUnseen, boolean unseen) {
        if (!Logger.isVerboseEnabled()) {
            return;
        }
        boolean seen = SeenVideos.contains(videoId);
        boolean shownUnseen = SeenVideos.wasShownUnseen(videoId);
        Logger.printVerbose(() -> "Ungrouped video: " + videoId
                + " key: " + key
                + " server unseen: " + serverUnseen
                + " group unseen: " + groupUnseen
                + " played or seen: " + seen
                + " shown unseen: " + shownUnseen
                + " -> unseen: " + unseen);
    }

    private static void appendFields(List<ProtoNode> fields, String parentPath, StringBuilder tree) {
        for (ProtoNode field : fields) {
            String path = parentPath + "/" + field.getFieldNumber();
            if (field.children != null) {
                tree.append(path).append(" {\n");
                appendFields(field.children, path, tree);
            } else if (field.isText()) {
                String text = field.getText();
                if (REPEATED_TEXT.matcher(text).find()) {
                    continue;
                }
                if (text.length() > MAX_TEXT_LENGTH) {
                    text = text.substring(0, MAX_TEXT_LENGTH) + "… (" + text.length() + ")";
                }
                tree.append(path).append(" = \"").append(text).append("\"\n");
            } else if (field.getVarint() != null) {
                tree.append(path).append(" = ").append(field.getVarint()).append('\n');
            } else {
                byte[] payload = field.getPayload();
                tree.append(path).append(" = bytes[").append(payload.length).append("] ");
                appendHex(payload, tree);
                tree.append('\n');
            }
        }
    }

    private static void appendHex(byte[] bytes, StringBuilder tree) {
        for (int i = 0; i < Math.min(bytes.length, MAX_BYTES_LENGTH); i++) {
            tree.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16))
                    .append(Character.forDigit(bytes[i] & 0xF, 16));
        }
    }

    /**
     * @return The text split at line ends into parts of at most the maximum chunk length, where possible.
     */
    private static List<String> chunks(CharSequence text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + MAX_CHUNK_LENGTH);
            if (end < text.length()) {
                for (int i = end - 1; i > start; i--) {
                    if (text.charAt(i) == '\n') {
                        end = i + 1;
                        break;
                    }
                }
            }
            chunks.add(text.subSequence(start, end).toString());
            start = end;
        }
        return chunks;
    }
}
