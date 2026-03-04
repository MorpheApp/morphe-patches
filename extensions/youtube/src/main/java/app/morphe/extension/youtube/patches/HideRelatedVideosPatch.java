/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;
import com.google.protobuf.MessageLite;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.SecondaryContents;

@SuppressWarnings("unused")
public class HideRelatedVideosPatch {
    private static final boolean HIDE_PLAYER_RELATED_VIDEOS = Settings.HIDE_PLAYER_RELATED_VIDEOS.get();
    private static final String COMMENTS = "comments"; // comments-entry-point
    private static volatile boolean isFiltered = false;

    /**
     * Injection point.
     */
    public static boolean hideRelatedVideos() {
        if (HIDE_PLAYER_RELATED_VIDEOS) {
            isFiltered = false;
        }

        return HIDE_PLAYER_RELATED_VIDEOS;
    }

    /**
     * Injection point.
     */
    public static boolean isRelatedItems(MessageLite messageLite) {
        try {
            var secondaryContents = SecondaryContents.parseFrom(messageLite.toByteArray());
            if (secondaryContents.hasItemSectionRenderer()) {
                String sectionIdentifier = secondaryContents.getItemSectionRenderer().getSectionIdentifier();

                // ItemSectionRenderer is one of related items, ads, or comments.
                if (sectionIdentifier != null && sectionIdentifier.startsWith(COMMENTS)) {
                    return false;
                }

                // ItemSectionRenderer is always hidden if it is not the comments.
                isFiltered = true;
                return true;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to parse ItemSectionRenderer", ex);
        }

        return false;
    }

    /**
     * Injection point.
     */
    public static boolean isShelfRenderer(MessageLite messageLite) {
        try {
            var secondaryContents = SecondaryContents.parseFrom(messageLite.toByteArray());
            boolean hasShelfRenderer = secondaryContents.hasShelfRenderer();
            if (hasShelfRenderer) {
                // ShelfRenderer is only on tablets, and ShelfRenderer is always related items.
                isFiltered = true;
            }

            return hasShelfRenderer;
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to parse ShelfRendererRenderer", ex);
        }

        return false;
    }

    /**
     * Injection point.
     */
    public static boolean isFiltered() {
        return isFiltered;
    }
}
