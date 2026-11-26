/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4929
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.likefilter

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patches.music.interaction.jam.QueueEnqueueFingerprint
import app.morphe.patches.music.interaction.jam.QueueStorageAbi
import app.morphe.patches.music.interaction.jam.installNativeAccessor
import app.morphe.patches.music.interaction.jam.installQueueConstructorCapture
import app.morphe.patches.music.interaction.jam.invokeKind
import app.morphe.patches.music.interaction.jam.queueManagerConstructorFingerprint
import app.morphe.patches.music.interaction.jam.queueRemovalFingerprint
import app.morphe.patches.music.interaction.jam.resolveDisplays
import app.morphe.patches.music.interaction.jam.resolveQueueVideoId
import app.morphe.patches.music.interaction.jam.resolveStorage
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.video.information.musicVideoIdHook
import app.morphe.patches.music.video.information.musicVideoInformationPatch
import app.morphe.patches.music.video.playerresponse.Hook
import app.morphe.patches.music.video.playerresponse.addPlayerResponseMethodHook
import app.morphe.patches.music.video.playerresponse.musicPlayerResponseMethodHookPatch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/QueueLikeFilterPatch;"
private const val QUEUE_ACCESS =
    $$"Lapp/morphe/extension/music/patches/QueueLikeFilterPatch$QueueAccess;"
private const val QUEUE_LISTENER =
    $$"Lapp/morphe/extension/music/patches/QueueLikeFilterPatch$QueueListener;"

private const val EXECUTOR = "Ljava/util/concurrent/Executor;"

val queueLikeFilterPatch = patch(
    name = "Queue likes filter",
    description = "Adds an option to remove songs with few likes from auto-generated mixes and radio, " +
            "using the like counts of Return YouTube Dislike. Playlists and albums are left unchanged.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        musicVideoInformationPatch,
        musicPlayerResponseMethodHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            // Keep the toggle directly above the minimum, even though the Player screen
            // otherwise sorts its preferences by title.
            noTitleUnsortedPreferenceCategory(
                SwitchPreference("morphe_music_queue_like_filter", summary = true),
                TextPreference(
                    key = "morphe_music_queue_like_filter_minimum_likes",
                    inputType = InputType.NUMBER,
                ),
            ),
        )

        // The queue members are resolved the same way as in Jam queue sharing.
        val manager = QueueEnqueueFingerprint.classDef
        val constructor = queueManagerConstructorFingerprint(manager.type).originalMethod
        val storage = resolveStorage(manager)
        val displays = resolveDisplays(manager, constructor, storage)
        val remove = queueRemovalFingerprint(manager.type, displays).originalMethod
        val videoId = resolveQueueVideoId(remove.parameterTypes.single().toString())
        val executor = manager.fields.singleOrNull { it.type == EXECUTOR }
            ?: error("Missing or ambiguous queue executor")

        manager.addPatchInterface(QUEUE_ACCESS, "patch_likeFilterExecutor", executor)
        manager.addPatchInterface(QUEUE_ACCESS, "patch_likeFilterRemove", remove)
        installNativeAccessor(
            manager,
            QUEUE_ACCESS,
            "patch_likeFilterCurrentIndex",
            storage.currentIndex,
            receiverField = storage.field,
        )
        installNativeAccessor(
            manager,
            QUEUE_ACCESS,
            "patch_likeFilterVideoId",
            videoId,
            opaqueReceiver = true,
        )
        installLaneAccess(manager, storage)
        installQueueListener(storage.listenerType)
        installQueueConstructorCapture(constructor, "$EXTENSION_CLASS->setQueue($QUEUE_ACCESS)V")

        // Songs added by the user are never removed.
        QueueEnqueueSuccessFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[1].index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static/range { v$register .. v$register }, " +
                            "$EXTENSION_CLASS->onSongsEnqueued(Ljava/util/List;)V"
                )
            }
        }

        // Check the queue again whenever playback moves to another track.
        musicVideoIdHook("$EXTENSION_CLASS->onVideoIdChanged(Ljava/lang/String;)V")

        // Track the playlist of the current track, so playlists and albums are left unchanged.
        addPlayerResponseMethodHook(
            Hook.VideoIdAndPlaylistId(
                "$EXTENSION_CLASS->onPlaylistChanged(Ljava/lang/String;Ljava/lang/String;I)V"
            )
        )
    }
}

private fun installLaneAccess(manager: MutableClass, storage: QueueStorageAbi) {
    manager.addPatchInterface(
        QUEUE_ACCESS,
        "patch_likeFilterLaneItems",
        """
            iget-object v0, p0, ${storage.field}
            ${invokeKind(storage.items)} { v0, p1 }, ${storage.items}
            move-result-object v0
            return-object v0
        """
    )
    manager.addPatchInterface(
        QUEUE_ACCESS,
        "patch_likeFilterLane",
        """
            iget-object v0, p0, ${storage.field}
            ${invokeKind(storage.lane)} { v0, p1 }, ${storage.lane}
            move-result-object v0
            return-object v0
        """
    )
    manager.addPatchInterface(
        QUEUE_ACCESS,
        "patch_likeFilterAddListener",
        """
            check-cast p1, ${storage.laneType}
            check-cast p2, ${storage.listenerType}
            ${invokeKind(storage.attachListener)} { p1, p2 }, ${storage.attachListener}
            return-void
        """
    )
}

/**
 * Makes the extension listener implement the app queue listener. Every callback tells the
 * extension that the queue changed.
 */
private fun installQueueListener(listenerType: String) {
    val listener = mutableClassDefBy(QUEUE_LISTENER)
    classDefBy(listenerType).methods
        .filter { AccessFlags.ABSTRACT.isSet(it.accessFlags) }
        .forEach { callback ->
            val returnDefault = when (callback.returnType.first()) {
                'V' -> "return-void"
                'L', '[' -> "const/4 v0, 0x0\nreturn-object v0"
                'J', 'D' -> "const-wide/16 v0, 0x0\nreturn-wide v0"
                else -> "const/4 v0, 0x0\nreturn v0"
            }
            listener.addPatchInterface(
                listenerType,
                callback.name,
                """
                    invoke-static { }, $EXTENSION_CLASS->onQueueChanged()V
                    $returnDefault
                """,
                callback.parameterTypes.map { it.toString() },
            )
        }
}
