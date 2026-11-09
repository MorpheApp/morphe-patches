/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.SecondaryContents;
import app.morphe.extension.youtube.settings.Settings;

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
        byte[] bytes = serializeSection(messageLite);
        if (bytes == null) {
            return false;
        }
        try {
            var secondaryContents = SecondaryContents.parseFrom(bytes);
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
        byte[] bytes = serializeSection(messageLite);
        if (bytes == null) {
            return false;
        }
        try {
            var secondaryContents = SecondaryContents.parseFrom(bytes);
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
     * @return The bytes of the section, or null if this patch already hid it.
     *         The app can build the watch page again from a response it keeps, and a section
     *         hidden in place keeps the serialized size saved before, so it cannot be serialized.
     *         A hidden section is left as it is.
     */
    @Nullable
    private static byte[] serializeSection(MessageLite section) {
        try {
            return section.toByteArray();
        } catch (RuntimeException ex) {
            Logger.printDebug(() -> "Section was already hidden and cannot be serialized", ex);
            return null;
        }
    }

    /**
     * Injection point.
     */
    public static boolean isFiltered() {
        return isFiltered;
    }
}
