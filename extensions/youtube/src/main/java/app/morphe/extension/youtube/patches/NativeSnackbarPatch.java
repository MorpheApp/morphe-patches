/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4148
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import java.util.HashMap;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.AddToToastAction;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueToastItem;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueToastRenderer;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.Text;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Shows the messages of {@link Utils#showSnackbar} in the YouTube snackbar, by running the same
 * command a server response uses to show one.
 */
@SuppressWarnings("unused")
public final class NativeSnackbarPatch {

    private static final Utils.SnackbarPresenter PRESENTER = (message, longDuration) -> {
        // A hidden YouTube snackbar would hide the message as well.
        if (!Settings.NATIVE_SNACKBAR.get() || Settings.HIDE_SNACKBAR.get()) {
            return false;
        }

        // The app has no duration for it, YouTube shows every snackbar for the same time.
        return CommandRouterPatch.executeCommand(NavigationEndpoint.newBuilder()
                .setAddToToastAction(AddToToastAction.newBuilder()
                        .setItem(QueueToastItem.newBuilder()
                                .setToastRenderer(QueueToastRenderer.newBuilder()
                                        .setResponseText(Text.newBuilder().setSimpleText(message)))))
                .build()
                .toByteArray(), new HashMap<>());
    };

    /**
     * Injection point.
     * <p>
     * Called when the main activity is created.
     */
    public static void initialize() {
        Utils.setSnackbarPresenter(PRESENTER);
    }
}
