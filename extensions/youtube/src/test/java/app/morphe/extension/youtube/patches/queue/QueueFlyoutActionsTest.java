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

import com.google.protobuf.CodedOutputStream;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

import app.morphe.extension.youtube.innertube.IconOuterClass.YTIconType;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.MenuItem;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.MenuRenderer;

public class QueueFlyoutActionsTest {
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
        var completed = QueueFlyoutActions.completeMenu(menu, true, "Next", "Last");
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
        assertSame(completed, QueueFlyoutActions.completeMenu(completed, true, "Next", "Last"));
        // Returning to no queue removes only our Last, including through serialization.
        assertArrayEquals(menu.toBuilder().setItems(1, completed.getItems(1)).build().toByteArray(), QueueFlyoutActions.completeMenu(
                MenuRenderer.parseFrom(completed.toByteArray()), false, "Next", "Last").toByteArray());
    }

    @Test public void suppliedLastIsGroupedAndFollowsQueueStateWithoutLosingItsEndpoint() throws Exception {
        var next = conditional(251, 2);
        var share = conditional(YTIconType.SHARE.getNumber(), 2);
        var last = conditional(895, 7);
        var menu = MenuRenderer.newBuilder().addItems(next).addItems(share).addItems(last).build();
        var completed = QueueFlyoutActions.completeMenu(menu, true, "Next", "Last");
        assertEquals(3, completed.getItemsCount());
        assertEquals(2, completed.getItems(1).getMenuConditionalServiceItemRenderer().getVisibility());
        assertEquals(last.getMenuConditionalServiceItemRenderer().getServiceEndpoint(),
                completed.getItems(1).getMenuConditionalServiceItemRenderer().getServiceEndpoint());
        assertEquals(last.toBuilder().setMenuConditionalServiceItemRenderer(
                last.getMenuConditionalServiceItemRenderer().toBuilder().setVisibility(2).clearSecondaryIcon()).build(),
                completed.getItems(1));
        assertEquals(share, completed.getItems(2));
        assertEquals(MenuRenderer.newBuilder().addItems(completed.getItems(0)).addItems(share).build(),
                QueueFlyoutActions.completeMenu(menu, false, "Next", "Last"));
    }

    @Test public void castOnlyAndUnrelatedMenusStayIdentical() throws Exception {
        var cast = MenuRenderer.newBuilder().addItems(conditional(251, 1))
                .addItems(conditional(895, 1)).build();
        assertSame(cast, QueueFlyoutActions.completeMenu(cast, true, "Next", "Last"));
        assertSame(cast, QueueFlyoutActions.completeMenu(cast, false, "Next", "Last"));
        var unrelated = MenuRenderer.newBuilder().addItems(conditional(YTIconType.SHARE.getNumber(), 2)).build();
        assertSame(unrelated, QueueFlyoutActions.completeMenu(unrelated, true, "Next", "Last"));
        var next = MenuRenderer.newBuilder().addItems(conditional(251, 2)).build();
        var cleaned = QueueFlyoutActions.completeMenu(next, false, "Next", "Last");
        assertFalse(cleaned.getItems(0).getMenuConditionalServiceItemRenderer().hasSecondaryIcon());
        assertSame(cleaned, QueueFlyoutActions.completeMenu(cleaned, false, "Next", "Last"));
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
            var completed = QueueFlyoutActions.completeMenu(menu, true, "Next", "Last");
            assertEquals(2, completed.getItemsCount());
            var inactive = QueueFlyoutActions.completeMenu(completed, false, "Next", "Last");
            assertEquals(1, inactive.getItemsCount());
            assertSame(inactive, QueueFlyoutActions.completeMenu(inactive, false, "Next", "Last"));
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
            assertEquals(MenuItem.parseFrom(expected.toByteArray()), inactive.getItems(0));
        }
    }

    @Test public void lastOnlyMenuStillUsesNativeNextAndLastPolicy() throws Exception {
        var original = MenuRenderer.newBuilder().addItems(conditional(895, 2)).build();
        var inactive = QueueFlyoutActions.completeMenu(original, false, "Next", "Last");
        assertEquals(1, inactive.getItemsCount());
        assertEquals(YTIconType.QUEUE_PLAY_NEXT,
                inactive.getItems(0).getMenuConditionalServiceItemRenderer().getIcon().getYtIconType());
        assertFalse(inactive.getItems(0).getMenuConditionalServiceItemRenderer().hasServiceEndpoint());
        var active = QueueFlyoutActions.completeMenu(original, true, "Next", "Last");
        assertEquals(2, active.getItemsCount());
        assertEquals(YTIconType.QUEUE_PLAY_LAST,
                active.getItems(1).getMenuConditionalServiceItemRenderer().getIcon().getYtIconType());
        assertEquals(original.getItems(0).getMenuConditionalServiceItemRenderer().getServiceEndpoint(),
                active.getItems(1).getMenuConditionalServiceItemRenderer().getServiceEndpoint());
    }

}
