/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.graphics.Rect;
import com.facebook.litho.ComponentHost;

import com.google.protobuf.MessageLite;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.youtube.innertube.PlaylistEdit.PlaylistEditAction;
import app.morphe.extension.youtube.innertube.PlaylistEdit.PlaylistEditActionType;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSaveEditResponse;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public final class WatchLaterPatch {
    private static final int DRAG_HANDLE_WIDTH_DP = 32;
    private static volatile boolean watchLaterVisible;
    private static final Map<Object, Boolean> removals = Collections.synchronizedMap(new WeakHashMap<>());

    public interface Callback {
        MessageLite patch_parseWatchLaterResult(byte[] bytes);
    }

    public static void setBrowseId(String browseId) {
        watchLaterVisible = "VLWL".equals(browseId);
    }

    public static void clearBrowseId() {
        watchLaterVisible = false;
    }

    public static boolean isWatchLaterActive() {
        return watchLaterVisible && !PlayerType.getCurrent().isMaximizedOrFullscreen();
    }

    private static boolean hasDropCallback(View view) {
        int id = ResourceUtils.getIdentifier(ResourceType.ID, "elements_item_dropped_sender_state_consumer");
        if (id == 0) return false;
        for (int depth = 0; view != null && depth < 4; depth++) {
            if (view.getTag(id) != null) return true;
            view = view.getParent() instanceof View parent ? parent : null;
        }
        return false;
    }

    private static View findDragHandle(View view, int remainingDepth) {
        int id = ResourceUtils.getIdentifier(ResourceType.STRING, "accessibility_drag_handle");
        return id == 0 ? null : findDragHandle(view, view.getResources().getString(id), remainingDepth);
    }

    private static View findDragHandle(View view, String description, int remainingDepth) {
        if (remainingDepth < 0) return null;
        CharSequence text = view.getContentDescription();
        if (text != null && description.contentEquals(text)) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View handle = findDragHandle(group.getChildAt(i), description, remainingDepth - 1);
                if (handle != null) return handle;
            }
        }
        return null;
    }

    public static boolean allowElementDrag(View view) {
        return !isWatchLaterActive() || hasDropCallback(view) || findDragHandle(view, 4) != null;
    }

    private static final class HandleTouchDelegate extends TouchDelegate {
        final Rect area;

        HandleTouchDelegate(Rect area, View handle) {
            super(area, handle);
            this.area = area;
        }
    }

    /** Expand the leading 16dp handle to a 32dp touch target without stretching thumbnails or text. */
    public static void onHostLayout(ComponentHost host) {
        if (!isWatchLaterActive()) {
            if (host.getTouchDelegate() instanceof HandleTouchDelegate) host.setTouchDelegate(null);
            return;
        }
        // Litho mounts the ImageViews after laying out their host.
        host.post(() -> updateHost(host));
    }

    private static void updateHost(ComponentHost host) {
        if (isWatchLaterActive()) {
            View handle = findDragHandle(host, 1);
            if (handle != null) {
                int width = Math.round(DRAG_HANDLE_WIDTH_DP * host.getResources().getDisplayMetrics().density);
                boolean rtl = host.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                Rect area = new Rect(rtl ? host.getWidth() - width : 0, 0,
                        rtl ? host.getWidth() : width, host.getHeight());
                host.setTouchDelegate(new HandleTouchDelegate(area, handle));
                return;
            }
        }
        if (host.getTouchDelegate() instanceof HandleTouchDelegate) host.setTouchDelegate(null);
    }

    public static boolean interceptHandleTouch(ComponentHost host, MotionEvent event) {
        return isWatchLaterActive() && event.getActionMasked() == MotionEvent.ACTION_DOWN
                && host.getTouchDelegate() instanceof HandleTouchDelegate delegate
                && delegate.area.contains((int) event.getX(), (int) event.getY());
    }

    public static void widenDragHandle(View handle) {
        if (!isWatchLaterActive()) return;
        int width = Math.round(DRAG_HANDLE_WIDTH_DP * handle.getResources().getDisplayMetrics().density);
        int containerId = ResourceUtils.getIdentifier(ResourceType.ID, "drag_handle_container");
        if (containerId != 0 && handle.getParent() instanceof View container && container.getId() == containerId) {
            ViewGroup.LayoutParams params = container.getLayoutParams();
            if (params != null && params.width > 0 && params.width < width) {
                params.width = width;
                container.setLayoutParams(params);
                container.post(() -> container.setTouchDelegate(new TouchDelegate(
                        new Rect(0, 0, container.getWidth(), container.getHeight()), handle)));
            }
            return;
        }
        ViewGroup.LayoutParams params = handle.getLayoutParams();
        if (params != null && params.width > 0 && params.width < width) {
            params.width = width;
            handle.setLayoutParams(params);
        }
    }

    public static void onEditCreated(Object callback, String playlistId, List<? extends MessageLite> actions) {
        if (isWatchLaterActive() && RemovalFeedback.isWatchLaterRemoval(playlistId, actions)) removals.put(callback, true);
    }

    public static MessageLite onEditResult(Callback callback, MessageLite response) {
        if (!removals.containsKey(callback) || !Settings.WATCH_LATER_HIDE_REMOVAL_SNACKBAR.get()) return response;
        try {
            var result = QueueSaveEditResponse.parseFrom(response.toByteArray());
            var filtered = RemovalFeedback.filterRemovalSnackbar(result);
            return filtered == result ? response : callback.patch_parseWatchLaterResult(filtered.toByteArray());
        } catch (Exception ex) {
            Logger.printException(() -> "Could not filter Watch later removal snackbar", ex);
            return response;
        }
    }

    /** Protobuf operations kept independent of the native UI classes. */
    static final class RemovalFeedback {
        static boolean isWatchLaterRemoval(String playlistId, List<? extends MessageLite> actions) {
            if (!"WL".equals(playlistId) || actions.isEmpty()) return false;
            try {
                for (MessageLite nativeAction : actions) {
                    var action = PlaylistEditAction.parseFrom(nativeAction.toByteArray()).getAction();
                    if (action != PlaylistEditActionType.ACTION_REMOVE_VIDEO
                            && action != PlaylistEditActionType.ACTION_REMOVE_VIDEO_BY_VIDEO_ID) return false;
                }
                return true;
            } catch (Exception ex) {
                Logger.printException(() -> "Could not identify Watch later removal", ex);
                return false;
            }
        }

        static QueueSaveEditResponse filterRemovalSnackbar(QueueSaveEditResponse result) {
            if (result.getStatus() != 1) return result;
            var builder = result.toBuilder();
            boolean changed = false;
            for (int i = 0; i < result.getActionsCount(); i++) {
                var action = result.getActions(i);
                if (action.hasAddToToastAction()) {
                    builder.setActions(i, action.toBuilder().clearAddToToastAction());
                    changed = true;
                }
            }
            return changed ? builder.build() : result;
        }
    }
}
