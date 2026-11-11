/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import app.morphe.extension.youtube.innertube.queue.QueueCardRenderer.AttributedText;
import app.morphe.extension.youtube.innertube.queue.QueueCardRenderer.Avatar;
import app.morphe.extension.youtube.innertube.queue.QueueCardRenderer.CardModel;
import app.morphe.extension.youtube.innertube.queue.QueueCardRenderer.ComponentType;
import app.morphe.extension.youtube.innertube.queue.QueueCardRenderer.ContentMetadata;
import app.morphe.extension.youtube.innertube.queue.QueueCardRenderer.ContentMetadataViewModel;
import app.morphe.extension.youtube.innertube.queue.QueueCardRenderer.Element;
import app.morphe.extension.youtube.innertube.queue.QueueCardRenderer.ElementType;
import app.morphe.extension.youtube.innertube.queue.QueueCardRenderer.LockupMetadata;
import app.morphe.extension.youtube.innertube.queue.QueueCardRenderer.LockupMetadataViewModel;
import app.morphe.extension.youtube.innertube.queue.QueueCardRenderer.MetadataPart;
import app.morphe.extension.youtube.innertube.queue.QueueCardRenderer.MetadataRow;
import app.morphe.extension.youtube.innertube.queue.QueueCardRenderer.VideoData;
import app.morphe.extension.youtube.innertube.queue.QueueCardRenderer.VideoWithContextData;
import app.morphe.extension.youtube.innertube.queue.QueueCardRenderer.VideoWithContextModel;

public class VideoMetadataTest {
    private static AttributedText text(String content) {
        return AttributedText.newBuilder().setContent(content).build();
    }
    private static String[] parse(String url, String title, String info,
            String channel, String label, String description) throws Exception {
        var row = MetadataRow.newBuilder().addMetadataParts(MetadataPart.newBuilder().setText(text(info)));
        var metadata = LockupMetadataViewModel.newBuilder().setTitle(text(title)).setMetadata(
            ContentMetadata.newBuilder().setContentMetadataViewModel(
                ContentMetadataViewModel.newBuilder().addMetadataRows(row)));
        var video = VideoData.newBuilder().setAvatar(Avatar.newBuilder().setAccessibilityText(label))
            .setDragAndDropUrl(url).setLockupMetadata(
                LockupMetadata.newBuilder().setLockupMetadataViewModel(metadata));
        var contextual = VideoWithContextModel.newBuilder().setVideoWithContextData(
            VideoWithContextData.newBuilder().setVideoData(video).setAccessibilityText(description));
        var element = Element.newBuilder().setType(ElementType.newBuilder().setComponentType(
            ComponentType.newBuilder().setModel(CardModel.newBuilder().setVideoWithContextModel(contextual))));
        var parsed = VideoMetadata.fromElement(element.build().toByteArray());
        if (parsed == null) return null;
        parsed = parsed.withChannelName(channel);
        return new String[]{parsed.videoId(), parsed.title(), parsed.channelName(), parsed.videoInfo()};
    }
    private static String[] merge(String json, String videoId, String title) throws Exception {
        var card = new VideoMetadata(videoId, title, "Starbound Extra", "157 views · 46 minutes ago", "29:04", false);
        var lookup = VideoMetadata.fromSearch(new org.json.JSONObject(json), videoId);
        var merged = card.withLookup(lookup).withCapture(card).withChannelName("Starbound Extra");
        var reverse = lookup.withCapture(card).withChannelName("Starbound Extra");
        merged = merged.withLookup(card);
        return new String[]{Boolean.toString(card.isComplete()), merged.channelName(),
            reverse.channelName(), merged.title(), Boolean.toString(merged.isComplete())};
    }
    private static String[] search(String json, String videoId) throws Exception {
        var parsed = VideoMetadata.fromSearch(new org.json.JSONObject(json), videoId);
        if (parsed == null) return null;
        return new String[]{parsed.title(), parsed.channelName(), parsed.videoInfo(), parsed.duration()};
    }
    private static String[] parse(String url, String title) throws Exception {
        return parse(url, title, "", "", "", "");
    }

