/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4148
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Queue;

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
 * command a server response uses to show one. At most one message is shown each second:
 * a message is shown at once if it is its turn, or else it waits for its turn.
 */
@SuppressWarnings("unused")
public final class NativeSnackbarPatch {

    private static final long MESSAGE_INTERVAL_MS = 5000;

    private record QueuedMessage(String message, boolean longDuration) {
    }

    /** Messages that wait for their turn, oldest first. Used only on the main thread. */
    private static final Queue<QueuedMessage> queuedMessages = new ArrayDeque<>();

    /** When the next message can be shown. Used only on the main thread. */
    private static long nextMessageTime;

    /** Always called on the main thread. */
    private static final Utils.SnackbarPresenter PRESENTER = (message, longDuration) -> {
        // A hidden YouTube snackbar would hide the message as well.
        if (!Settings.NATIVE_SNACKBAR.get() || Settings.HIDE_SNACKBAR.get()) {
            return false;
        }

        final long now = SystemClock.uptimeMillis();
        if (now < nextMessageTime || !queuedMessages.isEmpty()) {
            queuedMessages.add(new QueuedMessage(message, longDuration));
            if (queuedMessages.size() == 1) {
                Utils.runOnMainThreadDelayed(NativeSnackbarPatch::showNextMessage, nextMessageTime - now);
            }
            return true;
        }

        final boolean shown = showMessage(message);
        if (shown) {
            nextMessageTime = now + MESSAGE_INTERVAL_MS;
        }
        return shown;
    };

    private static boolean showMessage(String message) {
        // The app has no duration for it, YouTube shows every snackbar for the same time.
        return CommandRouterPatch.executeCommand(NavigationEndpoint.newBuilder()
                .setAddToToastAction(AddToToastAction.newBuilder()
                        .setItem(QueueToastItem.newBuilder()
                                .setToastRenderer(QueueToastRenderer.newBuilder()
                                        .setResponseText(Text.newBuilder().setSimpleText(message)))))
                .build()
                .toByteArray(), new HashMap<>());
    }

    private static void showNextMessage() {
        QueuedMessage next = queuedMessages.remove();
        nextMessageTime = SystemClock.uptimeMillis() + MESSAGE_INTERVAL_MS;
        if (!queuedMessages.isEmpty()) {
            Utils.runOnMainThreadDelayed(NativeSnackbarPatch::showNextMessage, MESSAGE_INTERVAL_MS);
        }

        boolean shown = false;
        try {
            shown = showMessage(next.message);
        } catch (Exception ex) {
            Logger.printException(() -> "showNextMessage failure", ex);
        }
        if (!shown) {
            // The app can no longer show it, such as while it is in the background.
            Utils.showToast(next.message, next.longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        }
    }

    /** Position of the snackbar container in its window. Used only on the main thread. */
    private static final int[] containerLocation = new int[2];

    /**
     * Injection point.
     * <p>
     * Called after the container of the snackbars has laid them out. YouTube gives it the bottom
     * window inset as bottom padding also when it ends above the navigation bar or the miniplayer,
     * which already keep it above the system navigation buttons, so the snackbars are shown that much
     * above them (<a href="https://github.com/MorpheApp/morphe-patches/issues/4308">...</a>).
     * Only the part of the padding the container really needs, where it overlaps the inset, is kept.
     */
    public static void removeExtraBottomRoom(FrameLayout container) {
        try {
            final int paddingBottom = container.getPaddingBottom();
            if (paddingBottom <= 0) {
                return;
            }

            container.getLocationInWindow(containerLocation);
            final int roomBelow = container.getRootView().getHeight()
                    - containerLocation[1] - container.getHeight();
            final int offset = Math.min(paddingBottom, roomBelow);
            if (offset <= 0) {
                return;
            }

            // The moved snackbars are drawn over the padding.
            container.setClipToPadding(false);
            for (int i = 0, count = container.getChildCount(); i < count; i++) {
                View child = container.getChildAt(i);
                if (child.getLayoutParams() instanceof FrameLayout.LayoutParams params
                        && (params.gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM) {
                    child.offsetTopAndBottom(offset);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "removeExtraBottomRoom failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * Called when the main activity is created.
     */
    public static void initialize() {
        Utils.setSnackbarPresenter(PRESENTER);
    }
}
