/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import static app.morphe.extension.shared.StringRef.str;

import com.google.protobuf.MessageLite;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.innertube.IconOuterClass.Icon;
import app.morphe.extension.youtube.innertube.IconOuterClass.YTIconType;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.MenuItem;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.MenuRenderer;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.patches.AddToQueuePatch;
import app.morphe.extension.youtube.settings.Settings;

/** Adjusts native menu data before YouTube filters and binds its rows. */
public final class QueueFlyoutActions {
    private static final int MDX_CONNECTED = 1;
    private static final int MDX_DISCONNECTED = 2;

    public interface Menu {
        MessageLite patch_parseQueueFlyout(byte[] bytes);
    }

    /** Injection point. Other queue providers and unrelated menu sources keep the original model. */
    public static MessageLite completeQueueMenu(MessageLite original) {
        if (original == null) return null;
        String videoId = FlyoutUtils.getFlyoutVideoId();
        if (NativePlaylistQueuePatch.getFlyoutQueueAction(videoId, false) == null) return original;
        try {
            MenuRenderer menu = MenuRenderer.parseFrom(original.toByteArray());
            MenuRenderer completed = completeMenu(menu,
                    NativePlaylistQueuePatch.canShowFlyoutLast(),
                    str("morphe_native_playlist_queue_next"), str("morphe_native_playlist_queue_last"));
            if (completed == menu) return original;
            return ((Menu) original).patch_parseQueueFlyout(completed.toByteArray());
        } catch (Exception exception) {
            Logger.printException(() -> "Could not prepare native queue menu", exception);
            return original;
        }
    }

    /** Direct native sheet dispatch, which does not run the button model's Runnable. */
    public static boolean onStockQueueItemClick(Enum<?> icon) {
        return icon != null && Settings.QUEUE_TYPE.get() == AddToQueuePatch.QueueType.NATIVE
                && AddToQueuePatch.flyoutButtonClickLogic(icon.name(), FlyoutUtils.getFlyoutVideoId());
    }

    static MenuRenderer completeMenu(MenuRenderer menu, boolean allowLast, String nextLabel, String lastLabel) {
        MenuItem next = null;
        MenuItem last = null;
        for (MenuItem item : menu.getItemsList()) {
            if (!isLocalItem(item)) continue;
            YTIconType icon = icon(item);
            if (icon == YTIconType.QUEUE_PLAY_NEXT && next == null) next = item;
            else if (icon == YTIconType.QUEUE_PLAY_LAST && last == null) last = item;
        }
        if (next == null && last == null) return menu;
        next = prepareLocalItem(next == null ? queueItem(YTIconType.QUEUE_PLAY_NEXT, nextLabel) : next);
        if (allowLast) {
            last = prepareLocalItem(last == null ? queueItem(YTIconType.QUEUE_PLAY_LAST, lastLabel) : last);
        }
        var completed = menu.toBuilder().clearItems();
        boolean added = false;
        for (MenuItem item : menu.getItemsList()) {
            YTIconType icon = icon(item);
            if (isLocalItem(item) && (icon == YTIconType.QUEUE_PLAY_NEXT || icon == YTIconType.QUEUE_PLAY_LAST)) {
                if (!added) {
                    completed.addItems(next);
                    if (allowLast) completed.addItems(last);
                    added = true;
                }
            } else completed.addItems(item);
        }
        MenuRenderer result = completed.build();
        return result.equals(menu) ? menu : result;
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
