/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4324
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.graphics.Rect;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

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

    // Used only on the UI thread. Neither the detectors nor their callbacks retain a row.
    private static final Map<ComponentHost, RowLongPress> longPresses = new WeakHashMap<>();
    private static boolean dispatchingCancel;

    /**
     * Width of a widened drag handle and of its touch area.
     */
    private static final int DRAG_HANDLE_WIDTH_DIP = 32;

    private static final int DRAG_HANDLE_CONTAINER_ID = ResourceUtils.getIdentifier(
            ResourceType.ID, "drag_handle_container");

    private static final int DRAG_HANDLE_DESCRIPTION_ID = ResourceUtils.getIdentifier(
            ResourceType.STRING, "accessibility_drag_handle");

    private static volatile boolean playlistShown;

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
        playlistShown = browseId != null && browseId.startsWith("VL") && browseId.length() > 2;
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
        if (DRAG_HANDLE_DESCRIPTION_ID == 0) return null;
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
            if (isWideDragHandlesActive()) {
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

    /** Observe row touches without stealing taps or scrolls from the native children. */
    public static boolean onComponentHostTouch(ComponentHost host, MotionEvent event) {
        if (dispatchingCancel) return false;
        try {
            RowLongPress gesture = longPresses.get(host);
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                longPresses.remove(host);
                if (!Settings.PLAYLIST_LONG_PRESS_DRAG.get() || !isPlaylistShown()) return false;
                View handle = findDragHandle(host);
                if (handle == null) return false;
                // Leave the native handle and action menu to their own listeners.
                float x = host.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                        ? host.getWidth() - event.getX() : event.getX();
                if (x < dipToPixels(host, DRAG_HANDLE_WIDTH_DIP)
                        || x > host.getWidth() - dipToPixels(host, 48)) return false;
                gesture = new RowLongPress(host, handle);
                longPresses.put(host, gesture);
            }
            if (gesture == null) return false;
            boolean consumed = gesture.dragging;
            if (consumed) gesture.forwardTouch(event);
            gesture.detector.onTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                longPresses.remove(host);
            }
            return consumed || gesture.dragging;
        } catch (Exception ex) {
            longPresses.remove(host);
            Logger.printException(() -> "onComponentHostTouch failure", ex);
            return false;
        }
    }

    private static final class RowLongPress extends GestureDetector.SimpleOnGestureListener {
        final WeakReference<ComponentHost> hostRef;
        final WeakReference<View> handleRef;
        final GestureDetector detector;
        final int[] location = new int[2];
        boolean dragging;

        RowLongPress(ComponentHost host, View handle) {
            hostRef = new WeakReference<>(host);
            handleRef = new WeakReference<>(handle);
            host.getLocationOnScreen(location);
            detector = new GestureDetector(host.getContext().getApplicationContext(), this);
        }

        @Override
        public boolean onDown(MotionEvent event) {
            return true;
        }

        @Override
        public void onLongPress(MotionEvent event) {
            ComponentHost host = hostRef.get();
            View handle = handleRef.get();
            if (host == null || handle == null || !host.isAttachedToWindow()
                    || !Settings.PLAYLIST_LONG_PRESS_DRAG.get() || !isPlaylistShown()
                    || longPresses.get(host) != this || findDragHandle(host) != handle) return;
            int[] current = new int[2];
            host.getLocationOnScreen(current);
            if (current[0] != location[0] || current[1] != location[1]) return;
            MotionEvent copy = MotionEvent.obtain(event);
            try {
                // Cancel the original row tap before starting the native handle's saved drag.
                dispatchingCancel = true;
                copy.setAction(MotionEvent.ACTION_CANCEL);
                host.dispatchTouchEvent(copy);
                dispatchingCancel = false;

                copy.setLocation(handle.getWidth() / 2f, handle.getHeight() / 2f);
                copy.setAction(MotionEvent.ACTION_DOWN);
                // Use the handle's actual command, which installs the native drop/save callbacks.
                dragging = handle.dispatchTouchEvent(copy);
            } catch (Exception ex) {
                dragging = false;
                Logger.printException(() -> "Could not start native playlist drag", ex);
            } finally {
                dispatchingCancel = false;
                copy.recycle();
            }
        }

        void forwardTouch(MotionEvent event) {
            View handle = handleRef.get();
            if (handle == null) return;
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
