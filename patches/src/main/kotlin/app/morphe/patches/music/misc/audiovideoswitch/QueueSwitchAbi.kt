/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3471
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.audiovideoswitch

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.music.interaction.jam.ProtoAbi
import app.morphe.patches.music.interaction.jam.QueueEnqueueFingerprint
import app.morphe.patches.music.interaction.jam.QueueStorageAbi
import app.morphe.patches.music.interaction.jam.interfaceClosure
import app.morphe.patches.music.interaction.jam.queueManagerConstructorFingerprint
import app.morphe.patches.music.interaction.jam.queueRemovalFingerprint
import app.morphe.patches.music.interaction.jam.queueVideoIdFingerprint
import app.morphe.patches.music.interaction.jam.resolveDisplays
import app.morphe.patches.music.interaction.jam.resolveProto
import app.morphe.patches.music.interaction.jam.resolveStorage
import app.morphe.util.matchSingle
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXECUTOR = "Ljava/util/concurrent/Executor;"

/**
 * The members the switch needs, resolved from the same stable queue relationships Jam uses so
 * enabling both patches never changes what is matched.
 */
internal data class AudioVideoQueueAbi(
    val managerType: String,
    val constructor: Method,
    val enqueue: MethodReference,
    val command: ProtoAbi,
    val executor: FieldReference,
    val storage: QueueStorageAbi,
    val remove: MethodReference,
    val itemType: String,
    val videoId: MethodReference,
)

internal fun BytecodePatchContext.resolveAudioVideoQueue(): AudioVideoQueueAbi {
    val queueMatch = QueueEnqueueFingerprint.matchSingle()
    val manager = queueMatch.originalClassDef
    val enqueue = queueMatch.originalMethod
    val command = resolveProto(
        enqueue.parameterTypes.singleOrNull()?.toString(),
        "queue command",
    )
    val constructor =
        queueManagerConstructorFingerprint(manager.type).matchSingle().originalMethod
    val executor =
        manager.fields.filter { it.type == EXECUTOR }.singleOrNull()
            ?: error("Missing or ambiguous audio/video switch queue executor")
    val storage = resolveStorage(manager, enqueue)
    val displays = resolveDisplays(manager, constructor, storage)
    val remove = queueRemovalFingerprint(manager.type, displays).matchSingle().originalMethod
    val itemType = remove.parameterTypes.singleOrNull()?.toString()
    require(!itemType.isNullOrBlank() && itemType.startsWith("L")) {
        "Unable to resolve audio/video switch queue item type"
    }
    val videoId =
        queueVideoIdFingerprint(interfaceClosure(itemType))
            .matchAll()
            .groupBy { it.originalClassDef.type }
            .values
            .filter { it.size == 1 }
            .singleOrNull()
            ?.single()
            ?.originalMethod
            ?: error("Missing or ambiguous audio/video switch queue video ID")

    return AudioVideoQueueAbi(
        managerType = manager.type,
        constructor = constructor,
        enqueue = enqueue,
        command = command,
        executor = executor,
        storage = storage,
        remove = remove,
        itemType = itemType,
        videoId = videoId,
    )
}
