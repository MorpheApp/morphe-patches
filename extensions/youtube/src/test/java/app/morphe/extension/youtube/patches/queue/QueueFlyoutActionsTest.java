/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.CodedOutputStream;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import app.morphe.extension.shared.innertube.IconOuterClass.Icon;
import app.morphe.extension.shared.innertube.IconOuterClass.YTIconType;
import app.morphe.extension.shared.innertube.queue.PlaylistQueueRenderer;
import app.morphe.extension.shared.innertube.queue.PlaylistQueueRenderer.MenuItem;
import app.morphe.extension.shared.innertube.queue.PlaylistQueueRenderer.MenuRenderer;

public class QueueFlyoutActionsTest {
    @Test
    public void playlistNextAndLastKeepTheirOwnCollectionTargetAfterSerialization() throws Exception {
        for (String playlist : List.of("PL-public", "PL-private", "WL", "LL", "RD-mix")) {
            var completed = QueueFlyoutActions.completeMenu(MenuRenderer.getDefaultInstance(),
                    "Next", "Last", playlist, false);
            assertEquals(2, completed.getItemsCount());
            var rebound = MenuRenderer.parseFrom(completed.toByteArray());
            assertEquals(playlist, rebound.getItems(0).getMenuConditionalServiceItemRenderer()
                    .getServiceEndpoint().getQueuePlaylistId());
            assertEquals(playlist, rebound.getItems(1).getMenuConditionalServiceItemRenderer()
                    .getServiceEndpoint().getQueuePlaylistId());
            assertEquals(YTIconType.QUEUE_PLAY_NEXT, rebound.getItems(0)
                    .getMenuConditionalServiceItemRenderer().getIcon().getYtIconType());
            assertEquals(YTIconType.QUEUE_PLAY_LAST, rebound.getItems(1)
                    .getMenuConditionalServiceItemRenderer().getIcon().getYtIconType());
        }
    }

    @Test
    public void pageToolbarBindsOnlyLocalNextToItsOwnPlaylist() throws Exception {
        var next = conditional(YTIconType.QUEUE_PLAY_NEXT_VALUE, 2);
        var remote = conditional(YTIconType.QUEUE_PLAY_NEXT_VALUE, 1);
        var shuffle = MenuItem.newBuilder().setMenuServiceItemRenderer(
                PlaylistQueueRenderer.MenuServiceItem.newBuilder()
                        .setIcon(Icon.newBuilder().setYtIconType(YTIconType.SHUFFLE))
                        .setServiceEndpoint(PlaylistQueueRenderer.NavigationEndpoint.newBuilder()
                                .setWatchEndpoint(PlaylistQueueRenderer.WatchEndpoint.newBuilder()
                                        .setPlaylistId("PLQdxfrZM11Xg")))).build();
        var toolbar = MenuRenderer.newBuilder().addItems(next).addItems(remote).build();
        assertSame(toolbar, QueueFlyoutActions.bindPlaylistPageMenu(toolbar));

        var stock = toolbar.toBuilder().addItems(shuffle).build();
        var result = MenuRenderer.parseFrom(QueueFlyoutActions.bindPlaylistPageMenu(stock).toByteArray());
        var command = result.getItems(0).getMenuConditionalServiceItemRenderer().getServiceEndpoint();
        assertEquals("PLQdxfrZM11Xg", command.getPlaylistPagePlayNextPlaylistId());
        assertEquals(next.getMenuConditionalServiceItemRenderer().getServiceEndpoint(),
                command.toBuilder().clearPlaylistPagePlayNextPlaylistId().build());
        assertEquals(remote, result.getItems(1));
        assertEquals(shuffle, result.getItems(2));
    }

    // Write the native conditional wire shape independently of our schema.
    // Using ordinary service builders here previously missed the real feed format.
    private static MenuItem conditional(int icon, int visibility) throws Exception {
        var iconBytes = new ByteArrayOutputStream();
        var output = CodedOutputStream.newInstance(iconBytes);
        output.writeInt32(1, icon);
        output.flush();
        var renderer = new ByteArrayOutputStream();
        output = CodedOutputStream.newInstance(renderer);
        output.writeByteArray(2, iconBytes.toByteArray());
        output.writeByteArray(3, new byte[] { 8, 1 }); // Opaque original endpoint.
        output.writeInt32(7, visibility);
        output.writeByteArray(12, iconBytes.toByteArray());
        output.writeString(90, "unknown renderer field");
        output.flush();
        var item = new ByteArrayOutputStream();
        output = CodedOutputStream.newInstance(item);
        output.writeByteArray(77258115, renderer.toByteArray());
        output.flush();
        return MenuItem.parseFrom(item.toByteArray());
    }

