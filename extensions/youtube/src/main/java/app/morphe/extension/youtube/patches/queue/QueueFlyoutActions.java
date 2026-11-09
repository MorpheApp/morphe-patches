/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import static app.morphe.extension.shared.StringRef.str;

import android.net.Uri;
import android.util.Base64;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.innertube.IconOuterClass.Icon;
import app.morphe.extension.youtube.innertube.IconOuterClass.YTIconType;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.MenuItem;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.MenuRenderer;
import app.morphe.extension.youtube.patches.AddToQueuePatch;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;

/** Adjusts native menu data before YouTube filters and binds its rows. */
public final class QueueFlyoutActions {
    private static final int MDX_CONNECTED = 1;
    private static final int MDX_DISCONNECTED = 2;

    public interface Menu {
        MessageLite patch_parseQueueFlyout(byte[] bytes);
    }

    /** Injection point. Other queue providers and unrelated menu sources keep the original model. */
    @Nullable
    public static MessageLite completeQueueMenu(@Nullable MessageLite original) {
        if (original == null || !AddToQueuePatch.isNativeQueueEnabled()) {
            return original;
        }
        try {
            MenuRenderer menu = MenuRenderer.parseFrom(original.toByteArray());
            // This model's collection-only Share target is authoritative when
            // present. Shared card context is needed for private menus without Share.
            String playlistId = "";
            if (NativePlaylistQueuePatch.canQueuePlaylist()) {
                playlistId = playlistIdFromMenu(menu);
                if (playlistId.isEmpty()) {
                    playlistId = FlyoutUtils.getNativeQueuePlaylistId();
                }
            }
            if (playlistId.isEmpty() && !NativePlaylistQueuePatch.canQueueFlyoutVideo(FlyoutUtils.getFlyoutVideoId())) {
                return original;
            }
            FlyoutUtils.setNativeQueuePlaylistId(playlistId);
            MenuRenderer completed = completeMenu(menu,
                    !playlistId.isEmpty() || NativePlaylistQueuePatch.canShowFlyoutLast(),
                    str("morphe_native_playlist_queue_next"), str("morphe_native_playlist_queue_last"), playlistId);
            if (completed == menu) {
                return original;
            }
            return ((Menu) original).patch_parseQueueFlyout(completed.toByteArray());
        } catch (Exception exception) {
            Logger.printException(() -> "completeQueueMenu failure", exception);
            return original;
        }
    }

    /** Direct native sheet dispatch, which does not run the button model's Runnable. */
    public static boolean onStockQueueItemClick(Enum<?> icon) {
        return icon != null && AddToQueuePatch.isNativeQueueEnabled()
                && AddToQueuePatch.flyoutButtonClickLogic(icon.name(), FlyoutUtils.getFlyoutVideoId());
    }

    static MenuRenderer completeMenu(MenuRenderer menu, boolean allowLast, String nextLabel, String lastLabel) {
        return completeMenu(menu, allowLast, nextLabel, lastLabel, "");
    }

    static MenuRenderer completeMenu(MenuRenderer menu, boolean allowLast, String nextLabel, String lastLabel,
                                     String playlistId) {
        MenuItem next = null;
        MenuItem last = null;
        for (MenuItem item : menu.getItemsList()) {
            if (!isLocalItem(item)) {
                continue;
            }
            YTIconType icon = icon(item);
            if (icon == YTIconType.QUEUE_PLAY_NEXT && next == null) {
                next = item;
            } else if (icon == YTIconType.QUEUE_PLAY_LAST && last == null) {
                last = item;
            }
        }

        if (next == null && last == null && !PlaylistCardSource.isPlaylistId(playlistId)) {
            return menu;
        }
        next = prepareLocalItem(next == null ? queueItem(YTIconType.QUEUE_PLAY_NEXT, nextLabel) : next);
        if (allowLast) {
            last = prepareLocalItem(last == null ? queueItem(YTIconType.QUEUE_PLAY_LAST, lastLabel) : last);
        }
        if (PlaylistCardSource.isPlaylistId(playlistId)) {
            next = bindPlaylist(next, playlistId);
            if (allowLast) {
                last = bindPlaylist(last, playlistId);
            }
        }
        var completed = menu.toBuilder().clearItems();
        boolean added = false;
        for (MenuItem item : menu.getItemsList()) {
            YTIconType icon = icon(item);
            if (isLocalItem(item) && (icon == YTIconType.QUEUE_PLAY_NEXT || icon == YTIconType.QUEUE_PLAY_LAST)) {
                if (!added) {
                    completed.addItems(next);
                    if (allowLast) {
                        completed.addItems(last);
                    }
                    added = true;
                }
            } else {
                completed.addItems(item);
            }
        }

        if (!added) {
            completed.clearItems().addItems(next);
            if (allowLast) {
                completed.addItems(last);
            }
            completed.addAllItems(menu.getItemsList());
        }
        MenuRenderer result = completed.build();
        return result.equals(menu) ? menu : result;
    }

