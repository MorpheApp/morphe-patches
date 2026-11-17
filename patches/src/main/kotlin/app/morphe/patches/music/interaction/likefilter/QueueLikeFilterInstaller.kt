/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.likefilter

import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.numberOfParameterRegisters
import app.morphe.patches.music.interaction.jam.QueueEnqueueFingerprint
import app.morphe.patches.music.interaction.jam.QueueStorageAbi
import app.morphe.patches.music.interaction.jam.addBridge
import app.morphe.patches.music.interaction.jam.addReferenceGetter
import app.morphe.patches.music.interaction.jam.interfaceClosure
import app.morphe.patches.music.interaction.jam.installNativeAccessor
import app.morphe.patches.music.interaction.jam.installQueueConstructorCapture
import app.morphe.patches.music.interaction.jam.invokeKind
import app.morphe.patches.music.interaction.jam.queueManagerConstructorFingerprint
import app.morphe.patches.music.interaction.jam.queueRemovalFingerprint
import app.morphe.patches.music.interaction.jam.queueVideoIdFingerprint
import app.morphe.patches.music.interaction.jam.resolveDisplays
import app.morphe.patches.music.interaction.jam.resolveStorage
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXECUTOR = "Ljava/util/concurrent/Executor;"

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/QueueLikeFilterPatch;"
private const val ACCESS =
    $$"Lapp/morphe/extension/music/patches/QueueLikeFilterPatch$QueueAccess;"
private const val QUEUE_LISTENER =
    $$"Lapp/morphe/extension/music/patches/QueueLikeFilterPatch$QueueListener;"

/** The receiver, plus a wide register for a default return value. */
private const val QUEUE_LISTENER_LOCAL_REGISTERS = 3

/**
 * The queue members the filter needs, resolved from the same stable relationships Jam uses.
 */
internal data class QueueLikeFilterAbi(
    val managerType: String,
    val constructor: Method,
    val storage: QueueStorageAbi,
    val remove: MethodReference,
    val itemType: String,
    val videoId: MethodReference,
    val executor: FieldReference,
)

internal fun resolveQueueLikeFilterAbi(): QueueLikeFilterAbi {
    val queueMatch = QueueEnqueueFingerprint.match()
    val manager = queueMatch.originalClassDef
    val constructor = queueManagerConstructorFingerprint(manager.type).originalMethod
    val executor = manager.fields.singleOrNull { it.type == EXECUTOR }
        ?: error("Missing or ambiguous queue like filter executor")
    val storage = resolveStorage(manager)
    val displays = resolveDisplays(manager, constructor, storage)
    val remove = queueRemovalFingerprint(manager.type, displays).originalMethod
    val itemType = remove.parameterTypes.singleOrNull()?.toString()
    require(!itemType.isNullOrBlank() && itemType.startsWith("L")) {
        "Unable to resolve queue like filter item type"
    }
    val videoId =
        queueVideoIdFingerprint(interfaceClosure(itemType))
            .matchAll()
            .groupBy { it.originalClassDef.type }
            .values
            .singleOrNull { it.size == 1 }
            ?.single()
            ?.originalMethod
            ?: error("Missing or ambiguous queue like filter video ID")

    return QueueLikeFilterAbi(
        managerType = manager.type,
        constructor = constructor,
        storage = storage,
        remove = remove,
        itemType = itemType,
        videoId = videoId,
        executor = executor,
    )
}

internal fun installQueueLikeFilter(queue: QueueLikeFilterAbi) {
    val manager = mutableClassDefBy(queue.managerType)
    manager.interfaces.add(ACCESS)

    manager.addBridge(
        "patch_qlfItems",
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
        "patch_qlfCurrentIndex",
        queue.storage.currentIndex,
        receiverField = queue.storage.field,
    )
    installNativeAccessor(
        manager,
        "patch_qlfVideoId",
        queue.videoId,
        opaqueReceiver = true,
    )
    manager.addReferenceGetter("patch_qlfExecutor", queue.executor)
    manager.addBridge(
        "patch_qlfRemove",
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
        "patch_qlfPrimaryLane",
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
        "patch_qlfAddListener",
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

    installQueueListener(queue.storage.listenerType)
    installQueueConstructorCapture(queue.constructor, "$EXTENSION_CLASS->capture($ACCESS)V")
}

/** Every listener callback does the same thing: tell the worker the queue changed. */
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
                callback.numberOfParameterRegisters + QUEUE_LISTENER_LOCAL_REGISTERS,
                body = """
                    invoke-static {}, $EXTENSION_CLASS->onQueueChanged()V
                    $returnDefault
                """
            )
        }
}
