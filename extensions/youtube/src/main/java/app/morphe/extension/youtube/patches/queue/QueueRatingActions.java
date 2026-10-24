/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import androidx.annotation.Nullable;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.List;

import app.morphe.extension.youtube.innertube.IconOuterClass.Icon;
import app.morphe.extension.youtube.innertube.IconOuterClass.YTIconType;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.Commands;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.LikeEndpoint.Status;

/** Native watch-page commands and entity identity, reused by native playlist buttons. */
record QueueRatingActions(String videoId, String entityKey, int initialStatus,
                          ByteString likeEndpoint, ByteString unlikeEndpoint,
                          ByteString dislikeEndpoint, ByteString undislikeEndpoint) {
    @Nullable
    static QueueRatingActions fromElement(NextResponseOuterClass.NewElement element)
            throws InvalidProtocolBufferException {
        var model = element.getType().getComponentType().getModel();
        var buttons = model.hasVideoActionBarModel()
                ? model.getVideoActionBarModel().getVideoActionBarData().getActionButtonsList()
                : model.getYoutubeModel().getViewModel().getCompactifyVideoActionBarViewModel().getActionButtonsList();
        NextResponseOuterClass.LikeButtonViewModel like = null;
        NextResponseOuterClass.DislikeButtonViewModel dislike = null;
        for (var action : buttons) {
            var button = action.getPrimaryButtonViewModel();
            if (button.hasLikeButtonViewModel()) like = button.getLikeButtonViewModel();
            if (button.hasDislikeButtonViewModel()) dislike = button.getDislikeButtonViewModel();
            if (button.hasSegmentedLikeDislikeButtonViewModel()) {
                var segmented = button.getSegmentedLikeDislikeButtonViewModel();
                like = segmented.getLikeButtonViewModel().getLikeButtonViewModel();
                dislike = segmented.getDislikeButtonViewModel().getDislikeButtonViewModel();
            }
        }

        if (like == null || dislike == null || like.getLikeStatusEntityKey().isEmpty()
                || !like.getLikeStatusEntityKey().equals(dislike.getDislikeEntityKey())) return null;
        var likeToggle = like.getToggleButtonViewModel().getToggleButtonViewModel();
        var dislikeToggle = dislike.getToggleButtonViewModel().getToggleButtonViewModel();
        ByteString likeEndpoint = endpoint(likeToggle.getDefaultButtonViewModel().getButtonViewModel().getOnTap());
        ByteString unlikeEndpoint = endpoint(likeToggle.getToggledButtonViewModel().getButtonViewModel().getOnTap());
        ByteString dislikeEndpoint = endpoint(dislikeToggle.getDefaultButtonViewModel().getButtonViewModel().getOnTap());
        ByteString undislikeEndpoint = endpoint(dislikeToggle.getToggledButtonViewModel().getButtonViewModel().getOnTap());
        String videoId = target(likeEndpoint, Status.LIKE);
        if (!PlaylistQueue.isVideoId(videoId) || !videoId.equals(target(unlikeEndpoint, Status.INDIFFERENT))
                || !videoId.equals(target(dislikeEndpoint, Status.DISLIKE))
                || !videoId.equals(target(undislikeEndpoint, Status.INDIFFERENT))) return null;
        int initialStatus = like.hasLikeStatusEntity()
                ? like.getLikeStatusEntity().getLikeStatus() : Status.INDIFFERENT_VALUE;
        return new QueueRatingActions(videoId, like.getLikeStatusEntityKey(), initialStatus,
                likeEndpoint, unlikeEndpoint, dislikeEndpoint, undislikeEndpoint);
    }

    private static ByteString endpoint(Commands commands) {
        if (!commands.getInnertubeCommand().isEmpty()) return commands.getInnertubeCommand();
        for (var command : commands.getSerialCommand().getCommandsList()) {
            ByteString endpoint = endpoint(command);
            if (!endpoint.isEmpty()) return endpoint;
        }

        return ByteString.EMPTY;
    }

    private static String target(ByteString bytes, Status expected) throws InvalidProtocolBufferException {
        var endpoint = PlaylistQueueRenderer.RatingEndpoint.parseFrom(bytes);
        var like = endpoint.getLikeEndpoint();
        return endpoint.hasLikeEndpoint() && like.hasStatus() && like.getStatus() == expected
                && like.getTarget().getPlaylistId().isEmpty() ? like.getTarget().getVideoId() : "";
    }

    List<PlaylistQueueRenderer.RatingAction> buttons(String likeLabel, String dislikeLabel) {
        return List.of(button(true, likeLabel), button(false, dislikeLabel));
    }

    private PlaylistQueueRenderer.RatingAction button(boolean isLike, String label) {
        var button = PlaylistQueueRenderer.RatingToggleButton.newBuilder()
                .setIsDisabled(false).setIsToggled(initialStatus == (isLike ? Status.LIKE_VALUE : Status.DISLIKE_VALUE))
                .setDefaultIcon(Icon.newBuilder().setYtIconType(isLike ? YTIconType.LIKE : YTIconType.DISLIKE))
                .setToggledIcon(Icon.newBuilder().setYtIconType(isLike ? YTIconType.LIKE_SELECTED : YTIconType.DISLIKE_SELECTED))
                .setDefaultServiceEndpoint(isLike ? likeEndpoint : dislikeEndpoint)
                .setToggledServiceEndpoint(isLike ? unlikeEndpoint : undislikeEndpoint)
                .setAccessibilityData(accessibility(label))
                .setToggledAccessibilityData(accessibility(label));
        return PlaylistQueueRenderer.RatingAction.newBuilder().setSlimMetadataToggleButton(
                PlaylistQueueRenderer.SlimRatingButton.newBuilder().setIsLike(isLike).setIsDislike(!isLike)
                        .setTarget(PlaylistQueueRenderer.RatingTarget.newBuilder().setVideoId(videoId))
                        .setLikeStatusEntityKey(entityKey)
                        .setButton(PlaylistQueueRenderer.RatingToggleContainer.newBuilder().setToggleButtonRenderer(button)))
                .build();
    }

    private static PlaylistQueueRenderer.Accessibility accessibility(String label) {
        return PlaylistQueueRenderer.Accessibility.newBuilder()
                .setAccessibilityData(PlaylistQueueRenderer.AccessibilityLabel.newBuilder().setLabel(label)).build();
    }
}
