/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.proto.hookElement
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.misc.auth.authHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.is_21_15_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.hookVideoIntentCommandTransform
import app.morphe.patches.youtube.shared.hookVideoIntentContext
import app.morphe.patches.youtube.shared.hookVideoIntentInterceptor
import app.morphe.patches.youtube.shared.openVideoIntentPatch
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.videoid.hookBackgroundPlayVideoId
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_PACKAGE = "Lapp/morphe/extension/youtube/patches/queue"
internal const val EXTENSION_CLASS = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch;"
internal const val CONTROLLER_INTERFACE = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch\$PlaylistController;"
internal const val NAVIGATOR_OWNER_INTERFACE = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch\$NavigatorOwner;"
internal const val PLAYER_CONTROLS_INTERFACE = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch\$PlayerControls;"
internal const val MEDIA_CONTROLS_INTERFACE = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch\$MediaControls;"
internal const val NOTIFICATION_CONTROLS_INTERFACE = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch\$NotificationControls;"
internal const val SERVICE_NAVIGATOR_INTERFACE = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch\$ServiceNavigator;"
internal const val DIRECT_NAVIGATOR_INTERFACE = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch\$DirectQueueNavigator;"
internal const val NATIVE_PLAYBACK_INTERFACE = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch\$NativePlayback;"
internal const val REPEAT_HEADER_INTERFACE = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch\$RepeatHeader;"
internal const val ROW_CLICK_INTERFACE = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch\$RowClick;"
internal const val SWIPE_BACKGROUND_INTERFACE = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch\$SwipeBackground;"
internal const val QUEUE_PANEL_INTERFACE = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch\$QueuePanel;"
internal const val PLAYLIST_TITLE_INTERFACE = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch\$PlaylistTitle;"
internal const val PLAYLIST_MODEL_INTERFACE = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch\$PlaylistModel;"
internal const val QUEUE_ROW_INTERFACE = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch\$QueueRow;"
internal const val REORDER_ITEMS_INTERFACE = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch\$ReorderItems;"
internal const val FULLSCREEN_ACTIONS_CLASS = "$EXTENSION_PACKAGE/QueueFullscreenActions;"
internal const val FULLSCREEN_CONTROLLER_INTERFACE = "$EXTENSION_PACKAGE/QueueFullscreenActions\$Controller;"
internal const val SAVE_ACTIONS_CLASS = "$EXTENSION_PACKAGE/QueueSaveActions;"
internal const val SAVE_PICKER_INTERFACE = "$EXTENSION_PACKAGE/QueueSaveActions\$Picker;"
internal const val SAVE_FEEDBACK_CLASS = "$EXTENSION_PACKAGE/QueueSaveFeedback;"
internal const val SAVE_CALLBACK_INTERFACE = "$EXTENSION_PACKAGE/QueueSaveFeedback\$Callback;"
internal const val SAVE_SELECTION_CLASS = "$EXTENSION_PACKAGE/QueueSaveFeedback\$Selection;"
internal const val UNDO_COMMAND_INTERFACE = "$EXTENSION_PACKAGE/QueueUndoActions\$Command;"

/**
 * Adds a temporary video queue using YouTube's playlist interface and playback controls.
 * Used by Add to queue when the queue type is set to the native queue.
 */
val nativePlaylistQueuePatch = bytecodePatch(
    description = "Adds a temporary video queue using YouTube's playlist interface and playback controls."
) {
    dependsOn(
        sharedExtensionPatch,
        authHookPatch,
        settingsPatch,
        flyoutPatch,
        videoInformationPatch,
        videoIdPatch,
        playerTypeHookPatch,
        fixProtoLibraryPatch,
        openVideoIntentPatch,
        versionCheckPatch
    )

    execute {
        // 20.x builds the native playlist queue differently.
        // Add to queue then does not offer the native queue type.
        if (!is_21_15_or_greater) {
            return@execute
        }

        hookVideoIntentCommandTransform(EXTENSION_CLASS, "prepareQueueVideoCommand")
        hookVideoIntentInterceptor(EXTENSION_CLASS, "onQueueMixTap")
        hookVideoIntentContext(EXTENSION_CLASS, "prepareQueueStartContext")

        installQueueFlyout(this)
        installPlaylistPanel(this)
        installPlaylistTitle(this)
        installRatingCapture(this)
        installPlaylistRemoval(this)
        installFullscreenPlaylist(this)

        hookElement("$EXTENSION_CLASS->onElementLoaded")
        hookVideoId("$EXTENSION_CLASS->onVideoChanged(Ljava/lang/String;)V")
        hookBackgroundPlayVideoId("$EXTENSION_CLASS->onVideoChanged(Ljava/lang/String;)V")
        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