    @Test public void realConditionalShapeAddsLastBesideLocalNextAndPreservesCastAndUnknownData() throws Exception {
        var remote = conditional(251, 1);
        var next = conditional(251, 2);
        var share = conditional(YTIconType.SHARE.getNumber(), 2);
        var menuBytes = new ByteArrayOutputStream();
        var output = CodedOutputStream.newInstance(menuBytes);
        output.writeMessage(1, remote);
        output.writeMessage(1, next);
        output.writeMessage(1, share);
        output.writeString(80, "unknown menu field");
        output.flush();
        var menu = MenuRenderer.parseFrom(menuBytes.toByteArray());
        var completed = QueueFlyoutActions.completeMenu(menu, "Next", "Last");
        assertEquals(4, completed.getItemsCount());
        assertArrayEquals(remote.toByteArray(), completed.getItems(0).toByteArray());
        assertArrayEquals(next.toBuilder().setMenuConditionalServiceItemRenderer(
                next.getMenuConditionalServiceItemRenderer().toBuilder().clearSecondaryIcon()).build().toByteArray(),
                completed.getItems(1).toByteArray());
        assertArrayEquals(share.toByteArray(), completed.getItems(3).toByteArray());
        var last = completed.getItems(2).getMenuConditionalServiceItemRenderer();
        assertEquals(YTIconType.QUEUE_PLAY_LAST, last.getIcon().getYtIconType());
        assertEquals("Last", last.getText().getSimpleText());
        assertEquals(2, last.getVisibility());
        assertFalse(last.hasServiceEndpoint());
        assertSame(completed, QueueFlyoutActions.completeMenu(completed, "Next", "Last"));
        assertArrayEquals(completed.toByteArray(), QueueFlyoutActions.completeMenu(
                MenuRenderer.parseFrom(completed.toByteArray()), "Next", "Last").toByteArray());
    }

    @Test public void suppliedLastIsGroupedWithoutLosingItsEndpoint() throws Exception {
        var next = conditional(251, 2);
        var share = conditional(YTIconType.SHARE.getNumber(), 2);
        var last = conditional(895, 7);
        var menu = MenuRenderer.newBuilder().addItems(next).addItems(share).addItems(last).build();
        var completed = QueueFlyoutActions.completeMenu(menu, "Next", "Last");
        assertEquals(3, completed.getItemsCount());
        assertEquals(2, completed.getItems(1).getMenuConditionalServiceItemRenderer().getVisibility());
        assertEquals(last.getMenuConditionalServiceItemRenderer().getServiceEndpoint(),
                completed.getItems(1).getMenuConditionalServiceItemRenderer().getServiceEndpoint());
        assertEquals(last.toBuilder().setMenuConditionalServiceItemRenderer(
                last.getMenuConditionalServiceItemRenderer().toBuilder().setVisibility(2).clearSecondaryIcon()).build(),
                completed.getItems(1));
        assertEquals(share, completed.getItems(2));
    }

    @Test public void castOnlyAndUnrelatedMenusStayIdentical() throws Exception {
        var cast = MenuRenderer.newBuilder().addItems(conditional(251, 1))
                .addItems(conditional(895, 1)).build();
        assertSame(cast, QueueFlyoutActions.completeMenu(cast, "Next", "Last"));
        var unrelated = MenuRenderer.newBuilder().addItems(conditional(YTIconType.SHARE.getNumber(), 2)).build();
        assertSame(unrelated, QueueFlyoutActions.completeMenu(unrelated, "Next", "Last"));
        var next = MenuRenderer.newBuilder().addItems(conditional(251, 2)).build();
        var cleaned = QueueFlyoutActions.completeMenu(next, "Next", "Last");
        assertFalse(cleaned.getItems(0).getMenuConditionalServiceItemRenderer().hasSecondaryIcon());
        assertSame(cleaned, QueueFlyoutActions.completeMenu(cleaned, "Next", "Last"));
    }

