/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.innertube.queue.fullscreen.QueueFullscreenRenderer;

/** Supplies only the playlist action to YouTube's existing fullscreen renderer. */
public final class QueueFullscreenActions {
    public interface Controller {
        Object patch_parseQuickActions(byte[] bytes);

        void patch_refreshQuickActions(Object model);
    }

    private static WeakReference<Controller> controllerRef = new WeakReference<>(null);
    @Nullable
    private static Object originalModel;
    private static boolean renderedWithQueue;

    private QueueFullscreenActions() {
    }

    /** Injection point in the native UI model setter, including its null/reset path. */
    @Nullable
    public static Object onModelLoaded(Controller controller, @Nullable Object model) {
        try {
            controllerRef = new WeakReference<>(controller);
            originalModel = model;
            renderedWithQueue = NativePlaylistQueuePatch.ownsFullscreenPlaylist();
            if (model == null || !renderedWithQueue) return model;
            var renderer = QueueFullscreenRenderer.Renderer.parseFrom(((MessageLite) model).toByteArray());
            var component = renderer.getNewElement().getType().getComponent();
            if (!component.getModel().hasQuickActions()) return model;
            var actions = component.getModel().getQuickActions();

            // An account playlist already supplies its own action; leave it intact.
            if (actions.hasPlaylistButton()) return model;
            var button = QueueFullscreenRenderer.PlaylistButton.newBuilder().setStyle(2).setButton(
                    QueueFullscreenRenderer.Button.newBuilder()
                            .setIcon("yt_outline_list_play_arrow_black_24")
                            .setAccessibilityLabel(str("show_playlist_videos"))
                            .setOnTap(QueueFullscreenRenderer.Command.newBuilder().setInnertubeCommand(
                                    QueueFullscreenRenderer.Endpoint.newBuilder().setShowEngagementPanel(
                                            QueueFullscreenRenderer.ShowPanel.newBuilder()
                                                    .setPanelId("engagement-panel-playlist")))));
            var updatedComponent = component.toBuilder().setModel(component.getModel().toBuilder()
                    .setQuickActions(actions.toBuilder().setPlaylistButton(button)));
            var element = renderer.getNewElement();
            return controller.patch_parseQuickActions(renderer.toBuilder().setNewElement(element.toBuilder()
                    .setType(element.getType().toBuilder().setComponent(updatedComponent))).build().toByteArray());
        } catch (Exception ex) {
            Logger.printException(() -> "onModelLoaded failure", ex);
            return model;
        }
    }

    /** Called after queue ownership changes, never for metadata/order-only updates. */
    static void refresh() {
        Controller controller = controllerRef.get();
        Object model = originalModel;
        if (controller == null || model == null
                || renderedWithQueue == NativePlaylistQueuePatch.ownsFullscreenPlaylist()) return;
        try {
            controller.patch_refreshQuickActions(model);
        } catch (Exception ex) {
            // A presentation refresh must never roll back an attached playback queue.
            Logger.printException(() -> "Could not refresh native fullscreen queue action", ex);
        }
    }
}
