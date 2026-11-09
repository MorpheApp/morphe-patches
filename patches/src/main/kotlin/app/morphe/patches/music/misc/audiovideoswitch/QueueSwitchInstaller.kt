/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3471
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.audiovideoswitch

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.getMutableMethod
import app.morphe.patcher.util.numberOfParameterRegisters
import app.morphe.patches.music.interaction.jam.addBridge
import app.morphe.patches.music.interaction.jam.addReferenceGetter
import app.morphe.patches.music.interaction.jam.decode
import app.morphe.patches.music.interaction.jam.installNativeAccessor
import app.morphe.patches.music.interaction.jam.invokeKind
import app.morphe.util.cloneMutable
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/EnableAudioVideoSwitchPatch;"
private const val ACCESS =
    $$"Lapp/morphe/extension/music/patches/EnableAudioVideoSwitchPatch$QueueAccess;"

/**
 * Every bridge is named patch_atv* and the original queue operations are never renamed here, so
 * enabling both patches never collides.
 */
internal fun installAudioVideoSwitchQueue(queue: AudioVideoQueueAbi) {
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

private fun installAudioVideoSwitchCapture(queue: AudioVideoQueueAbi) {
    // The clone has to replace the original or it does nothing. p0 can get reassigned in the
    // constructor so keep it in its own register, same as Jam.
    val original = queue.constructor.getMutableMethod()
    val preservedThisRegister = requireNotNull(original.implementation).registerCount
    val constructor =
        original.cloneMutable(additionalRegisters = original.numberOfParameterRegisters + 1)
    mutableClassDefBy(original.definingClass).methods.apply {
        remove(original)
        add(constructor)
    }
    constructor.addInstructions(0, "move-object/from16 v$preservedThisRegister, p0")
    constructor.findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
        constructor.addInstructions(
            index,
            "invoke-static/range {v$preservedThisRegister .. v$preservedThisRegister}, " +
                "$EXTENSION_CLASS->capture($ACCESS)V",
        )
    }
}
