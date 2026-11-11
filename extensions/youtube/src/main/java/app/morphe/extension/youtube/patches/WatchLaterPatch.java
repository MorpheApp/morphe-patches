/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4324
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.youtube.patches.SaveToWatchLaterPatch.WATCH_LATER_PLAYLIST_ID;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.youtube.innertube.PlaylistEdit.PlaylistEditAction;
import app.morphe.extension.youtube.innertube.PlaylistEdit.PlaylistEditActionType;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSaveEditResponse;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public final class WatchLaterPatch {

    /**
     * Added to the playlist edit callback of the app during patching.
     */
    public interface PlaylistEditCallback {
        /**
         * Parses a playlist edit response with the protobuf class of the app.
         */
        MessageLite patch_parsePlaylistEditResponse(byte[] bytes);
    }

    private static final String WATCH_LATER_BROWSE_ID = "VLWL";

    /**
     * Width of a widened drag handle and of its touch area.
     */
    private static final int DRAG_HANDLE_WIDTH_DIP = 32;

    private static final int DRAG_HANDLE_CONTAINER_ID = ResourceUtils.getIdentifier(
            ResourceType.ID, "drag_handle_container");

    private static final int DRAG_HANDLE_DESCRIPTION_ID = ResourceUtils.getIdentifier(
            ResourceType.STRING, "accessibility_drag_handle");

    private static volatile boolean watchLaterShown;

    /**
     * Touch delegate of a row host that gives the touches of the drag handle area to the handle.
     */
    private static final class DragHandleTouchDelegate extends TouchDelegate {
        final Rect area;

        DragHandleTouchDelegate(Rect area, View handle) {
            super(area, handle);
            this.area = area;
        }
    }

    /**
     * Injection point.
     */
    public static void setBrowseId(@Nullable String browseId) {
        watchLaterShown = WATCH_LATER_BROWSE_ID.equals(browseId);
    }

    /**
     * Injection point.
     */
    public static void clearBrowseId() {
        watchLaterShown = false;
    }

    private static boolean isWatchLaterShown() {
        return watchLaterShown && !PlayerType.getCurrent().isMaximizedOrFullscreen();
    }

    private static boolean isWideDragHandlesActive() {
        return Settings.WATCH_LATER_WIDE_DRAG_HANDLES.get() && isWatchLaterShown();
    }

    private static int dipToPixels(View view, int dip) {
        return Math.round(dip * view.getResources().getDisplayMetrics().density);
    }

    /**
     * @return The drag handle among the children of the row host.
     */
    @Nullable
    private static View findDragHandle(ViewGroup host) {
        String description = host.getResources().getString(DRAG_HANDLE_DESCRIPTION_ID);
        for (int i = 0, count = host.getChildCount(); i < count; i++) {
            View child = host.getChildAt(i);
            CharSequence childDescription = child.getContentDescription();
            if (childDescription != null && description.contentEquals(childDescription)) return child;
        }
        return null;
    }

    /**
     * Injection point.
     * Called when the touch listener of the drag handle of a playlist row is created.
     */
    public static void onDragHandleCreated(View handle) {
        try {
            if (!isWideDragHandlesActive()) return;

            final int width = dipToPixels(handle, DRAG_HANDLE_WIDTH_DIP);
            if (handle.getParent() instanceof View container && container.getId() == DRAG_HANDLE_CONTAINER_ID) {
                if (widen(container, width)) {
                    container.post(() -> container.setTouchDelegate(new TouchDelegate(
                            new Rect(0, 0, container.getWidth(), container.getHeight()), handle)));
                }
            } else {
                widen(handle, width);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onDragHandleCreated failure", ex);
        }
    }

    private static boolean widen(View view, int width) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null || params.width <= 0 || params.width >= width) return false;

        params.width = width;
        view.setLayoutParams(params);
        return true;
    }

    /**
     * Injection point.
     * Called when a Litho host is laid out. The narrow drag handle of a Litho row gets
     * a wider touch area, without changing the size of the thumbnail or the text.
     */
    public static void onComponentHostLayout(ComponentHost host) {
        try {
            if (!Settings.WATCH_LATER_WIDE_DRAG_HANDLES.get()) return;

            if (isWatchLaterShown()) {
                // Litho mounts the views of the host after it is laid out.
                host.post(() -> updateDragHandleTouchDelegate(host));
            } else {
                removeDragHandleTouchDelegate(host);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onComponentHostLayout failure", ex);
        }
    }

    private static void updateDragHandleTouchDelegate(ComponentHost host) {
        try {
            View handle = isWideDragHandlesActive() ? findDragHandle(host) : null;
            if (handle == null) {
                removeDragHandleTouchDelegate(host);
                return;
            }

            final int width = dipToPixels(host, DRAG_HANDLE_WIDTH_DIP);
            final int hostWidth = host.getWidth();
            final int hostHeight = host.getHeight();
            Rect area = host.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                    ? new Rect(hostWidth - width, 0, hostWidth, hostHeight)
                    : new Rect(0, 0, width, hostHeight);
            host.setTouchDelegate(new DragHandleTouchDelegate(area, handle));
        } catch (Exception ex) {
            Logger.printException(() -> "updateDragHandleTouchDelegate failure", ex);
        }
    }

    private static void removeDragHandleTouchDelegate(ComponentHost host) {
        if (host.getTouchDelegate() instanceof DragHandleTouchDelegate) {
            host.setTouchDelegate(null);
        }
    }

    /**
     * Injection point.
     * Called by ComponentHost.onInterceptTouchEvent(). A touch that starts in the drag handle area
     * is not given to the children, so the touch delegate of the host gives it to the handle.
     */
    public static boolean onComponentHostInterceptTouch(ComponentHost host, MotionEvent event) {
        try {
            return Settings.WATCH_LATER_WIDE_DRAG_HANDLES.get()
                    && event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && host.getTouchDelegate() instanceof DragHandleTouchDelegate delegate
                    && isWatchLaterShown()
                    && delegate.area.contains((int) event.getX(), (int) event.getY());
        } catch (Exception ex) {
            Logger.printException(() -> "onComponentHostInterceptTouch failure", ex);
            return false;
        }
    }

    /**
     * Injection point.
     * Called with the successful response of a playlist edit, before the app runs its actions.
     *
     * @param playlistId Playlist of the edit.
     * @param actions    Edit actions of the request.
     */
    public static MessageLite onPlaylistEditResponse(PlaylistEditCallback callback,
                                                     @Nullable String playlistId,
                                                     List<? extends MessageLite> actions,
                                                     MessageLite response) {
        if (!Settings.WATCH_LATER_HIDE_REMOVAL_SNACKBAR.get()) return response;

        try {
            if (!RemovalSnackbar.isWatchLaterRemoval(playlistId, actions)) return response;

            var result = QueueSaveEditResponse.parseFrom(response.toByteArray());
            var filtered = RemovalSnackbar.removeToasts(result);
            return filtered == result
                    ? response
                    : callback.patch_parsePlaylistEditResponse(filtered.toByteArray());
        } catch (Exception ex) {
            // The edit already completed, so the original response is always kept.
            Logger.printException(() -> "onPlaylistEditResponse failure", ex);
            return response;
        }
    }

    /**
     * Protobuf logic of the removal snackbar, without Android dependencies.
     */
    static final class RemovalSnackbar {
        /**
         * Status of a playlist edit response that succeeded.
         */
        private static final int STATUS_SUCCEEDED = 1;

        /**
         * @return If all actions of the edit remove videos from Watch later.
         */
        static boolean isWatchLaterRemoval(@Nullable String playlistId, List<? extends MessageLite> actions)
                throws InvalidProtocolBufferException {
            if (!WATCH_LATER_PLAYLIST_ID.equals(playlistId) || actions.isEmpty()) return false;

            for (MessageLite appAction : actions) {
                var action = PlaylistEditAction.parseFrom(appAction.toByteArray()).getAction();
                if (action != PlaylistEditActionType.ACTION_REMOVE_VIDEO
                        && action != PlaylistEditActionType.ACTION_REMOVE_VIDEO_BY_VIDEO_ID) {
                    return false;
                }
            }
            return true;
        }

        /**
         * @return The response without its toast actions, or the same instance if nothing is removed.
         */
        static QueueSaveEditResponse removeToasts(QueueSaveEditResponse response) {
            if (response.getStatus() != STATUS_SUCCEEDED) return response;

            QueueSaveEditResponse.Builder builder = null;
            for (int i = 0, count = response.getActionsCount(); i < count; i++) {
                var action = response.getActions(i);
                if (action.hasAddToToastAction()) {
                    if (builder == null) builder = response.toBuilder();
                    builder.setActions(i, action.toBuilder().clearAddToToastAction());
                }
            }
            return builder == null ? response : builder.build();
        }
    }
}
