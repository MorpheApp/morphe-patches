/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class OpenSystemShareSheetPatch {

    public interface ActionSheetControllerInterface {
        // Method is added during patching.
        void patch_dismissActionSheet();
    }

    private static final int SHARE_ENDPOINT_ENTITY_FIELD = 1;

    // Share entity fields, from observed values.
    private static final int ENTITY_VIDEO_ID_FIELD = 1;
    private static final int ENTITY_PLAYLIST_ID_FIELD = 2;
    private static final int ENTITY_CHANNEL_ID_FIELD = 3;
    private static final int ENTITY_POST_ID_FIELD = 6;
    private static final int ENTITY_POST_CHANNEL_ID_FIELD = 7;
    private static final int ENTITY_COMMENT_FIELD = 18;
    /**
     * Fields of the entities shared with a plain link.
     * 10, 12 and 20 are Shorts and entity type metadata that does not change the link.
     */
    private static final Set<Integer> SHARE_ENTITY_FIELDS = Set.of(
            ENTITY_VIDEO_ID_FIELD,
            ENTITY_PLAYLIST_ID_FIELD,
            ENTITY_CHANNEL_ID_FIELD,
            ENTITY_POST_ID_FIELD,
            ENTITY_POST_CHANNEL_ID_FIELD,
            10, 12, 20
    );

    // Comment entity fields. 3, 4 and 6 are the header, text and avatar of the share sheet.
    private static final int COMMENT_ID_FIELD = 1;
    private static final int COMMENT_PAGE_PATH_FIELD = 5;
    /**
     * Commented video id, from the commented item (field 2) of the comment entity.
     */
    private static final int[] COMMENT_VIDEO_PATH = {2, 1, 1};
    /**
     * Commented post id, from the commented item (field 2) of the comment entity.
     */
    private static final int[] COMMENT_POST_PATH = {2, 2, 1};
    private static final int COMMENT_ITEM_ID_FIELD = 1;

    private static final Pattern SHARE_ENTITY_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");
    /**
     * Comment id, or the comment id of the parent and the reply id separated by a dot.
     */
    private static final Pattern COMMENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)?");

    private static WeakReference<ActionSheetControllerInterface> actionSheetControllerRef = new WeakReference<>(null);

    /**
     * Injection point.
     */
    public static void setActionSheetController(ActionSheetControllerInterface actionSheetController) {
        actionSheetControllerRef = new WeakReference<>(actionSheetController);
    }

    /**
     * Dismisses the empty action sheet that is opened before the share endpoint is resolved.
     */
    private static void dismissActionSheet() {
        // Post to the main thread, so the action sheet opened in the same call is already shown.
        Utils.runOnMainThread(() -> {
            ActionSheetControllerInterface actionSheetController = actionSheetControllerRef.get();
            if (actionSheetController != null) {
                actionSheetController.patch_dismissActionSheet();
            }
        });
    }

    /**
     * Builds the link from the serialized share entity of the share endpoint.
     * The entity is what the in-app share sheet sends to {@code share/get_share_panel}.
     *
     * @return The link, or null if the entity is not a type known to be shared with a plain link.
     */
    @Nullable
    private static String getShareEntityUrl(String serializedEntity, String longURLPrefix, String videoURLPrefix) {
        final byte[] entityBytes;
        try {
            String base64 = Uri.decode(serializedEntity).replace('-', '+').replace('_', '/');
            entityBytes = Base64.decode(base64, Base64.DEFAULT);
        } catch (IllegalArgumentException ex) {
            Logger.printDebug(() -> "Share entity is not Base64: " + serializedEntity);
            return null;
        }

        List<ProtoNode> entity = ProtoNode.parse(entityBytes);
        if (entity == null) {
            return null;
        }
        ProtoNode comment = ProtoNode.field(entity, ENTITY_COMMENT_FIELD);
        if (comment != null) {
            return entity.size() == 1 ? getCommentUrl(comment, longURLPrefix, videoURLPrefix) : null;
        }
        for (ProtoNode field : entity) {
            if (!SHARE_ENTITY_FIELDS.contains(field.getFieldNumber())) {
                // Comments, clips, timestamps and other entities link to more than the plain id.
                Logger.printDebug(() -> "Unknown share entity field: " + field.getFieldNumber());
                return null;
            }
        }

        final String videoId = getShareEntityId(entity, ENTITY_VIDEO_ID_FIELD);
        final String playlistId = getShareEntityId(entity, ENTITY_PLAYLIST_ID_FIELD);
        final String channelId = getShareEntityId(entity, ENTITY_CHANNEL_ID_FIELD);
        final String postId = getShareEntityId(entity, ENTITY_POST_ID_FIELD);
        if (postId != null) {
            return longURLPrefix + "/post/" + postId;
        }
        if (channelId != null) {
            return videoId == null && playlistId == null
                    ? longURLPrefix + "/channel/" + channelId
                    : null;
        }
        if (videoId != null && playlistId == null) {
            return videoURLPrefix + videoId;
        }
        if (playlistId != null && videoId == null) {
            return longURLPrefix + "/playlist?list=" + playlistId;
        }
        return null;
    }

    /**
     * @return The link, or null if the comment is not on a video or a post.
     */
    @Nullable
    private static String getCommentUrl(ProtoNode comment, String longURLPrefix, String videoURLPrefix) {
        List<ProtoNode> fields = comment.children;
        ProtoNode commentId = fields == null ? null : ProtoNode.field(fields, COMMENT_ID_FIELD);
        ProtoNode path = fields == null ? null : ProtoNode.field(fields, COMMENT_PAGE_PATH_FIELD);
        if (commentId == null || !commentId.isText() || !COMMENT_ID_PATTERN.matcher(commentId.getText()).matches()
                || path == null || !path.isText()) {
            return null;
        }

        switch (path.getText()) {
            case "/watch" -> {
                String videoId = getShareEntityId(ProtoNode.message(fields, COMMENT_VIDEO_PATH), COMMENT_ITEM_ID_FIELD);
                if (videoId == null) return null;
                String separator = videoURLPrefix.contains("?") ? "&" : "?";
                return videoURLPrefix + videoId + separator + "lc=" + commentId.getText();
            }
            case "/post" -> {
                String postId = getShareEntityId(ProtoNode.message(fields, COMMENT_POST_PATH), COMMENT_ITEM_ID_FIELD);
                return postId == null ? null : longURLPrefix + "/post/" + postId + "?lc=" + commentId.getText();
            }
            default -> {
                return null;
            }
        }
    }

    @Nullable
    private static String getShareEntityId(List<ProtoNode> entity, int fieldNumber) {
        ProtoNode field = ProtoNode.field(entity, fieldNumber);
        if (field == null || !field.isText() || !SHARE_ENTITY_ID_PATTERN.matcher(field.getText()).matches()) {
            return null;
        }
        return field.getText();
    }

    /**
     * Injection point.
     *
     * @param shareEndpoint Share endpoint proto.
     * @return If the in-app share sheet must not be opened.
     */
    public static boolean openSystemShareSheet(byte[] shareEndpoint) {
        if (!Settings.OPEN_SYSTEM_SHARE_SHEET.get()) {
            return false;
        }

        List<ProtoNode> endpoint = ProtoNode.parse(shareEndpoint);
        ProtoNode serializedEntity = endpoint == null ? null : ProtoNode.field(endpoint, SHARE_ENDPOINT_ENTITY_FIELD);
        if (serializedEntity == null || !serializedEntity.isText()) {
            // Community posts send a share entity endpoint without an entity. Its command opens
            // an action sheet with a share endpoint that has the entity, which is handled instead.
            Logger.printDebug(() -> "Share endpoint has no share entity, resolving its command: "
                    + Base64.encodeToString(shareEndpoint, Base64.NO_WRAP));
            return false;
        }

        final String longURLPrefix = "https://www.youtube.com";
        final String videoURLPrefix = !Settings.REPLACE_LINKS_WITH_SHORTENER.get()
                ? longURLPrefix + "/watch?v="
                : "https://youtu.be/";

        final String intentUrl = getShareEntityUrl(serializedEntity.getText(), longURLPrefix, videoURLPrefix);
        if (intentUrl == null) {
            Logger.printDebug(() -> "No link for share endpoint, opening the in-app share sheet: "
                    + Base64.encodeToString(shareEndpoint, Base64.NO_WRAP));
            return false;
        }

        Logger.printDebug(() -> "Share link: " + intentUrl);
        final Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, intentUrl);
        final Intent chooserIntent = Intent.createChooser(shareIntent, "");
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);

        try {
            Utils.getContext().startActivity(chooserIntent);
        } catch (Exception ex) {
            Logger.printException(() -> "Can not open System Share panel: " + intentUrl, ex);
        }

        // The in-app share sheet is never opened.
        dismissActionSheet();
        return true;
    }
}
