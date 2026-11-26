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
import java.util.Objects;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.returnyoutubedislike.ReturnYouTubeDislike;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

/**
 * Adds a dislikes row below the likes row of the 'Video details' section of the video description.
 * <p>
 * Each row of the section is a list item with a title, an icon and a value. The dislikes row
 * is a copy of the likes row with the dislike icon, and with the dislikes as text instead of
 * the like count element.
 */
@SuppressWarnings("unused")
public final class ReturnYouTubeDislikeVideoDetailsPatch {

    private static final boolean RYD_ENABLED = Settings.RYD_ENABLED.get();

    /**
     * Template of the like count element of the likes row.
     */
    private static final String LIKE_COUNT_TEMPLATE = "list_item_like_count.eml";

    /**
     * Linear layout of the section, with a field for each row.
     */
    private static final int LINEAR_LAYOUT_FIELD = 2128;
    private static final int LINEAR_LAYOUT_ROW_FIELD = 3;

    /**
     * List item of a row.
     */
    private static final int LIST_ITEM_FIELD = 489973921;
    private static final int LIST_ITEM_TITLE_FIELD = 1;
    /**
     * Element of the value, such as the like count.
     */
    private static final int LIST_ITEM_VALUE_ELEMENT_FIELD = 31;
    /**
     * Text of the value, such as the upload date.
     */
    private static final int LIST_ITEM_VALUE_TEXT_FIELD = 33;
    private static final int TEXT_FIELD = 1;

    /**
     * Path from the list item to the icon type (YouTube 21.x).
     */
    private static final int[] LIST_ITEM_ICON_TYPE_PATH = {4, 1, 6, 1002, 1};
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
     * Path from the list item to the icon drawable name (YouTube 20.x).
     */
    private static final int[] LIST_ITEM_ICON_NAME_PATH = {4, 1, 3};
    private static final int ICON_NAME_FIELD = 2;
    private static final String ICON_NAME_THUMB_UP = "thumb_up";
    private static final String ICON_NAME_THUMB_DOWN = "thumb_down";

    /**
     * Reference of an element of the row, with the element ID.
     */
    private static final int ELEMENT_REFERENCE_FIELD = 183314536;
    private static final int ELEMENT_REFERENCE_ID_FIELD = 6;
    /**
     * Added to the element IDs of the likes row, for the element IDs of the dislikes row.
     * The element IDs of a response are consecutive numbers.
     */
    private static final long DISLIKES_ROW_ID_OFFSET = 1_000_000_000L;

    /**
     * Like and remove like tokens of the like count element. They are a URL safe Base64 message
     * with the video ID in field 2, and start with the tag of that field.
     */
    private static final Pattern LIKE_TOKEN_PATTERN = Pattern.compile("^Eg[A-Za-z0-9_-]+(?:%3D)*$");
    private static final int LIKE_TOKEN_VIDEO_ID_FIELD = 2;
    private static final int VIDEO_ID_LENGTH = 11;

    private ReturnYouTubeDislikeVideoDetailsPatch() {
    }

    /**
     * Injection point.
     */
    @Nullable
    public static byte[] addDislikesRow(@Nullable byte[] bytes) {
        // Shorts cannot be disliked, and RYD is not fetched for them.
        if (!RYD_ENABLED || bytes == null || ShortsPlayerState.isOpen()
                || !FeedVideoCardsPatch.getElementText(bytes).contains(LIKE_COUNT_TEMPLATE)) {
            return bytes;
        }

        try {
            List<ProtoNode> root = ProtoNode.parse(bytes);
            if (root == null) {
                return bytes;
            }

            for (ProtoNode likesRow : ProtoNode.findMessages(root, LINEAR_LAYOUT_FIELD, LINEAR_LAYOUT_ROW_FIELD)) {
                if (!isLikesRow(likesRow)) {
                    continue;
                }

                byte[] dislikesRow = createDislikesRow(likesRow.getPayload());
                if (dislikesRow == null) {
                    return bytes;
                }

                ProtoNode layout = Objects.requireNonNull(likesRow.getParent());
                List<ProtoNode> rows = Objects.requireNonNull(layout.children);
                final int dislikesRowIndex = rows.indexOf(likesRow) + 1;
                layout.setPayload(ProtoNode.concat(
                        ProtoNode.write(rows.subList(0, dislikesRowIndex)),
                        ProtoNode.encodeMessage(LINEAR_LAYOUT_ROW_FIELD, dislikesRow),
                        ProtoNode.write(rows.subList(dislikesRowIndex, rows.size()))
                ));

                return ProtoNode.write(root);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "addDislikesRow failure", ex);
        }

        return bytes;
    }

    private static boolean isLikesRow(ProtoNode row) {
        for (String text : ProtoNode.texts(Objects.requireNonNull(row.children))) {
            if (text.startsWith(LIKE_COUNT_TEMPLATE)) {
                return true;
            }
        }
        return false;
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
                : videoData.getFormattedDislikesFullCount();
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
        List<ProtoNode> itemFields = Objects.requireNonNull(item.children);

        ProtoNode title = ProtoNode.field(ProtoNode.message(itemFields, LIST_ITEM_TITLE_FIELD), TEXT_FIELD);
        ProtoNode valueElement = ProtoNode.field(itemFields, LIST_ITEM_VALUE_ELEMENT_FIELD);
        if (title == null || !title.isText() || valueElement == null || !setDislikeIcon(itemFields)) {
            Logger.printDebug(() -> "Unexpected likes row: " + ProtoNode.texts(row));
            return null;
        }

        title.setText(str("morphe_ryd_video_details_dislikes"));
        valueElement.remove();
        item.setPayload(ProtoNode.concat(
                ProtoNode.write(itemFields),
                ProtoNode.encodeMessage(LIST_ITEM_VALUE_TEXT_FIELD, ProtoNode.encodeString(TEXT_FIELD, dislikes))
        ));

        // The remaining elements of the row need their own IDs.
        for (ProtoNode reference : ProtoNode.findMessages(row, ELEMENT_REFERENCE_FIELD)) {
            ProtoNode id = ProtoNode.field(reference.children, ELEMENT_REFERENCE_ID_FIELD);
            if (id != null && id.isText()) {
                id.setText(String.valueOf(Long.parseLong(id.getText()) + DISLIKES_ROW_ID_OFFSET));
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

    /**
     * @return The video ID of the like tokens of the like count element, or null if not found.
     */
    @Nullable
    private static String findVideoId(List<ProtoNode> likesRow) {
        for (String text : ProtoNode.texts(likesRow)) {
            if (!LIKE_TOKEN_PATTERN.matcher(text).matches()) {
                continue;
            }
            byte[] token = Base64.decode(text.replace("%3D", ""),
                    Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            ProtoNode videoId = ProtoNode.field(ProtoNode.parse(token), LIKE_TOKEN_VIDEO_ID_FIELD);
            if (videoId != null && videoId.isText() && videoId.getText().length() == VIDEO_ID_LENGTH) {
                return videoId.getText();
            }
        }
        return null;
    }
}
