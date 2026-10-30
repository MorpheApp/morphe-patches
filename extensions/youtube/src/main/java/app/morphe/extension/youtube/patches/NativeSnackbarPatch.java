/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4148
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
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

    /**
     * Interface to use the obfuscated command router of an activity.
     */
    public interface CommandRouter {
        // Method is added during patching.
        void patch_executeCommand(byte[] command);
    }

    /**
     * Each activity has its own router, which can only show a snackbar in that activity.
     * The routers hold their activity, so they are not kept strongly by the key.
     */
    private static final Map<Activity, WeakReference<CommandRouter>> routers = new WeakHashMap<>();

    private static final Utils.SnackbarPresenter PRESENTER = (message, longDuration) -> {
        // A hidden YouTube snackbar would hide the message as well.
        if (!Settings.NATIVE_SNACKBAR.get() || Settings.HIDE_SNACKBAR.get()) {
            return false;
        }

        for (Map.Entry<Activity, WeakReference<CommandRouter>> entry : routers.entrySet()) {
            Activity activity = entry.getKey();
            CommandRouter router = entry.getValue().get();
            // Stopped behind another screen, such as the Morphe settings, or in the background,
            // where nobody would see the snackbar.
            if (router == null || activity == null || activity.isFinishing()
                    || !activity.getWindow().getDecorView().isShown()) {
                continue;
            }

            // The app has no duration for it, YouTube shows every snackbar for the same time.
            router.patch_executeCommand(NavigationEndpoint.newBuilder()
                    .setAddToToastAction(AddToToastAction.newBuilder()
                            .setItem(QueueToastItem.newBuilder()
                                    .setToastRenderer(QueueToastRenderer.newBuilder()
                                            .setResponseText(Text.newBuilder().setSimpleText(message)))))
                    .build()
                    .toByteArray());
            return true;
        }

        return false;
    };

    /**
     * Injection point.
     * <p>
     * Called when an activity creates its command router.
     */
    public static void setCommandRouter(CommandRouter router, Activity activity) {
        try {
            Utils.runOnMainThreadNowOrLater(() -> {
                routers.put(activity, new WeakReference<>(router));
                Utils.setSnackbarPresenter(PRESENTER);
            });
        } catch (Exception ex) {
            Logger.printException(() -> "setCommandRouter failure", ex);
        }
    }
}
