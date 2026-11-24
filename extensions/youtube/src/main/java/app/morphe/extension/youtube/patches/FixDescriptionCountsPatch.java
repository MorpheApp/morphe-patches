/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5301
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.patches.utils.ProtoNode;

/**
 * The like and view counts of the description header roll from an older count to the current count,
 * but the app shows the older count without rolling. The header then shows the counts as its other
 * factoids, such as the upload date, which show only their current value.
 */
@SuppressWarnings("unused")
public final class FixDescriptionCountsPatch {

    private static final String DESCRIPTION_HEADER_TEMPLATE = "video_description_header.eml";

    /**
     * Factoid with a value and a label, such as the view count and the upload date.
     */
    private static final int FACTOID_FIELD = 2419;
    /**
     * Older value of a factoid, that the value rolls from.
     */
    private static final int FACTOID_ROLLING_START_FIELD = 2;
    private static final int FACTOID_CURRENT_VALUE_FIELD = 3;

    /**
     * Factoid of the like count, with a factoid for each like state.
     */
    private static final int LIKE_FACTOID_FIELD = 2428;
    /**
     * Key of the like count of the app for the video, which the factoid shows instead of the value of its factoids.
     */
    private static final int LIKE_FACTOID_LIKE_COUNT_FIELD = 2;

    private FixDescriptionCountsPatch() {
    }

    /**
     * Injection point.
     */
    @Nullable
    public static byte[] onElementLoaded(@Nullable byte[] bytes) {
        if (bytes == null || !FeedVideoCardsPatch.getElementText(bytes).contains(DESCRIPTION_HEADER_TEMPLATE)) {
            return bytes;
        }

        try {
            List<ProtoNode> root = ProtoNode.parse(bytes);
            if (root == null) {
                return bytes;
            }

            boolean changed = false;
            for (ProtoNode factoid : ProtoNode.findMessages(root, FACTOID_FIELD)) {
                ProtoNode rollingStart = ProtoNode.field(factoid.children, FACTOID_ROLLING_START_FIELD);
                if (rollingStart != null && ProtoNode.field(factoid.children, FACTOID_CURRENT_VALUE_FIELD) != null) {
                    rollingStart.remove();
                    changed = true;
                }
            }

            // The factoid of the like count shows the like count of the app, which is as old as the older value,
            // so it shows the current value of its factoid of the like state instead.
            for (ProtoNode likeFactoid : ProtoNode.findMessages(root, LIKE_FACTOID_FIELD)) {
                ProtoNode likeCount = ProtoNode.field(likeFactoid.children, LIKE_FACTOID_LIKE_COUNT_FIELD);
                if (likeCount != null) {
                    likeCount.remove();
                    changed = true;
                }
            }

            if (!changed) {
                return bytes;
            }

            Logger.printDebug(() -> "Description counts show the current counts");
            return ProtoNode.write(root);
        } catch (Exception ex) {
            Logger.printException(() -> "onElementLoaded failure", ex);
            return bytes;
        }
    }
}