    @Test public void shortsMenuAddsMissingLocalActionsWithoutChangingCastOrOtherItems() throws Exception {
        var cast = conditional(251, 1);
        var share = conditional(YTIconType.SHARE.getNumber(), 2);
        var bytes = new ByteArrayOutputStream();
        var output = CodedOutputStream.newInstance(bytes);
        output.writeMessage(1, cast);
        output.writeMessage(1, share);
        output.writeString(80, "unknown menu field");
        output.flush();
        var menu = MenuRenderer.parseFrom(bytes.toByteArray());

        assertSame(menu, QueueFlyoutActions.completeMenu(menu, "Next", "Last", "", false));
        var completed = QueueFlyoutActions.completeMenu(menu, "Next", "Last", "", true);
        assertEquals(4, completed.getItemsCount());
        var next = completed.getItems(0).getMenuConditionalServiceItemRenderer();
        assertEquals(YTIconType.QUEUE_PLAY_NEXT, next.getIcon().getYtIconType());
        assertFalse(next.hasServiceEndpoint());
        assertEquals(2, next.getVisibility());
        var last = completed.getItems(1).getMenuConditionalServiceItemRenderer();
        assertEquals(YTIconType.QUEUE_PLAY_LAST, last.getIcon().getYtIconType());
        assertFalse(last.hasServiceEndpoint());
        assertArrayEquals(cast.toByteArray(), completed.getItems(2).toByteArray());
        assertArrayEquals(share.toByteArray(), completed.getItems(3).toByteArray());
        assertArrayEquals(menu.toBuilder().clearItems().build().toByteArray(),
                completed.toBuilder().clearItems().build().toByteArray());
        assertSame(completed, QueueFlyoutActions.completeMenu(completed, "Next", "Last", "", true));
    }

    @Test public void nativeNavigationAndServiceVariantsKeepTheirCommandsAndLoseOnlyTheBadge() throws Exception {
        int[][] variants = { { 66441108, 11 }, { 66441155, 9 }, { 77258115, 12 }, { 77262503, 0 } };
        for (int[] variant : variants) {
            var renderer = new ByteArrayOutputStream();
            var output = CodedOutputStream.newInstance(renderer);
            output.writeByteArray(2, new byte[] { 8, (byte) 251, 1 });
            output.writeByteArray(3, new byte[] { 8, 1 });
            if (variant[0] == 77258115 || variant[0] == 77262503) output.writeInt32(7, 2);
            if (variant[1] != 0) output.writeByteArray(variant[1], new byte[] { 8, 1 });
            output.writeString(90, "unknown");
            output.flush();
            var bytes = new ByteArrayOutputStream();
            output = CodedOutputStream.newInstance(bytes);
            output.writeByteArray(variant[0], renderer.toByteArray());
            output.flush();
            var menu = MenuRenderer.newBuilder().addItems(MenuItem.parseFrom(bytes.toByteArray())).build();
            var completed = QueueFlyoutActions.completeMenu(menu, "Next", "Last");
            assertEquals(2, completed.getItemsCount());
            assertSame(completed, QueueFlyoutActions.completeMenu(completed, "Next", "Last"));
            // The only removed field is the native secondary icon. Opaque command
            // and renderer fields survive even for conditional navigation items.
            var expectedRenderer = new ByteArrayOutputStream();
            output = CodedOutputStream.newInstance(expectedRenderer);
            output.writeByteArray(2, new byte[] { 8, (byte) 251, 1 });
            output.writeByteArray(3, new byte[] { 8, 1 });
            if (variant[0] == 77258115 || variant[0] == 77262503) output.writeInt32(7, 2);
            output.writeString(90, "unknown");
            output.flush();
            var expected = new ByteArrayOutputStream();
            output = CodedOutputStream.newInstance(expected);
            output.writeByteArray(variant[0], expectedRenderer.toByteArray());
            output.flush();
            assertEquals(MenuItem.parseFrom(expected.toByteArray()), completed.getItems(0));
        }
    }

    @Test public void lastOnlyMenuStillUsesNativeNextAndLastPolicy() throws Exception {
        var original = MenuRenderer.newBuilder().addItems(conditional(895, 2)).build();
        var completed = QueueFlyoutActions.completeMenu(original, "Next", "Last");
        assertEquals(2, completed.getItemsCount());
        assertEquals(YTIconType.QUEUE_PLAY_NEXT,
                completed.getItems(0).getMenuConditionalServiceItemRenderer().getIcon().getYtIconType());
        assertFalse(completed.getItems(0).getMenuConditionalServiceItemRenderer().hasServiceEndpoint());
        assertEquals(YTIconType.QUEUE_PLAY_LAST,
                completed.getItems(1).getMenuConditionalServiceItemRenderer().getIcon().getYtIconType());
        assertEquals(original.getItems(0).getMenuConditionalServiceItemRenderer().getServiceEndpoint(),
                completed.getItems(1).getMenuConditionalServiceItemRenderer().getServiceEndpoint());
    }

    @Test public void unresolvedCollectionCannotBeMistakenForItsThumbnailVideo() throws Exception {
        var collection = PlaylistCardSource.fromElement(new byte[0], true);
        assertTrue(collection.playlist());
        assertEquals("", collection.playlistId());
        assertFalse(PlaylistCardSource.fromElement(new byte[0], false).playlist());
    }

}
