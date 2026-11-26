/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5404
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.util.Base64;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.returnyoutubedislike.ReturnYouTubeDislike;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

/**
 * Adds a dislikes row below the likes row of the 'Video details' section of the video description.
 * <p>
 * Each row of the section is a list item element with a title, an icon and a value. The dislikes row
 * is a copy of the likes row, with the dislike icon, and with the dislikes as text instead of
 * the like count element.
 */
@SuppressWarnings("unused")
public final class ReturnYouTubeDislikeVideoDetailsPatch {

    private static final boolean RYD_ENABLED = Settings.RYD_ENABLED.get();

    /**
     * Template of the like count of the likes row.
     */
    private static final String LIKE_COUNT_TEMPLATE = "list_item_like_count.eml";

    /**
     * Linear layout of the section, with a field for each row.
     */
    private static final int LINEAR_LAYOUT_FIELD = 2128;
    private static final int LINEAR_LAYOUT_CHILD_FIELD = 3;

    /**
     * Model of a row.
     */
    private static final int LIST_ITEM_FIELD = 489973921;
    private static final int LIST_ITEM_TITLE_FIELD = 1;
    /**
     * Path from the list item to the message of the icon type (YouTube 21.x).
     */
    private static final int[] LIST_ITEM_ICON_TYPE_PATH = {4, 1, 6, 1002, 1};
    /**
     * Path from the list item to the message of the icon drawable name (YouTube 20.26).
     */
    private static final int[] LIST_ITEM_ICON_NAME_PATH = {4, 1, 3};
    private static final int ICON_NAME_FIELD = 2;
    private static final String ICON_NAME_THUMB_UP = "thumb_up";
    private static final String ICON_NAME_THUMB_DOWN = "thumb_down";
    /**
     * Element of the value, such as the like count.
     */
    private static final int LIST_ITEM_VALUE_ELEMENT_FIELD = 31;
    /**
     * Text of the value, such as the upload date.
     */
    private static final int LIST_ITEM_VALUE_TEXT_FIELD = 33;
    private static final int TEXT_FIELD = 1;
    private static final int ICON_TYPE_FIELD = 1;
    /**
     * YTICON_OUTLINE_THUMB_UP_S24, the icon of the likes row.
     */
    private static final long ICON_TYPE_OUTLINE_THUMB_UP = 275;
    /**
     * YTICON_OUTLINE_THUMB_DOWN_S24.
     */
    private static final long ICON_TYPE_OUTLINE_THUMB_DOWN = 274;

    /**
     * Reference of the row element, with its ID.
     */
    private static final int ELEMENT_REFERENCE_FIELD = 183314536;
    private static final int ELEMENT_REFERENCE_ID_FIELD = 6;
    /**
     * Added to the ID of the likes row element, for the ID of the dislikes row element.
     * The IDs of the elements of a response are consecutive numbers.
     */
    private static final long DISLIKES_ROW_ID_OFFSET = 1_000_000_000L;

    /**
     * Start of the like and remove like tokens of the like count, which are a message
     * with the video ID in field 2, encoded as URL safe Base64.
     */
    private static final String LIKE_TOKEN_START = "Eg";
    private static final int LIKE_TOKEN_VIDEO_ID_FIELD = 2;
    private static final int VIDEO_ID_LENGTH = 11;

    private ReturnYouTubeDislikeVideoDetailsPatch() {
    }

