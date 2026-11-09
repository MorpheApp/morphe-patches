/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4324
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.graphics.Rect;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;

import java.lang.ref.WeakReference;
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
public final class PlaylistPatch {

    /**
     * Added to the playlist edit callback of the app during patching.
     */
    public interface PlaylistEditCallback {
        /**
         * Parses a playlist edit response with the protobuf class of the app.
         */
        MessageLite patch_parsePlaylistEditResponse(byte[] bytes);
    }

    /**
     * Prefix of the browse id of a playlist page, followed by the playlist id.
     */
    private static final String PLAYLIST_BROWSE_ID_PREFIX = "VL";

    /**
     * Width of a widened drag handle and of its touch area.
     */
    private static final int DRAG_HANDLE_WIDTH_DIP = 32;

    /**
     * Width of the menu button at the end of a row.
     */
    private static final int MENU_BUTTON_WIDTH_DIP = 48;

    private static final int DRAG_HANDLE_CONTAINER_ID = ResourceUtils.getIdentifier(
            ResourceType.ID, "drag_handle_container");

    private static final int DRAG_HANDLE_DESCRIPTION_ID = ResourceUtils.getIdentifier(
            ResourceType.STRING, "accessibility_drag_handle");

    private static volatile boolean playlistShown;

    /**
     * Long press of the row that is touched now. Used only on the main thread.
     */
    @Nullable
    private static RowLongPress rowLongPress;

