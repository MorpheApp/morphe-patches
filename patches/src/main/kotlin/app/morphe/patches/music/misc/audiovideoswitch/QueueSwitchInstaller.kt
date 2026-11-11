/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3471
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.audiovideoswitch

import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.numberOfParameterRegisters
import app.morphe.patches.music.interaction.jam.addBridge
import app.morphe.patches.music.interaction.jam.addReferenceGetter
import app.morphe.patches.music.interaction.jam.decode
import app.morphe.patches.music.interaction.jam.installNativeAccessor
import app.morphe.patches.music.interaction.jam.installQueueConstructorCapture
import app.morphe.patches.music.interaction.jam.invokeKind
import com.android.tools.smali.dexlib2.AccessFlags

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/EnableAudioVideoSwitchPatch;"
private const val ACCESS =
    $$"Lapp/morphe/extension/music/patches/EnableAudioVideoSwitchPatch$QueueAccess;"
private const val QUEUE_LISTENER =
    $$"Lapp/morphe/extension/music/patches/AutoSongVersionPatch$QueueListener;"

/** The receiver, plus a wide register for a default return value. */
private const val LISTENER_LOCAL_REGISTERS = 3

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
        "patch_atvMove",
        listOf("I", "I"),
        "V",
        5,
        body = """
            iget-object v0, p0, ${queue.storage.field}
            const/4 v1, 0x0
            ${invokeKind(queue.storage.lane)} {v0, v1}, ${queue.storage.lane}
            move-result-object v0
            ${invokeKind(queue.storage.laneMove)} {v0, p1, p2}, ${queue.storage.laneMove}
            return-void
        """
    )
    manager.addBridge(
        "patch_atvPrimaryLane",
        emptyList(),
        "Ljava/lang/Object;",
        3,
        body = """
            iget-object v0, p0, ${queue.storage.field}
            const/4 v1, 0x0
            ${invokeKind(queue.storage.lane)} {v0, v1}, ${queue.storage.lane}
            move-result-object v0
            return-object v0
        """
    )
    manager.addBridge(
        "patch_atvAddListener",
        listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
        "V",
        3,
        body = """
            check-cast p1, ${queue.storage.laneType}
            check-cast p2, ${queue.storage.listenerType}
            ${invokeKind(queue.storage.attachListener)} {p1, p2}, ${queue.storage.attachListener}
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

    installQueueListener(queue.storage.listenerType)
    installAudioVideoSwitchCapture(queue)
}

/**
 * All the listener callbacks do the same thing, tell us the queue changed.
 */
private fun installQueueListener(listenerType: String) {
    val listener = mutableClassDefBy(QUEUE_LISTENER)
    listener.interfaces.add(listenerType)
    classDefBy(listenerType).methods
        .filter { AccessFlags.ABSTRACT.isSet(it.accessFlags) }
        .forEach { callback ->
            val returnDefault = when (callback.returnType.first()) {
                'V' -> "return-void"
                'L', '[' -> "const/4 v0, 0x0\nreturn-object v0"
                'J', 'D' -> "const-wide/16 v0, 0x0\nreturn-wide v0"
                else -> "const/4 v0, 0x0\nreturn v0"
            }
            listener.addBridge(
                callback.name,
                callback.parameterTypes.map { it.toString() },
                callback.returnType,
                callback.numberOfParameterRegisters + LISTENER_LOCAL_REGISTERS,
                body = """
                    invoke-static {}, $AUTO_SONG_VERSION_CLASS->onQueueChanged()V
                    $returnDefault
                """
            )
        }
}

private fun installAudioVideoSwitchCapture(queue: AudioVideoQueueAbi) {
    installQueueConstructorCapture(queue.constructor, "$EXTENSION_CLASS->capture($ACCESS)V")
}
