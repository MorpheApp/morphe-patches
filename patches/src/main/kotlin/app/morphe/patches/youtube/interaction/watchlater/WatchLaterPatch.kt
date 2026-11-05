/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4324
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.watchlater

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.bridge.addBridge
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.interaction.feedactions.FeedComponentHostLayoutFingerprint
import app.morphe.patches.youtube.interaction.nativeplaylistqueue.QueueSaveEditErrorFingerprint
import app.morphe.patches.youtube.interaction.nativeplaylistqueue.parseProtoInstructions
import app.morphe.patches.youtube.interaction.nativeplaylistqueue.queueSaveEditSuccessFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.navigation.addBrowseIdHook
import app.morphe.patches.youtube.misc.navigation.addSearchResultsHook
import app.morphe.patches.youtube.misc.navigation.browseIdHookPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.is_21_05_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/WatchLaterPatch;"
private const val PLAYLIST_EDIT_CALLBACK_INTERFACE =
    "Lapp/morphe/extension/youtube/patches/WatchLaterPatch\$PlaylistEditCallback;"

@Suppress("unused")
val watchLaterPatch = bytecodePatch(
    name = "Watch later improvements",
    description = "Adds options to widen the drag handles of the Watch later videos " +
            "and to hide the snackbar shown after removing a video.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        // Finds if the Watch later playlist is shown.
        browseIdHookPatch,
        // Finds if the player covers the playlist.
        playerTypeHookPatch,
        versionCheckPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_watch_later_wide_drag_handles", summary = true),
            SwitchPreference("morphe_watch_later_hide_removal_snackbar", summary = true),
        )

        addBrowseIdHook("$EXTENSION_CLASS->setBrowseId(Ljava/lang/String;)V")
        addSearchResultsHook("$EXTENSION_CLASS->clearBrowseId()V")

        // region Wide drag handles.

        // Drag handles of regular views. The original code reuses the view register.
        DragHandleTouchListenerFingerprint.method.addInstruction(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS->onDragHandleCreated(Landroid/view/View;)V"
        )

        // Litho sets the size of the drag handle of its rows, so instead
        // the row host forwards the touches of a wider area to the handle.
        // Before 21.05, ComponentHost does not override onInterceptTouchEvent().
        if (is_21_05_or_greater) {
            FeedComponentHostLayoutFingerprint.method.apply {
                findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                    addInstructionsAtControlFlowLabel(
                        index,
                        "invoke-static { p0 }, $EXTENSION_CLASS->onComponentHostLayout(Lcom/facebook/litho/ComponentHost;)V"
                    )
                }
            }

            ComponentHostInterceptTouchFingerprint.method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { p0, p1 }, $EXTENSION_CLASS->onComponentHostInterceptTouch(Lcom/facebook/litho/ComponentHost;Landroid/view/MotionEvent;)Z
                    move-result v0
                    if-eqz v0, :original
                    return v0
                    :original
                    nop
                """
            )
        }

        // endregion

        // region Hide removal snackbar.

        val playlistEditCallback = QueueSaveEditErrorFingerprint.classDef
        val (playlistIdField, actionsField) = PlaylistEditCallbackConstructorFingerprint
            .instructionMatches.map { it.fieldReference }
        val responseMethod = queueSaveEditSuccessFingerprint(playlistEditCallback.type).method
        val responseType = responseMethod.parameterTypes.single().toString()

        playlistEditCallback.interfaces.add(PLAYLIST_EDIT_CALLBACK_INTERFACE)
        playlistEditCallback.addBridge(
            "patch_parsePlaylistEditResponse",
            listOf("[B"),
            "Lcom/google/protobuf/MessageLite;",
            4,
            """
                ${parseProtoInstructions(responseType, bytes = "p1", result = "v0", registry = "v1")}
                return-object v0
            """
        )

        // The toasts are removed before the app runs the actions of the response.
        responseMethod.addInstructions(
            0,
            """
                iget-object v0, p0, $playlistIdField
                iget-object v1, p0, $actionsField
                invoke-static { p0, v0, v1, p1 }, $EXTENSION_CLASS->onPlaylistEditResponse(${PLAYLIST_EDIT_CALLBACK_INTERFACE}Ljava/lang/String;Ljava/util/List;Lcom/google/protobuf/MessageLite;)Lcom/google/protobuf/MessageLite;
                move-result-object p1
                check-cast p1, $responseType
            """
        )

        // endregion
    }
}
