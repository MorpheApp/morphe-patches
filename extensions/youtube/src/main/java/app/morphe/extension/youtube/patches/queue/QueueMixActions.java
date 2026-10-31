/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.ConfirmationDialog;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.ConfirmationDialogRenderer;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.ShowConfirmationDialogEndpoint;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.SwipeAction;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.SwipeButton;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.Text;

/** Native Mix confirmation; each button retains the original watch command and a local capability. */
final class QueueMixActions {
    private QueueMixActions() {}

    static NavigationEndpoint dialog(NavigationEndpoint original, String token,
                                     String title, String watchLabel, String addLabel) {
        var watch = original.toBuilder().setQueueMixChoiceToken(token).setQueueMixAdd(false);
        var add = original.toBuilder().setQueueMixChoiceToken(token).setQueueMixAdd(true);
        return NavigationEndpoint.newBuilder().setShowConfirmationDialogEndpoint(
                ShowConfirmationDialogEndpoint.newBuilder().setDialog(ConfirmationDialog.newBuilder()
                        .setConfirmDialogRenderer(ConfirmationDialogRenderer.newBuilder()
                                .setTitle(Text.newBuilder().setSimpleText(title))
                                .setCancelButton(button(watch.build(), watchLabel))
                                .setConfirmButton(button(add.build(), addLabel))))).build();
    }

    private static SwipeAction button(NavigationEndpoint endpoint, String label) {
        return SwipeAction.newBuilder().setButtonRenderer(SwipeButton.newBuilder()
                .setText(Text.newBuilder().setSimpleText(label)).setServiceEndpoint(endpoint)).build();
    }
}
