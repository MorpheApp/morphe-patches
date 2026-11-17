/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.likefilter

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.getMutableMethod
import app.morphe.patches.music.interaction.jam.QueueEnqueueFingerprint
import app.morphe.patches.music.interaction.jam.QueueStorageAbi
import app.morphe.patches.music.interaction.jam.addBridge
import app.morphe.patches.music.interaction.jam.implementsType
import app.morphe.patches.music.interaction.jam.interfaceClosure
import app.morphe.patches.music.interaction.jam.installNativeAccessor
import app.morphe.patches.music.interaction.jam.installQueueConstructorCapture
import app.morphe.patches.music.interaction.jam.invokeKind
import app.morphe.patches.music.interaction.jam.queueManagerConstructorFingerprint
import app.morphe.patches.music.interaction.jam.queueRemovalFingerprint
import app.morphe.patches.music.interaction.jam.queueSuccessCallbackFingerprint
import app.morphe.patches.music.interaction.jam.queueVideoIdFingerprint
import app.morphe.patches.music.interaction.jam.resolveDisplays
import app.morphe.patches.music.interaction.jam.resolveStorage
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXECUTOR = "Ljava/util/concurrent/Executor;"
private const val LIST = "Ljava/util/List;"

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/QueueLikeFilterPatch;"
private const val ACCESS =
    $$"Lapp/morphe/extension/music/patches/QueueLikeFilterPatch$QueueAccess;"
private const val QUEUE_LISTENER =
    $$"Lapp/morphe/extension/music/patches/QueueLikeFilterPatch$QueueListener;"

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
    val enqueueObserver: EnqueueObserverAbi,
)

/** The enqueue completion path used to recognize and exempt "Play next" items. */
internal data class EnqueueObserverAbi(
    val callbackType: String,
    val success: MethodReference,
    val responseType: String,
    val responseItems: FieldReference,
    val target: FieldReference,
    val playNextTarget: FieldReference,
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

    val enqueueObserver = resolveEnqueueObserver(manager)

    return QueueLikeFilterAbi(
        managerType = manager.type,
        constructor = constructor,
        storage = storage,
        remove = remove,
        itemType = itemType,
        videoId = videoId,
        executor = executor,
        enqueueObserver = enqueueObserver,
    )
}

/**
 * Resolves the enqueue completion path by shape. This deliberately avoids comparing methods or
 * native names, because another queue patch may already have renamed or wrapped them before this
 * patch runs.
 */
private fun resolveEnqueueObserver(manager: ClassDef): EnqueueObserverAbi {
    val callbackType =
        queueLikeFilterEnqueueCallbackFingerprint(manager.type).originalMethod.definingClass

    val callback = classDefBy(callbackType)
    val targetType =
        callback.fields
            .map { it.type }
            .singleOrNull { type ->
                type != manager.type &&
                    classDefByOrNull(type)?.let { AccessFlags.ENUM.isSet(it.accessFlags) } == true
            }
            ?: error("Missing or ambiguous queue like filter enqueue target")
    val target = callback.fields.single { it.type == targetType }
    val playNextTarget =
        queueLikeFilterPlayNextTargetFingerprint(targetType)
            .instructionMatches[1]
            .fieldReference

    val success = queueSuccessCallbackFingerprint(callbackType).originalMethod
    val responseType =
        success.typeReferences()
            .singleOrNull { type ->
                classDefByOrNull(type)?.fields?.any { field ->
                    field.type == LIST || implementsType(field.type, LIST)
                } == true
            }
            ?: error("Missing or ambiguous queue like filter enqueue response")
    val responseItems =
        classDefBy(responseType).fields
            .singleOrNull { it.type == LIST || implementsType(it.type, LIST) }
            ?: error("Missing or ambiguous queue like filter enqueue response items")

    return EnqueueObserverAbi(
        callbackType = callbackType,
        success = success,
        responseType = responseType,
        responseItems = responseItems,
        target = target,
        playNextTarget = playNextTarget,
    )
}

internal fun installQueueLikeFilter(queue: QueueLikeFilterAbi) {
    val manager = mutableClassDefBy(queue.managerType)

    manager.addPatchInterface(
        ACCESS,
        "patch_qlfItems",
        """
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
        ACCESS,
        "patch_qlfCurrentIndex",
        queue.storage.currentIndex,
        receiverField = queue.storage.field,
    )
    installNativeAccessor(
        manager,
        ACCESS,
        "patch_qlfVideoId",
        queue.videoId,
        opaqueReceiver = true,
    )
    manager.addPatchInterface(ACCESS, "patch_qlfExecutor", queue.executor)
    manager.addPatchInterface(
        ACCESS,
        "patch_qlfRemove",
        """
            check-cast p1, ${queue.itemType}
            ${invokeKind(queue.remove)} {p0, p1}, ${queue.remove}
            return-void
        """,
        parameters = listOf("Ljava/lang/Object;"),
    )
    manager.addPatchInterface(
        ACCESS,
        "patch_qlfPrimaryLane",
        """
            iget-object v0, p0, ${queue.storage.field}
            const/4 v1, 0x0
            ${invokeKind(queue.storage.lane)} {v0, v1}, ${queue.storage.lane}
            move-result-object v0
            return-object v0
        """
    )
    manager.addPatchInterface(
        ACCESS,
        "patch_qlfAddListener",
        """
            check-cast p1, ${queue.storage.laneType}
            check-cast p2, ${queue.storage.listenerType}
            ${invokeKind(queue.storage.attachListener)} {p1, p2}, ${queue.storage.attachListener}
            return-void
        """,
        parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
    )

    installQueueListener(queue.storage.listenerType)
    installEnqueueObserver(queue)
    installQueueConstructorCapture(queue.constructor, "$EXTENSION_CLASS->capture($ACCESS)V")
}

/**
 * Marks songs the user queued with "Play next" before they enter the queue. The filter must not
 * remove those songs, just like it never considers the currently playing track.
 */
private fun installEnqueueObserver(queue: QueueLikeFilterAbi) {
    val callback = mutableClassDefBy(queue.enqueueObserver.callbackType)
    callback.addBridge(
        "patch_qlfOnEnqueuedSuccess",
        listOf("Ljava/lang/Object;"),
        "V",
        5,
        body = """
            check-cast p1, ${queue.enqueueObserver.responseType}
            iget-object v0, p1, ${queue.enqueueObserver.responseItems}
            iget-object v1, p0, ${queue.enqueueObserver.target}
            sget-object v2, ${queue.enqueueObserver.playNextTarget}
            if-ne v1, v2, :qlf_not_play_next
            const/4 v1, 0x1
            goto :qlf_items_enqueued
            :qlf_not_play_next
            const/4 v1, 0x0
            :qlf_items_enqueued
            invoke-static {v0, v1}, $EXTENSION_CLASS->onItemsEnqueued(Ljava/util/List;Z)V
            return-void
        """
    )
    queue.enqueueObserver.success.getMutableMethod().addInstruction(
        0,
        "invoke-virtual {p0, p1}, ${callback.type}->patch_qlfOnEnqueuedSuccess(Ljava/lang/Object;)V"
    )
}

/** Every listener callback does the same thing: tell the worker the queue changed. */
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
                    invoke-static {}, $EXTENSION_CLASS->onQueueChanged()V
                    $returnDefault
                """,
                callback.parameterTypes.map { it.toString() },
            )
        }
}