    @Test public void lookupPreservesCollaboratorsAndLocalizedDetailsForRequestedVideo() throws Exception {
        String response = """
                {"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
                    {"compactVideoRenderer":{"videoId":"other_video","title":{"runs":[{"text":"Wrong result"}]}}},
                    {"compactVideoRenderer":{"videoId":"waZyE4Xl6bo",
                        "title":{"runs":[{"text":"A native title"}]},
                        "longBylineText":{"runs":[{"text":"Starbound Extra"},{"text":" and "},{"text":"Starbound HFY"}]},
                        "shortBylineText":{"runs":[{"text":"Starbound Extra"}]},
                        "shortViewCountText":{"runs":[{"text":"1,8 M de vues"}]},
                        "publishedTimeText":{"runs":[{"text":"il y a 2 ans"}]},
                        "lengthText":{"runs":[{"text":"29:04"}]}}}
                ]}}]}}}
                """;
        assertArrayEquals(new String[]{"A native title", "Starbound Extra and Starbound HFY",
                "1,8 M de vues · il y a 2 ans", "29:04"}, search(response, "waZyE4Xl6bo"));
        assertNull(search(response, "abcdefghijk"));
        assertNull(search("{}", "waZyE4Xl6bo"));
    }

    @Test public void uploaderCaptureCannotOverwriteCollaboratorsOrLookupTitle() throws Exception {
        String response = """
                {"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
                    {"compactVideoRenderer":{"videoId":"waZyE4Xl6bo", "title":{"runs":[{"text":"Search title"}]},
                        "longBylineText":{"runs":[{"text":"Starbound Extra"},{"text":" and "},{"text":"Starbound HFY"}]},
                        "shortViewCountText":{"runs":[{"text":"157 views"}]},
                        "publishedTimeText":{"runs":[{"text":"46 minutes ago"}]}}}
                ]}}]}}}
                """;
        assertArrayEquals(new String[]{"false", "Starbound Extra and Starbound HFY",
                "Starbound Extra and Starbound HFY", "Native title", "true"},
                merge(response, "waZyE4Xl6bo", "Native title"));
        for (String title : new String[]{"", "   ", "waZyE4Xl6bo"}) {
            assertArrayEquals(new String[]{"false", "Starbound Extra and Starbound HFY",
                    "Starbound Extra and Starbound HFY", "Search title", "true"},
                    merge(response, "waZyE4Xl6bo", title));
        }
    }

    @Test public void cardsRequireValidIdentityAndTitle() throws Exception {
        String url = "https://www.youtube.com/watch?v=abcdefghijk";
        assertArrayEquals(new String[]{"abcdefghijk", "A title", "", ""}, parse(url, "A title"));
        assertNull(parse("https://www.youtube.com/playlist?list=PLabcdefghijk", "A playlist"));
        assertNull(parse(url + "&list=RDabcdefghijk", "A mix"));
        assertArrayEquals(new String[]{"abcdefghijk", "A video in a playlist", "", ""},
                parse(url + "&list=PLabcdefghijk&index=2", "A video in a playlist"));
        assertNull(parse("https://www.youtube.com/watch?v=invalid", "A title"));
        assertNull(parse(url, ""));
    }

    @Test public void onlyExactKnownChannelPrefixIsSplit() throws Exception {
        String url = "https://www.youtube.com/watch?v=abcdefghijk";
        String channel = "La chaîne · 🎵", info = "1,8 M de vues · il y a 2 ans";
        assertArrayEquals(new String[]{"abcdefghijk", "Une vidéo 🎵", channel, info},
                parse(url, "Une vidéo 🎵", channel + " · " + info, channel, "", ""));
        assertArrayEquals(new String[]{"abcdefghijk", "Une vidéo 🎵", "", channel + " · " + info},
                parse(url, "Une vidéo 🎵", channel + " · " + info, "Another channel", "", ""));
    }

    @Test public void accessibilityTextIdentifiesLongChannelWithoutFlyoutMetadata() throws Exception {
        String url = "https://www.youtube.com/watch?v=abcdefghijk";
        String channel = "Starbound Extra and Starbound HFY", info = "157 views · 46 minutes ago";
        assertArrayEquals(new String[]{"abcdefghijk", "We Sent Earth Our Mightiest Weapons", channel, info},
                parse(url, "We Sent Earth Our Mightiest Weapons", channel + " · " + info,
                        "", "Go to channel " + channel, ""));
        assertArrayEquals(new String[]{"abcdefghijk", "A title", channel, info},
                parse(url, "A title", channel + " · " + info, "", "",
                        "A title - 10 minutes - " + channel + " - 157 views - 46 minutes ago - play video"));
        assertArrayEquals(new String[]{"abcdefghijk", "A title", "Stars · HFY", info},
                parse(url, "A title", "Stars · HFY · " + info, "", "Accéder à la chaîne Stars · HFY", ""));
        assertArrayEquals(new String[]{"abcdefghijk", "A title", "", channel + " · " + info},
                parse(url, "A title", channel + " · " + info, "", "Go to channel Another channel", ""));
    }
}
