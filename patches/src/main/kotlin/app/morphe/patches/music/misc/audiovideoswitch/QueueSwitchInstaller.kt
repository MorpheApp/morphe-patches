/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3471
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.audiovideoswitch

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.music.interaction.jam.addBridge
import app.morphe.patches.music.interaction.jam.addReferenceGetter
import app.morphe.patches.music.interaction.jam.decode
import app.morphe.patches.music.interaction.jam.installNativeAccessor
import app.morphe.patches.music.interaction.jam.invokeKind
import app.morphe.util.cloneParameters
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/EnableAudioVideoSwitchPatch;"
private const val ACCESS =
    "Lapp/morphe/extension/music/patches/EnableAudioVideoSwitchPatch\$QueueAccess;"

/**
 * Every bridge is named patch_atv* and the original queue operations are never renamed here, so
 * enabling both patches never collides.
 */
internal fun BytecodePatchContext.installAudioVideoSwitchQueue(queue: AudioVideoQueueAbi) {
    val manager = mutableClassDefBy(queue.managerType)
    manager.interfaces.add(ACCESS)

    manager.addBridge(
        "patch_atvEnqueue",
        listOf("[B"),
        "V",
        4,
        body =
            queue.command.decode("p1", "v0") + """
            ${invokeKind(queue.enqueue)} {p0, v0}, ${queue.enqueue}
            return-void
        """
    )
    manager.addReferenceGetter("patch_atvExecutor", queue.executor)
    installNativeAccessor(
        manager,
        "patch_atvCurrentIndex",
        queue.storage.currentIndex,
        receiverField = queue.storage.field,
    )
    manager.addBridge(
        "patch_atvItems",
        emptyList(),
        "[Ljava/lang/Object;",
        3,
        body = """
            iget-object v0, p0, ${queue.storage.field}
            const/4 v1, 0x0
            ${invokeKind(queue.storage.items)} {v0, v1}, ${queue.storage.items}
            move-result-object v0
            invoke-interface {v0}, Ljava/util/List;->toArray()[Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """
    )
    installNativeAccessor(
        manager,
        "patch_atvVideoId",
        queue.videoId,
        opaqueReceiver = true,
    )
    manager.addBridge(
        "patch_atvRemove",
        listOf("Ljava/lang/Object;"),
        "V",
        2,
        body = """
            check-cast p1, ${queue.itemType}
            ${invokeKind(queue.remove)} {p0, p1}, ${queue.remove}
            return-void
        """
    )
    manager.addBridge(
        "patch_atvLocal",
        emptyList(),
        "Z",
        3,
        body = """
            iget-object v0, p0, ${queue.storage.field}
            ${invokeKind(queue.storage.mode)} {v0}, ${queue.storage.mode}
            move-result-object v0
            sget-object v1, ${queue.storage.localMode}
            if-ne v0, v1, :remote
            const/4 v0, 0x1
            return v0
            :remote
            const/4 v0, 0x0
            return v0
        """
    )

    installAudioVideoSwitchCapture(queue)
}

private fun BytecodePatchContext.installAudioVideoSwitchCapture(queue: AudioVideoQueueAbi) {
    queue.constructor.cloneParameters().apply {
        findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
            addInstructions(
                index,
                "invoke-static/range {p0 .. p0}, " +
                    "$EXTENSION_CLASS->capture($ACCESS)V",
            )
        }
    }
}