    /**
     * Injection point.
     */
    @Nullable
    public static byte[] addDislikesRow(@Nullable byte[] bytes) {
        if (!RYD_ENABLED || bytes == null
                || !FeedVideoCardsPatch.getElementText(bytes).contains(LIKE_COUNT_TEMPLATE)) {
            return bytes;
        }

        try {
            // The dislikes of Shorts are not shown, as Shorts cannot be disliked.
            if (ShortsPlayerState.isOpen()) {
                return bytes;
            }

            List<ProtoNode> root = ProtoNode.parse(bytes);
            if (root == null) {
                return bytes;
            }

            for (ProtoNode layout : ProtoNode.findMessages(root, LINEAR_LAYOUT_FIELD)) {
                List<ProtoNode> rows = layout.children;
                if (rows == null) {
                    continue;
                }

                for (int i = 0, size = rows.size(); i < size; i++) {
                    ProtoNode likesRow = rows.get(i);
                    if (likesRow.getFieldNumber() != LINEAR_LAYOUT_CHILD_FIELD
                            || likesRow.children == null
                            || !containsTextStartingWith(likesRow.children)) {
                        continue;
                    }

                    byte[] dislikesRow = createDislikesRow(likesRow.getPayload());
                    if (dislikesRow == null) {
                        return bytes;
                    }

                    // Insert the dislikes row after the likes row.
                    byte[] layoutPayload = ProtoNode.concat(
                            ProtoNode.concat(
                                    ProtoNode.write(rows.subList(0, i + 1)),
                                    ProtoNode.encodeMessage(LINEAR_LAYOUT_CHILD_FIELD, dislikesRow)
                            ),
                            ProtoNode.write(rows.subList(i + 1, size))
                    );
                    layout.setPayload(layoutPayload);

                    return ProtoNode.write(root);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "addDislikesRow failure", ex);
        }

        return bytes;
    }

    /**
     * @return The dislikes row, or null if the dislikes are not available.
     */
    @Nullable
    private static byte[] createDislikesRow(byte[] likesRow) throws IOException {
        List<ProtoNode> row = ProtoNode.parse(likesRow);
        if (row == null) {
            return null;
        }

        String videoId = findVideoId(row);
        if (videoId == null) {
            Logger.printDebug(() -> "Video ID of the likes row not found");
            return null;
        }

        ReturnYouTubeDislike videoData = ReturnYouTubeDislike.getFetchForVideoIdOrNull(videoId);
        String dislikes = videoData == null
                ? null
                : videoData.getFormattedDislikesFullNumber();
        if (dislikes == null) {
            Logger.printDebug(() -> "Dislikes are not available for video: " + videoId);
            return null;
        }

        List<ProtoNode> items = ProtoNode.findMessages(row, LIST_ITEM_FIELD);
        if (items.size() != 1) {
            Logger.printDebug(() -> "Unexpected likes row items: " + items.size());
            return null;
        }
        ProtoNode item = items.get(0);
        List<ProtoNode> itemFields = item.children;

        ProtoNode title = ProtoNode.field(ProtoNode.message(itemFields, LIST_ITEM_TITLE_FIELD), TEXT_FIELD);
        ProtoNode valueElement = ProtoNode.field(itemFields, LIST_ITEM_VALUE_ELEMENT_FIELD);
        if (title == null || !title.isText() || valueElement == null || !setDislikeIcon(itemFields)) {
            Logger.printDebug(() -> "Unexpected likes row: " + ProtoNode.texts(row));
            return null;
        }

        title.setText(str("morphe_ryd_video_details_dislikes"));
        // The value is the dislikes text instead of the like count element.
        valueElement.remove();
        item.setPayload(ProtoNode.concat(
                ProtoNode.write(itemFields),
                ProtoNode.encodeMessage(LIST_ITEM_VALUE_TEXT_FIELD,
                        ProtoNode.encodeString(TEXT_FIELD, dislikes))
        ));

        // Each element has its own ID. The reference of the like count element was removed with it.
        for (ProtoNode reference : ProtoNode.findMessages(row, ELEMENT_REFERENCE_FIELD)) {
            ProtoNode id = ProtoNode.field(reference.children, ELEMENT_REFERENCE_ID_FIELD);
            if (id != null && id.isText()) {
                id.setText(dislikesRowId(id.getText()));
            }
        }

        Logger.printDebug(() -> "Added dislikes row: " + dislikes + " for video: " + videoId);
        return ProtoNode.write(row);
    }

    /**
     * Replaces the like icon of the row with the dislike icon.
     *
     * @return If the like icon was found.
     */
    private static boolean setDislikeIcon(List<ProtoNode> itemFields) {
        ProtoNode iconType = ProtoNode.field(ProtoNode.message(itemFields, LIST_ITEM_ICON_TYPE_PATH), ICON_TYPE_FIELD);
        if (iconType != null) {
            Long type = iconType.getVarint();
            if (type != null && type == ICON_TYPE_OUTLINE_THUMB_UP) {
                iconType.setVarint(ICON_TYPE_OUTLINE_THUMB_DOWN);
                return true;
            }
        }

        ProtoNode iconName = ProtoNode.field(ProtoNode.message(itemFields, LIST_ITEM_ICON_NAME_PATH), ICON_NAME_FIELD);
        if (iconName != null && iconName.isText() && iconName.getText().contains(ICON_NAME_THUMB_UP)) {
            iconName.setText(iconName.getText().replace(ICON_NAME_THUMB_UP, ICON_NAME_THUMB_DOWN));
            return true;
        }

        return false;
    }

    private static String dislikesRowId(String likesRowId) {
        try {
            return String.valueOf(Long.parseLong(likesRowId) + DISLIKES_ROW_ID_OFFSET);
        } catch (NumberFormatException ex) {
            return likesRowId + "_dislikes";
        }
    }

    /**
     * @return The video ID of the like tokens of the like count, or null if not found.
     */
    @Nullable
    private static String findVideoId(List<ProtoNode> likesRow) {
        for (String text : ProtoNode.texts(likesRow)) {
            if (!text.startsWith(LIKE_TOKEN_START)) {
                continue;
            }
            try {
                byte[] token = Base64.decode(text.replace("%3D", "="), Base64.URL_SAFE);
                ProtoNode videoId = ProtoNode.field(ProtoNode.parse(token), LIKE_TOKEN_VIDEO_ID_FIELD);
                if (videoId != null && videoId.isText() && videoId.getText().length() == VIDEO_ID_LENGTH) {
                    return videoId.getText();
                }
            } catch (IllegalArgumentException ex) {
                // Not Base64.
            }
        }
        return null;
    }

    private static boolean containsTextStartingWith(List<ProtoNode> message) {
        for (String text : ProtoNode.texts(message)) {
            if (text.startsWith(ReturnYouTubeDislikeVideoDetailsPatch.LIKE_COUNT_TEMPLATE)) {
                return true;
            }
        }
        return false;
    }
}
