/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4404
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.CodedOutputStream;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer;

public class PlaylistCardSourceTest {
    private static final String VIDEO = "abcdefghijk";
    private static final String MIX = "RD" + VIDEO;
    private static final String URL = "https://www.youtube.com/watch?v=" + VIDEO + "&list=" + MIX;

    private static byte[] field(int number, byte[] value) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var output = CodedOutputStream.newInstance(bytes);
        output.writeByteArray(number, value);
        output.flush();
        return bytes.toByteArray();
    }

    private static byte[] watch() {
        return PlaylistQueueRenderer.NavigationEndpoint.newBuilder().setWatchEndpoint(
                PlaylistQueueRenderer.WatchEndpoint.newBuilder().setVideoId(VIDEO).setPlaylistId(MIX))
                .build().toByteArray();
    }

    private static byte[] wrappedWatch() throws Exception {
        // Native conditional command: the first branch opens a dialog, the second watches the Mix.
        var branches = new ByteArrayOutputStream();
        branches.write(field(1, field(79289575, field(1, new byte[0]))));
        branches.write(field(2, watch()));
        return field(471082452, branches.toByteArray());
    }

    private static byte[] card(String url, boolean mix, String timestamp, byte[] command) throws Exception {
        var video = FeedCardLayoutOuterClass.VideoData.newBuilder().setUrl(url).setThumbnail(
                FeedCardLayoutOuterClass.VideoThumbnail.newBuilder().setIsMix(mix).setTimestampText(timestamp));
        var data = FeedCardLayoutOuterClass.VideoWithContextData.newBuilder().setVideoData(video).setOnTap(
                PlaylistQueueRenderer.ElementsCommand.newBuilder().setInnertubeCommand(
                        PlaylistQueueRenderer.NavigationEndpoint.parseFrom(command)).build().toByteString());
        var model = FeedCardLayoutOuterClass.ElementModel.newBuilder().setVideoWithContextModel(
                FeedCardLayoutOuterClass.VideoWithContextModel.newBuilder().setData(data));
        return FeedCardLayoutOuterClass.Element.newBuilder().setType(FeedCardLayoutOuterClass.ElementType.newBuilder()
                .setComponentType(FeedCardLayoutOuterClass.ComponentType.newBuilder().setModel(model)))
                .build().toByteArray();
    }

    @Test
    public void wrappedMixUsesItsNativeCardUrlForBothMenuActions() throws Exception {
        var source = PlaylistCardSource.fromElement(card(URL, true, "", wrappedWatch()), true);
        assertTrue(source.playlist());
        assertEquals(MIX, source.playlistId());
        var menu = QueueFlyoutActions.completeMenu(PlaylistQueueRenderer.MenuRenderer.getDefaultInstance(),
                true, "Next", "Last", source.playlistId());
        for (var item : menu.getItemsList()) {
            assertEquals(MIX, item.getMenuConditionalServiceItemRenderer().getServiceEndpoint().getQueuePlaylistId());
        }
        assertEquals(2, menu.getItemsCount());
    }

    @Test
    public void ordinaryVideoCannotImportItsSurroundingMix() throws Exception {
        var source = PlaylistCardSource.fromElement(card(URL, false, "", wrappedWatch()), true);
        assertFalse(source.playlist());
        assertEquals("", source.playlistId());
    }

    @Test
    public void mixUrlStillRequiresTheSelectedCollectionWithoutDuration() throws Exception {
        assertFalse(PlaylistCardSource.fromElement(card(URL, true, "", wrappedWatch()), false).playlist());
        assertFalse(PlaylistCardSource.fromElement(card(URL, true, "3:20", wrappedWatch()), true).playlist());
    }

    @Test
    public void invalidOrIndexedUrlsCannotAuthorizeAnUnresolvedMix() throws Exception {
        for (String url : List.of("", "not a url", "https://www.youtube.com/bad path",
                URL.replace("www.youtube.com", "youtube.com.example.org"), URL.replace("https:", "file:"),
                URL.replace("/watch?", "/playlist?"), URL + "&index=0", URL + "&index",
                URL + "&list=RDother", URL + "&v=ZYXWVUTSRQP", URL.replace("list=" + MIX, "list=PLother"),
                URL.replace("v=" + VIDEO, "v=bad"), URL.replace("list=" + MIX, "list=RD%20bad"))) {
            var source = PlaylistCardSource.fromElement(card(url, true, "", wrappedWatch()), true);
            assertTrue(url, source.playlist());
            assertEquals(url, "", source.playlistId());
        }
    }

    @Test
    public void nativeMixUrlDoesNotDependOnQueryOrder() throws Exception {
        var url = "https://www.youtube.com/watch?list=" + MIX + "&v=" + VIDEO + "&feature=share";
        assertEquals(MIX, PlaylistCardSource.fromElement(card(url, true, "", wrappedWatch()), true).playlistId());
    }

    @Test
    public void directMixCommandsRemainAuthoritative() throws Exception {
        assertEquals(MIX, PlaylistCardSource.fromElement(card("", true, "", watch()), true).playlistId());
        assertEquals(MIX, PlaylistCardSource.fromElement(card(URL.replace(MIX, "RDZYXWVUTSRQP"),
                true, "", watch()), true).playlistId());
        var indexed = PlaylistQueueRenderer.NavigationEndpoint.parseFrom(watch()).toBuilder().setWatchEndpoint(
                PlaylistQueueRenderer.WatchEndpoint.newBuilder().setVideoId(VIDEO).setPlaylistId(MIX).setIndex(0)).build();
        assertFalse(PlaylistCardSource.fromElement(card(URL, true, "", indexed.toByteArray()), true).playlist());
    }
}
