/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

import app.morphe.extension.youtube.innertube.NextResponseOuterClass.ActionButtons;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.Commands;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.ComponentType;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.DislikeButtonViewModel;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.LikeButtonViewModel;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.Model;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.NewElement;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.PrimaryButtonViewModel;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.RatingButtonContainer;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.RatingDislikeContainer;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.RatingLikeContainer;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.RatingStatusEntity;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.RatingToggleContainer;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.RatingToggleViewModel;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.SecondaryButtonViewModel;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.SegmentedLikeDislikeButtonViewModel;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.SerialCommands;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.Type;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.VideoActionBarData;
import app.morphe.extension.youtube.innertube.NextResponseOuterClass.VideoActionBarModel;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.LikeEndpoint.Status;

public class QueueRatingActionsTest {
    private static ByteString endpoint(String id, Status status) throws Exception {
        var target = PlaylistQueueRenderer.RatingTarget.newBuilder().setVideoId(id);
        var body = new ByteArrayOutputStream();
        body.write(PlaylistQueueRenderer.LikeEndpoint.newBuilder().setStatus(status).setTarget(target).build().toByteArray());
        var parameters = CodedOutputStream.newInstance(body);
        parameters.writeString(6, "opaque server parameters");
        parameters.flush();
        var result = new ByteArrayOutputStream();
        var command = CodedOutputStream.newInstance(result);
        command.writeBytes(63158558, ByteString.copyFrom(body.toByteArray()));
        command.flush();
        return ByteString.copyFrom(result.toByteArray());
    }
    private static RatingButtonContainer button(ByteString endpoint) {
        return RatingButtonContainer.newBuilder().setButtonViewModel(SecondaryButtonViewModel.newBuilder()
            .setOnTap(Commands.newBuilder().setSerialCommand(SerialCommands.newBuilder()
                .addCommands(Commands.getDefaultInstance())
                .addCommands(Commands.newBuilder().setInnertubeCommand(endpoint)))))
            .build();
    }
    private static String[] parse(String dislikeId, boolean omitUndo, int initialStatus) throws Exception {
        String id = "abcdefghijk";
        var likeEndpoint = endpoint(id, Status.LIKE);
        var like = LikeButtonViewModel.newBuilder().setLikeStatusEntityKey("native entity key")
            .setLikeStatusEntity(RatingStatusEntity.newBuilder().setLikeStatus(initialStatus))
            .setToggleButtonViewModel(RatingToggleContainer.newBuilder().setToggleButtonViewModel(
                RatingToggleViewModel.newBuilder().setDefaultButtonViewModel(button(likeEndpoint))
                    .setToggledButtonViewModel(button(endpoint(id, Status.INDIFFERENT)))));
        var dislike = DislikeButtonViewModel.newBuilder().setDislikeEntityKey("native entity key")
            .setToggleButtonViewModel(RatingToggleContainer.newBuilder().setToggleButtonViewModel(
                RatingToggleViewModel.newBuilder().setDefaultButtonViewModel(button(endpoint(dislikeId, Status.DISLIKE)))
                    .setToggledButtonViewModel(button(omitUndo ? ByteString.EMPTY : endpoint(id, Status.INDIFFERENT)))));
        var segmented = SegmentedLikeDislikeButtonViewModel.newBuilder()
            .setLikeButtonViewModel(RatingLikeContainer.newBuilder().setLikeButtonViewModel(like))
            .setDislikeButtonViewModel(RatingDislikeContainer.newBuilder().setDislikeButtonViewModel(dislike));
        var element = NewElement.newBuilder().setType(Type.newBuilder().setComponentType(ComponentType.newBuilder()
            .setModel(Model.newBuilder().setVideoActionBarModel(VideoActionBarModel.newBuilder()
                .setVideoActionBarData(VideoActionBarData.newBuilder().addActionButtons(ActionButtons.newBuilder()
                    .setPrimaryButtonViewModel(PrimaryButtonViewModel.newBuilder().setSegmentedLikeDislikeButtonViewModel(segmented))))))))
            .build();
        var actions = QueueRatingActions.fromElement(NewElement.parseFrom(element.toByteArray()));
        if (actions == null) return null;
        var buttons = actions.buttons("Like", "Dislike");
        var nativeLike = buttons.get(0).getSlimMetadataToggleButton().getButton().getToggleButtonRenderer();
        var nativeDislike = buttons.get(1).getSlimMetadataToggleButton().getButton().getToggleButtonRenderer();
        return new String[]{actions.videoId(), actions.entityKey(),
            Boolean.toString(likeEndpoint.equals(nativeLike.getDefaultServiceEndpoint())),
            Boolean.toString(nativeLike.getIsToggled()), Boolean.toString(nativeDislike.getIsToggled()),
            nativeLike.getAccessibilityData().getAccessibilityData().getLabel()};
    }
    @Test public void ratingsPreserveOpaqueCommandsAndNativeEntityKey() throws Exception {
        assertArrayEquals(new String[]{"abcdefghijk", "native entity key", "true", "false", "true", "Like"},
                parse("abcdefghijk", false, 1));
    }

    @Test public void likedStatusIsReflectedInButtons() throws Exception {
        assertArrayEquals(new String[]{"abcdefghijk", "native entity key", "true", "true", "false", "Like"},
                parse("abcdefghijk", false, 0));
    }

    @Test public void mismatchedTargetsAreRejected() throws Exception {
        assertNull(parse("lmnopqrstuv", false, 1));
    }

    @Test public void missingUndoCommandIsRejected() throws Exception {
        assertNull(parse("abcdefghijk", true, 1));
    }
}
