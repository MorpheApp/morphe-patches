/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4963
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class OpenPostImagesFullscreenPatch {

    /**
     * Browse id of the posts feed that the images of a post open when tapped.
     */
    private static final String POSTS_FEED_BROWSE_ID = "FEpost_home";

    /**
     * Field numbers of the command an image of a post runs when tapped:
     * of each image of a post with many images, and of the image of a post with one image.
     * Other parts of the post, such as the 'View all posts' button, have other field numbers.
     */
    private static final int[][] IMAGE_TAP_COMMAND_PATHS = {
            {9, 1, 30},
            {2, 30}
    };
    private static final int INNERTUBE_COMMAND_FIELD = 169495254;
    private static final int BROWSE_ENDPOINT_FIELD = 48687626;
    private static final int BROWSE_ID_FIELD = 2;

    private OpenPostImagesFullscreenPatch() {
    }

    /**
     * Injection point.
     * <p>
     * The images of a post open the posts feed when tapped, where the image must be tapped
     * again to open it in fullscreen. Without the command of the image, the post opens
     * the image in fullscreen.
     */
    @Nullable
    public static byte[] onElementLoaded(@Nullable byte[] bytes) {
        if (!Settings.OPEN_POST_IMAGES_FULLSCREEN.get() || bytes == null
                || !FeedVideoCardsPatch.getElementText(bytes).contains(POSTS_FEED_BROWSE_ID)) {
            return bytes;
        }

        try {
            List<ProtoNode> root = ProtoNode.parse(bytes);
            if (root == null) {
                return bytes;
            }

            boolean removed = false;
            for (int[] path : IMAGE_TAP_COMMAND_PATHS) {
                for (ProtoNode command : ProtoNode.findMessages(root, path)) {
                    ProtoNode browseId = ProtoNode.field(ProtoNode.message(command.children,
                            INNERTUBE_COMMAND_FIELD, BROWSE_ENDPOINT_FIELD), BROWSE_ID_FIELD);
                    if (browseId != null && POSTS_FEED_BROWSE_ID.equals(browseId.decodeText())) {
                        command.remove();
                        removed = true;
                    }
                }
            }
            if (!removed) {
                return bytes;
            }

            Logger.printDebug(() -> "Removed the posts feed command of post images");
            return ProtoNode.write(root);
        } catch (Exception ex) {
            Logger.printException(() -> "onElementLoaded failure", ex);
            return bytes;
        }
    }
}