    /**
     * Injection point.
     * <p>
     * Capture from the selected source before native menu binding resets shared flyout state.
     */
    public static PlaylistCardSource.Selection playlistSource(@Nullable Object source, @Nullable View sender) {
        boolean collection = false;
        try {
            if (!NativePlaylistQueuePatch.canQueuePlaylist()) {
                return new PlaylistCardSource.Selection("", false);
            }
            collection = sender != null && FeedVideoCardsPatch.isPlaylistTap(sender);
            if (collection) {
                try {
                    var mounted = PlaylistCardSource.fromView(sender);
                    if (mounted != null) {
                        return mounted;
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not read mounted playlist command", ex);
                }
            }
            if (source instanceof FlyoutUtils.FlyoutMenuVideoIdInterface) {
                return new PlaylistCardSource.Selection("", collection);
            }
            byte[] bytes = source instanceof FlyoutUtils.ProtocolBufferFieldInterface buffer ? buffer.patch_getBuffer()
                    : source instanceof MessageLite message ? message.toByteArray() : null;
            if (bytes == null) {
                return new PlaylistCardSource.Selection("", collection);
            }
            try {
                return PlaylistCardSource.fromElement(bytes, collection);
            } catch (InvalidProtocolBufferException ex) {
                // Legacy/native menu tags can contain their renderer rather than Element.
                Logger.printDebug(() -> "Flyout source is not an Element", ex);
                String id = collection ? PlaylistCardSource.fromNavigation(bytes) : "";
                return new PlaylistCardSource.Selection(id, collection);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "playlistSource failure", ex);
            return new PlaylistCardSource.Selection("", collection);
        }
    }

    static String playlistIdFromMenu(MenuRenderer menu) throws InvalidProtocolBufferException {
        String found = "";
        for (MenuItem item : menu.getItemsList()) {
            var command = endpoint(item);
            String encoded = command.hasShareEntityServiceEndpoint()
                    ? command.getShareEntityServiceEndpoint().getSerializedShareEntity()
                    : command.getLegacyShareEntityEndpoint().getSerializedShareEntity();
            if (encoded.isEmpty()) {
                continue;
            }
            var entity = PlaylistQueueRenderer.ShareEntity.parseFrom(Base64.decode(Uri.decode(encoded), Base64.URL_SAFE));
            // Individual videos may share a surrounding playlist too. Only a
            // collection-only ShareEntity can authorize importing its whole list.
            if (!entity.getVideoId().isEmpty() || !PlaylistCardSource.isPlaylistId(entity.getPlaylistId())) {
                continue;
            }
            if (!found.isEmpty() && !found.equals(entity.getPlaylistId())) {
                return "";
            }
            found = entity.getPlaylistId();
        }
        return found;
    }

    @Nullable
    public static Runnable getPlaylistQueueAction(@Nullable Object source) {
        if (!AddToQueuePatch.isNativeQueueEnabled() || !(source instanceof MessageLite message)) {
            return null;
        }
        try {
            var item = MenuItem.parseFrom(message.toByteArray());
            YTIconType icon = icon(item);
            if (!isLocalItem(item) || icon != YTIconType.QUEUE_PLAY_NEXT && icon != YTIconType.QUEUE_PLAY_LAST) {
                return null;
            }
            var command = endpoint(item);
            return command.hasQueuePlaylistId() ? NativePlaylistQueuePatch.getPlaylistQueueAction(
                    command.getQueuePlaylistId(), icon == YTIconType.QUEUE_PLAY_LAST) : null;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read native playlist menu action", ex);
            return null;
        }
    }

    /**
     * Injection point.
     * <p>
     * Direct sheet/popup paths can bypass the factory's Runnable.
     */
    public static boolean onPlaylistQueueItemClick(MessageLite item) {
        Runnable action = getPlaylistQueueAction(item);
        if (action == null) {
            return false;
        }
        action.run();
        return true;
    }

    private static PlaylistQueueRenderer.NavigationEndpoint endpoint(MenuItem item) {
        if (item.hasMenuConditionalServiceItemRenderer()) {
            return item.getMenuConditionalServiceItemRenderer().getServiceEndpoint();
        }
        if (item.hasMenuConditionalNavigationItemRenderer()) {
            return item.getMenuConditionalNavigationItemRenderer().getNavigationEndpoint();
        }
        if (item.hasMenuServiceItemRenderer()) {
            return item.getMenuServiceItemRenderer().getServiceEndpoint();
        }
        return item.getMenuNavigationItemRenderer().getNavigationEndpoint();
    }

    private static MenuItem bindPlaylist(MenuItem item, String playlistId) {
        var command = PlaylistQueueRenderer.NavigationEndpoint.newBuilder()
                .setQueuePlaylistId(playlistId)
                .setWatchEndpoint(PlaylistQueueRenderer.WatchEndpoint.newBuilder().setPlaylistId(playlistId));
        if (item.hasMenuConditionalServiceItemRenderer()) {
            return item.toBuilder().setMenuConditionalServiceItemRenderer(item.getMenuConditionalServiceItemRenderer()
                    .toBuilder().setServiceEndpoint(command)).build();
        }
        if (item.hasMenuConditionalNavigationItemRenderer()) {
            return item.toBuilder().setMenuConditionalNavigationItemRenderer(item.getMenuConditionalNavigationItemRenderer()
                    .toBuilder().setNavigationEndpoint(command)).build();
        }
        if (item.hasMenuServiceItemRenderer()) {
            return item.toBuilder().setMenuServiceItemRenderer(item.getMenuServiceItemRenderer()
                    .toBuilder().setServiceEndpoint(command)).build();
        }
        return item.toBuilder().setMenuNavigationItemRenderer(item.getMenuNavigationItemRenderer()
                .toBuilder().setNavigationEndpoint(command)).build();
    }

    private static MenuItem queueItem(YTIconType icon, String label) {
        // No copied Premium/Cast/backend endpoint. The shared stock overrides
        // dispatch this row to Native; other modes cannot run an unrelated command.
        return MenuItem.newBuilder().setMenuConditionalServiceItemRenderer(
                PlaylistQueueRenderer.MenuConditionalServiceItem.newBuilder()
                        .setText(PlaylistQueueRenderer.Text.newBuilder().setSimpleText(label))
                        .setIcon(Icon.newBuilder().setYtIconType(icon))
                        .setVisibility(MDX_DISCONNECTED)).build();
    }

    private static boolean isLocalItem(MenuItem item) {
        // Cast actions can share the queue icon, but retain their native MDX condition.
        return visibility(item) != MDX_CONNECTED;
    }

    private static int visibility(MenuItem item) {
        if (item.hasMenuConditionalServiceItemRenderer()) {
            return item.getMenuConditionalServiceItemRenderer().getVisibility();
        }
        if (item.hasMenuConditionalNavigationItemRenderer()) {
            return item.getMenuConditionalNavigationItemRenderer().getVisibility();
        }
        return MDX_DISCONNECTED;
    }

    private static MenuItem prepareLocalItem(MenuItem item) {
        // Remove the secondary Premium icon in the model as well as in the shared
        // list binder: legacy sheets do not record buttons through that binder.
        if (item.hasMenuConditionalServiceItemRenderer()) {
            return item.toBuilder().setMenuConditionalServiceItemRenderer(
                    item.getMenuConditionalServiceItemRenderer().toBuilder()
                            .setVisibility(MDX_DISCONNECTED).clearSecondaryIcon()).build();
        }

        if (item.hasMenuConditionalNavigationItemRenderer()) {
            return item.toBuilder().setMenuConditionalNavigationItemRenderer(
                    item.getMenuConditionalNavigationItemRenderer().toBuilder().setVisibility(MDX_DISCONNECTED)).build();
        }

        if (item.hasMenuServiceItemRenderer()) {
            return item.toBuilder().setMenuServiceItemRenderer(
                    item.getMenuServiceItemRenderer().toBuilder().clearSecondaryIcon()).build();
        }

        if (item.hasMenuNavigationItemRenderer()) {
            return item.toBuilder().setMenuNavigationItemRenderer(
                    item.getMenuNavigationItemRenderer().toBuilder().clearSecondaryIcon()).build();
        }

        return item;
    }

    private static YTIconType icon(MenuItem item) {
        if (item.hasMenuConditionalServiceItemRenderer()) {
            return item.getMenuConditionalServiceItemRenderer().getIcon().getYtIconType();
        }
        if (item.hasMenuConditionalNavigationItemRenderer()) {
            return item.getMenuConditionalNavigationItemRenderer().getIcon().getYtIconType();
        }
        if (item.hasMenuServiceItemRenderer()) {
            return item.getMenuServiceItemRenderer().getIcon().getYtIconType();
        }
        return item.getMenuNavigationItemRenderer().getIcon().getYtIconType();
    }

    private QueueFlyoutActions() {}
}
