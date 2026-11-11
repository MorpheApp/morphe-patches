/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4698
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.Menu.MenuIcon;
import app.morphe.extension.shared.innertube.Menu.MenuItem;
import app.morphe.extension.shared.innertube.Menu.MenuNavigationItemRenderer;
import app.morphe.extension.shared.innertube.Menu.MenuRenderer;
import app.morphe.extension.shared.innertube.Menu.MenuText;
import app.morphe.extension.shared.innertube.Menu.MenuTextRun;
import app.morphe.extension.shared.patches.OfflineVideoExporter;

/**
 * Adds an item to the menus of the songs and videos saved by the app, such as the menus
 * of the downloads list, that exports the song or video to a file on the device.
 */
@SuppressWarnings("unused")
public final class ExportMenuItem {

    /**
     * Icon of the Download item of menus.
     */
    private static final int ICON_TYPE_OFFLINE_DOWNLOAD = 147;

    private ExportMenuItem() {
    }

    /**
     * Injection point.
     * Called on the main thread when the items of a menu are created.
     *
     * @param menu The menu renderer, which has a remove download item if the song or video is saved.
     * @return The menu item renderer of the export item, or null if the menu gets no export item.
     */
    @Nullable
    public static byte[] getExportMenuItem(@Nullable Object menu) {
        try {
            if (!(menu instanceof MessageLite message)) {
                return null;
            }

            String videoId = getSavedVideoId(MenuRenderer.parseFrom(message.toByteArray()));
            if (videoId == null || !OfflineVideoExporter.isExportable(videoId)) {
                return null;
            }

            // The item has no command, so the app only closes the menu when it is clicked.
            return MenuItem.newBuilder()
                    .setMenuNavigationItemRenderer(MenuNavigationItemRenderer.newBuilder()
                            .setText(MenuText.newBuilder()
                                    .addRuns(MenuTextRun.newBuilder()
                                            .setText(str("morphe_export_download_flyout_title"))))
                            .setIcon(MenuIcon.newBuilder().setIconType(ICON_TYPE_OFFLINE_DOWNLOAD))
                            .setMorpheExportVideoId(videoId))
                    .build()
                    .toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "getExportMenuItem failure", ex);
            return null;
        }
    }

    /**
     * The menu of a song or video has an item with the offline video endpoint,
     * which saves the song or video, or removes it if it is saved.
     *
     * @return The song or video of the menu, or null if the menu has none.
     */
    @Nullable
    private static String getSavedVideoId(MenuRenderer menu) {
        for (MenuItem item : menu.getItemsList()) {
            MenuNavigationItemRenderer renderer = item.getMenuNavigationItemRenderer();
            if (renderer.getNavigationEndpoint().hasOfflineVideoEndpoint()) {
                String videoId = renderer.getNavigationEndpoint().getOfflineVideoEndpoint().getVideoId();
                return videoId.isEmpty() ? null : videoId;
            }
        }
        return null;
    }

    /**
     * Injection point.
     * Called on the main thread when an item of a menu is clicked.
     */
    public static void onMenuItemClick(@Nullable Object item) {
        try {
            if (!(item instanceof MessageLite message)) {
                return;
            }

            MenuNavigationItemRenderer renderer = MenuItem.parseFrom(message.toByteArray())
                    .getMenuNavigationItemRenderer();
            if (renderer.hasMorpheExportVideoId()) {
                OfflineVideoExporter.exportVideo(renderer.getMorpheExportVideoId());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onMenuItemClick failure", ex);
        }
    }
}
