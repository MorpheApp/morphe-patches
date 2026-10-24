/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import androidx.annotation.Nullable;

import com.google.protobuf.InvalidProtocolBufferException;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.youtube.innertube.queue.QueueCardRenderer;

/** Server-formatted display text for a native playlist row. */
record VideoMetadata(String videoId, String title, String channelName, String videoInfo, String duration, boolean fullByline) {
    VideoMetadata {
        // Player metadata can arrive after the queue is created. Use the same
        // missing-title sentinel as queue entries so a later lookup can replace it.
        if (title == null || title.isBlank()) title = videoId;
    }

    private static final String WATCH_URL_PREFIX = "https://www.youtube.com/watch?v=";
    private static final String METADATA_SEPARATOR = " · ";

    @Nullable
    static VideoMetadata fromElement(byte[] bytes) throws InvalidProtocolBufferException {
        var model = QueueCardRenderer.Element.parseFrom(bytes).getType().getComponentType().getModel();
        if (!model.hasVideoWithContextModel()) return null;
        var card = model.getVideoWithContextModel().getVideoWithContextData();
        var video = card.getVideoData();
        String url = video.getDragAndDropUrl();
        if (!url.startsWith(WATCH_URL_PREFIX)) return null;

        // A mix/playlist card can link its first video but show the list's title.
        // Indexed entries identify an individual video; unindexed list links do not.
        if (url.contains("&list=") && !url.contains("&index=")) return null;
        String videoId = url.substring(WATCH_URL_PREFIX.length()).split("&", 2)[0];
        if (!PlaylistQueue.isVideoId(videoId)) return null;

        var metadata = video.getLockupMetadata().getLockupMetadataViewModel();
        String title = metadata.getTitle().getContent();
        if (title.isEmpty()) return null;
        List<String> rows = new ArrayList<>();
        for (var row : metadata.getMetadata().getContentMetadataViewModel().getMetadataRowsList()) {
            List<String> parts = new ArrayList<>();
            for (var part : row.getMetadataPartsList()) {
                String content = part.getText().getContent();
                if (!content.isEmpty()) parts.add(content);
            }

            if (!parts.isEmpty()) rows.add(String.join(METADATA_SEPARATOR, parts));
        }

        VideoMetadata result = new VideoMetadata(videoId, title, "", String.join("\n", rows),
                video.getThumbnail().getTimestampText(), false);
        return result.withChannelLabel(video.getAvatar().getAccessibilityText(), card.getAccessibilityText());
    }

    /** Native search cards provide the full byline, including collaborating channels. */
    @Nullable
    static VideoMetadata fromSearch(JSONObject response, String videoId) {
        JSONObject contents = response.optJSONObject("contents");
        JSONObject sections = contents == null ? null : contents.optJSONObject("sectionListRenderer");
        var sectionItems = sections == null ? null : sections.optJSONArray("contents");
        if (sectionItems == null) return null;
        for (int sectionIndex = 0; sectionIndex < sectionItems.length(); sectionIndex++) {
            JSONObject section = sectionItems.optJSONObject(sectionIndex);
            JSONObject itemSection = section == null ? null : section.optJSONObject("itemSectionRenderer");
            var items = itemSection == null ? null : itemSection.optJSONArray("contents");
            if (items == null) continue;
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index);
                JSONObject video = item == null ? null : item.optJSONObject("compactVideoRenderer");
                if (video == null || !videoId.equals(video.optString("videoId"))) continue;
                String title = text(video.optJSONObject("title"));
                String channel = text(video.optJSONObject("longBylineText"));
                boolean fullByline = !channel.isEmpty();
                if (channel.isEmpty()) channel = text(video.optJSONObject("shortBylineText"));
                if (title.isEmpty() || channel.isEmpty()) continue;
                List<String> info = new ArrayList<>();
                String views = text(video.optJSONObject("shortViewCountText"));
                String age = text(video.optJSONObject("publishedTimeText"));
                if (!views.isEmpty()) info.add(views);
                if (!age.isEmpty()) info.add(age);
                return new VideoMetadata(videoId, title, channel, String.join(METADATA_SEPARATOR, info),
                        text(video.optJSONObject("lengthText")), fullByline);
            }
        }

        return null;
    }

    private static String text(JSONObject text) {
        if (text == null) return "";
        String simple = text.optString("simpleText", "");
        if (!simple.isEmpty()) return simple;
        var runs = text.optJSONArray("runs");
        StringBuilder result = new StringBuilder();
        if (runs != null) {
            for (int index = 0; index < runs.length(); index++) {
                JSONObject run = runs.optJSONObject(index);
                if (run != null) result.append(run.optString("text", ""));
            }
        }

        return result.toString();
    }

    boolean isComplete() {
        // A card/player can expose only the uploading channel, even when its other
        // details are complete. Only the native long byline confirms all collaborators.
        return fullByline && !title.isEmpty() && !title.equals(videoId)
                && !channelName.isEmpty() && !videoInfo.isEmpty();
    }

    VideoMetadata withCapture(VideoMetadata captured) {
        if (!fullByline) return captured.withChannelName(channelName);

        // Later feed/player captures must not downgrade an authoritative byline.
        return new VideoMetadata(videoId, captured.title().equals(videoId) ? title : captured.title(),
                channelName, videoInfo, captured.duration().isEmpty() ? duration : captured.duration(), true);
    }

    VideoMetadata withLookup(VideoMetadata fetched) {
        if (fullByline && !fetched.fullByline()) return this;

        // Preserve captured titles and their title-patch processing, but prefer
        // the lookup's native long byline over an uploader-only fallback.
        return new VideoMetadata(videoId, title.equals(videoId) ? fetched.title() : title,
                fetched.channelName(), fetched.videoInfo(),
                fetched.duration().isEmpty() ? duration : fetched.duration(), fetched.fullByline());
    }

    private VideoMetadata withChannelLabel(String avatarLabel, String description) {
        if (avatarLabel.isEmpty() && description.isEmpty()) return this;

        // The avatar label names this card's channel. Confirm the longest metadata
        // prefix at its end, or a complete segment of the card's accessibility text.
        // Neither path parses localized words such as "Go to channel" or "views".
        // Work backwards so separators inside a channel name remain part of the name.
        String name = "";
        for (String separator : List.of(METADATA_SEPARATOR, "\n")) {
            int boundary = videoInfo.lastIndexOf(separator);
            while (boundary > 0) {
                String candidate = videoInfo.substring(0, boundary);
                if (candidate.length() > name.length() && (avatarLabel.endsWith(candidate)
                        || description.contains(" - " + candidate + " - "))) name = candidate;
                boundary = videoInfo.lastIndexOf(separator, boundary - 1);
            }
        }

        return withChannelName(name);
    }

    VideoMetadata withChannelName(String name) {
        if (fullByline || name.isEmpty() || name.equals(channelName)) return this;
        if (videoInfo.isEmpty() || videoInfo.equals(name)) {
            return new VideoMetadata(videoId, title, name, "", duration, false);
        }

        // Cards may combine the channel, views and age into one text. Split only an
        // exact known channel prefix; channel names themselves can contain separators.
        for (String separator : List.of(METADATA_SEPARATOR, "\n")) {
            String prefix = name + separator;
            if (videoInfo.startsWith(prefix)) {
                return new VideoMetadata(videoId, title, name, videoInfo.substring(prefix.length()), duration, false);
            }
        }

        return this;
    }
}
