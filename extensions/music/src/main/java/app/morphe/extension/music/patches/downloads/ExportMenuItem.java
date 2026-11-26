/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4698
 * https://github.com/MorpheApp/morphe-patches/pull/5043
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.Menu.MenuCommand;
import app.morphe.extension.shared.innertube.Menu.MenuIcon;
import app.morphe.extension.shared.innertube.Menu.MenuItem;
import app.morphe.extension.shared.innertube.Menu.MenuNavigationItemRenderer;
import app.morphe.extension.shared.innertube.Menu.MenuRenderer;
import app.morphe.extension.shared.innertube.Menu.MenuText;
import app.morphe.extension.shared.innertube.Menu.MenuTextRun;
import app.morphe.extension.shared.patches.OfflineVideoExporter;

/**
 * Adds items to the menus of the songs, videos, playlists and albums saved by the app, such as
 * the menus of the downloads list, that export them, or all saved songs and videos, to files on the device.
 */
@SuppressWarnings("unused")
public final class ExportMenuItem {

    /**
     * Icon of the Download item of menus.
     */
    private static final int ICON_TYPE_OFFLINE_DOWNLOAD = 147;

    private static final byte[][] NO_ITEMS = new byte[0][];

    private ExportMenuItem() {
    }

    /**
     * Injection point.
     * Called on the main thread when the items of a menu are created.
     *
     * @param menu The menu renderer, which has a remove download item if the song, video,
     *             playlist or album is saved.
     * @return The menu item renderers of the export items, which are none if the menu gets no export items.
     */
    public static byte[][] getExportMenuItems(@Nullable Object menu) {
        try {
            if (!(menu instanceof MessageLite message)) {
                return NO_ITEMS;
            }

            MenuRenderer renderer = MenuRenderer.parseFrom(message.toByteArray());
            MenuNavigationItemRenderer.Builder exportItem = createItem(str("morphe_export_download_flyout_title"));
            final int exportCount;

            String playlistId = getOfflinePlaylistId(renderer);
            if (playlistId != null) {
                exportCount = OfflineVideoExporter.getExportablePlaylistVideoIds(playlistId).size();
                if (exportCount == 0) {
                    return NO_ITEMS;
                }
                exportItem.setMorpheExportPlaylistId(playlistId);
            } else {
                String videoId = getVideoId(renderer);
                if (videoId == null || !OfflineVideoExporter.isExportable(videoId)) {
                    return NO_ITEMS;
                }
                exportCount = 1;
                exportItem.setMorpheExportVideoId(videoId);
            }

            // Exporting all is only shown if there is more to export than the item of the menu.
            if (OfflineVideoExporter.getExportableVideoIds(exportCount + 1).size() <= exportCount) {
                return new byte[][]{toMenuItem(exportItem)};
            }

            byte[] exportAllItem = toMenuItem(createItem(str("morphe_export_download_all_flyout_title"))
                    .setMorpheExportAll(true));
            return new byte[][]{toMenuItem(exportItem), exportAllItem};
        } catch (Exception ex) {
            Logger.printException(() -> "getExportMenuItems failure", ex);
            return NO_ITEMS;
        }
    }

    /**
     * The items have no command, so the app only closes the menu when they are clicked.
     */
    private static MenuNavigationItemRenderer.Builder createItem(String text) {
        return MenuNavigationItemRenderer.newBuilder()
                .setText(MenuText.newBuilder()
                        .addRuns(MenuTextRun.newBuilder().setText(text)))
                .setIcon(MenuIcon.newBuilder().setIconType(ICON_TYPE_OFFLINE_DOWNLOAD));
    }

    private static byte[] toMenuItem(MenuNavigationItemRenderer.Builder renderer) {
        return MenuItem.newBuilder()
                .setMenuNavigationItemRenderer(renderer)
                .build()
                .toByteArray();
    }

    /**
     * The menu of a playlist or album has an item with the offline playlist endpoint,
     * which saves the playlist, or removes it if it is saved.
     *
     * @return The playlist or album of the menu, or null if the menu has none.
     */
    @Nullable
    private static String getOfflinePlaylistId(MenuRenderer menu) {
        for (MenuItem item : menu.getItemsList()) {
            MenuCommand command = item.getMenuNavigationItemRenderer().getNavigationEndpoint();
            if (command.hasOfflinePlaylistEndpoint()) {
                String playlistId = command.getOfflinePlaylistEndpoint().getPlaylistId();
                return playlistId.isEmpty() ? null : playlistId;
            }
        }
        return null;
    }

    /**
     * The menu of a song or video usually has an item with the offline video endpoint,
     * which saves the song or video, or removes it if it is saved. The menus of the songs
     * of a saved album have no such item, so the song of the queue items is used instead.
     *
     * @return The song or video of the menu, or null if the menu has none.
     */
    @Nullable
    private static String getVideoId(MenuRenderer menu) {
        String queueVideoId = null;
        for (MenuItem item : menu.getItemsList()) {
            MenuCommand command = item.getMenuNavigationItemRenderer().getNavigationEndpoint();
            if (command.hasOfflineVideoEndpoint()) {
                String videoId = command.getOfflineVideoEndpoint().getVideoId();
                return videoId.isEmpty() ? null : videoId;
            }
            if (queueVideoId == null && command.hasQueueAddEndpoint()) {
                String videoId = command.getQueueAddEndpoint().getQueueTarget().getVideoId();
                if (!videoId.isEmpty()) {
                    queueVideoId = videoId;
                }
            }
        }
        return queueVideoId;
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
            } else if (renderer.hasMorpheExportPlaylistId()) {
                OfflineVideoExporter.exportPlaylistVideos(renderer.getMorpheExportPlaylistId());
            } else if (renderer.getMorpheExportAll()) {
                OfflineVideoExporter.exportAllVideos();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onMenuItemClick failure", ex);
        }
    }
}