    /**
     * If a cancel event is given to the children of a row, which the touch hook must ignore.
     */
    private static boolean dispatchingCancel;

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
        playlistShown = browseId != null && browseId.startsWith(PLAYLIST_BROWSE_ID_PREFIX)
                && browseId.length() > PLAYLIST_BROWSE_ID_PREFIX.length();
    }

    /**
     * Injection point.
     */
    public static void clearBrowseId() {
        playlistShown = false;
    }

    private static boolean isPlaylistShown() {
        return playlistShown && !PlayerType.getCurrent().isMaximizedOrFullscreen();
    }

    private static boolean isWideDragHandlesActive() {
        return Settings.PLAYLIST_WIDE_DRAG_HANDLES.get() && isPlaylistShown();
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
            if (!Settings.PLAYLIST_WIDE_DRAG_HANDLES.get()) return;

            if (isPlaylistShown()) {
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
            return Settings.PLAYLIST_WIDE_DRAG_HANDLES.get()
                    && event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && host.getTouchDelegate() instanceof DragHandleTouchDelegate delegate
                    && isPlaylistShown()
                    && delegate.area.contains((int) event.getX(), (int) event.getY());
        } catch (Exception ex) {
            Logger.printException(() -> "onComponentHostInterceptTouch failure", ex);
            return false;
        }
    }

    /**
     * Injection point.
     * Called by ComponentHost.dispatchTouchEvent(). Touching and holding a row of an editable
     * playlist drags it with its drag handle, so the new order is saved like a drag of the handle.
     *
     * @return If the touch was handled and must not be given to the children.
     */
    public static boolean onComponentHostTouch(ComponentHost host, MotionEvent event) {
        if (dispatchingCancel || !Settings.PLAYLIST_LONG_PRESS_DRAG.get()) return false;
        try {
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                onRowTouchDown(host, event);
            }

            RowLongPress longPress = rowLongPress;
            if (longPress == null || longPress.hostRef.get() != host) return false;

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                rowLongPress = null;
            }
            if (longPress.dragging) {
                longPress.forwardToHandle(event);
                return true;
            }
            longPress.detector.onTouchEvent(event);
            return false;
        } catch (Exception ex) {
            rowLongPress = null;
            Logger.printException(() -> "onComponentHostTouch failure", ex);
            return false;
        }
    }

    private static void onRowTouchDown(ComponentHost host, MotionEvent event) {
        // A row that was recycled during a touch may not have seen the end of that touch.
        if (rowLongPress != null && rowLongPress.hostRef.get() == host) {
            rowLongPress = null;
        }
        if (!isPlaylistShown()) return;

        View handle = findDragHandle(host);
        if (handle == null) return;

        // The drag handle and the menu button keep their own touches.
        final int hostWidth = host.getWidth();
        final float x = host.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? hostWidth - event.getX()
                : event.getX();
        if (x < dipToPixels(host, DRAG_HANDLE_WIDTH_DIP)
                || x > hostWidth - dipToPixels(host, MENU_BUTTON_WIDTH_DIP)) {
            return;
        }

        rowLongPress = new RowLongPress(host, handle);
    }

    /**
     * Long press of a row, which then gives the touch to the drag handle of the row.
     * Does not keep the views of the row, which can be recycled during the touch.
     */
    private static final class RowLongPress extends GestureDetector.SimpleOnGestureListener {
        private final WeakReference<ComponentHost> hostRef;
        private final WeakReference<View> handleRef;
        private final GestureDetector detector;
        private final int[] downLocation = new int[2];
        private final int[] currentLocation = new int[2];
        private boolean dragging;

        RowLongPress(ComponentHost host, View handle) {
            hostRef = new WeakReference<>(host);
            handleRef = new WeakReference<>(handle);
            host.getLocationOnScreen(downLocation);
            detector = new GestureDetector(host.getContext().getApplicationContext(), this);
        }

        @Override
        public void onLongPress(@NonNull MotionEvent event) {
            try {
                ComponentHost host = hostRef.get();
                View handle = handleRef.get();
                if (rowLongPress != this || host == null || handle == null
                        || !host.isAttachedToWindow() || !isPlaylistShown()
                        || findDragHandle(host) != handle) {
                    return;
                }
                // The list can still move without new touch events, such as after a fling.
                host.getLocationOnScreen(currentLocation);
                if (currentLocation[0] != downLocation[0] || currentLocation[1] != downLocation[1]) {
                    return;
                }

                cancelChildrenTouch(host, event);
                host.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);

                // The rest of the touch goes to the handle, even if the handle
                // does not use the down event, so lifting the finger stops the drag.
                dragging = true;
                MotionEvent down = MotionEvent.obtain(event);
                try {
                    down.setAction(MotionEvent.ACTION_DOWN);
                    dispatchToHandleCenter(handle, down);
                } finally {
                    down.recycle();
                }
            } catch (Exception ex) {
                Logger.printException(() -> "onLongPress failure", ex);
            }
        }

        /**
         * Removes the pressed state of the children, and their own pending clicks.
         */
        private static void cancelChildrenTouch(ComponentHost host, MotionEvent event) {
            MotionEvent cancel = MotionEvent.obtain(event);
            dispatchingCancel = true;
            try {
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                host.dispatchTouchEvent(cancel);
            } finally {
                dispatchingCancel = false;
                cancel.recycle();
            }
        }

        void forwardToHandle(MotionEvent event) {
            View handle = handleRef.get();
            if (handle != null) {
                dispatchToHandleCenter(handle, event);
            }
        }

        /**
         * The handle starts the drag a moment after the touch starts, unless the touch moves
         * or ends first. The drag itself follows the finger in the list, so the handle always
         * gets the touch at its center.
         */
        private static void dispatchToHandleCenter(View handle, MotionEvent event) {
            MotionEvent copy = MotionEvent.obtain(event);
            try {
                copy.setLocation(handle.getWidth() / 2f, handle.getHeight() / 2f);
                handle.dispatchTouchEvent(copy);
            } finally {
                copy.recycle();
            }
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
        if (!Settings.PLAYLIST_HIDE_REMOVAL_SNACKBAR.get()) return response;

        try {
            if (!RemovalSnackbar.isPlaylistRemoval(playlistId, actions)) return response;

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
         * @return If all actions of the edit remove videos from a playlist.
         */
        static boolean isPlaylistRemoval(@Nullable String playlistId, List<? extends MessageLite> actions)
                throws InvalidProtocolBufferException {
            if (playlistId == null || playlistId.isEmpty() || actions.isEmpty()) return false;

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
