/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.shared.misc.bridge.addBridge
import app.morphe.patches.youtube.misc.playservice.is_21_43_or_greater
import app.morphe.patches.youtube.video.information.PlaybackStartDescriptorToStringFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val JAVA_OBJECT = "Ljava/lang/Object;"
private const val OFFLINE_PLAYBACK_WORKER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/InAppDownloadsPatch$OfflinePlaybackWorker;"

internal const val DOWNLOADS_PLAYLIST_ID = "PPSV"

internal fun hookOfflineDownloadResume(playerResponseModelType: String, modelImplementation: String) {
    val descriptor = PlaybackStartDescriptorToStringFingerprint
    val descriptorType = descriptor.classDef.type
    val videoIdGetter = descriptor.instructionMatches[1].methodReference
    val (_, playlistId) = offlineDownloadPlaylistIdFingerprint(videoIdGetter)
        .match(descriptor.originalMethod).instructionMatches
    // Null keeps YouTube's start time. Zero is a valid Downloads time.
    val startTimeMs = ImmutableField(
        descriptorType, "patch_downloadStartTimeMs", "Ljava/lang/Long;",
        AccessFlags.PUBLIC.value or AccessFlags.VOLATILE.value, null, null, null
    ).toMutable()
    descriptor.classDef.fields.add(startTimeMs)
    val worker = offlinePlaybackWorkerFingerprint(descriptorType)
    val rowGetter = offlinePlaybackRowFingerprint(worker.classDef.type).method
    val (_, _, savedPositionSeconds) =
        offlineDownloadPositionCopyFingerprint(rowGetter.returnType).instructionMatches
    val (calculateStartTimeSeconds) = OfflineDownloadCardCommandFingerprint.instructionMatches
    val (_, duration) = offlineDownloadDurationFingerprint(modelImplementation).instructionMatches
    val durationSecondsGetter = duration.methodReference.name

    worker.classDef.interfaces.add(OFFLINE_PLAYBACK_WORKER_INTERFACE)
    worker.classDef.addBridge(
        "patch_storeDownloadStartTime",
        listOf(JAVA_OBJECT, JAVA_OBJECT),
        "V",
        8,
        """
            check-cast p1, $descriptorType
            const/4 v0, 0x0
            iput-object v0, p1, $startTimeMs
            if-eqz p2, :done
            invoke-virtual { p1 }, ${playlistId.methodReference}
            move-result-object v0
            const-string v1, "$DOWNLOADS_PLAYLIST_ID"
            invoke-virtual { v1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :done

            invoke-virtual { p1 }, $videoIdGetter
            move-result-object v0
            invoke-static { v0 }, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z
            move-result v1
            if-nez v1, :done

            invoke-direct { p0, v0 }, $rowGetter
            move-result-object v0
            if-eqz v0, :done
            iget-wide v0, v0, ${savedPositionSeconds.fieldReference}
            const-wide/16 v2, 0x0
            cmp-long v4, v0, v2
            if-ltz v4, :done

            check-cast p2, $playerResponseModelType
            invoke-interface { p2 }, $playerResponseModelType->$durationSecondsGetter()I
            move-result v2
            if-lez v2, :done
            int-to-long v2, v2
            invoke-static { v2, v3, v0, v1 }, ${calculateStartTimeSeconds.methodReference}
            move-result v0
            # YouTube truncates the card time to whole seconds before converting to milliseconds.
            float-to-long v0, v0
            const-wide/16 v2, 0x3e8
            mul-long/2addr v0, v2
            invoke-static { v0, v1 }, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;
            move-result-object v0
            iput-object v0, p1, $startTimeMs

            :done
            return-void
        """
    )

    // Both paths reach this callback without a Downloads row.
    // Read it here through the worker's store for the current account.
    val (success) = worker.instructionMatches
    val callbackRegisters = success.instruction.registersUsed
    val (_, _, responseRegister) = callbackRegisters
    val storeDownloadStartTime = "$EXTENSION_IN_APP_DOWNLOADS_CLASS->" +
        "storeDownloadStartTime($OFFLINE_PLAYBACK_WORKER_INTERFACE$JAVA_OBJECT$JAVA_OBJECT)V"
    val instructions = if (is_21_43_or_greater) {
        // 21.43.523+: The worker's p0 and p1 fit in invoke-static.
        "invoke-static { p0, p1, v$responseRegister }, $storeDownloadStartTime"
    } else {
        // Before 21.43.523: p0 and p1 are above v15.
        // Reuse YouTube's low copy of p0 and move p1 into v0 for invoke-static.
        val (receiver) = OfflinePlaybackWorkerReceiverFingerprint.match(worker.originalMethod).instructionMatches
        val receiverMove = receiver.getInstruction<TwoRegisterInstruction>()
        val receiverRegister = receiverMove.registerA
        check(receiverMove.registerB == worker.originalMethod.p0Register) {
            "Downloads: The worker's first move must copy its receiver"
        }
        check(receiverRegister in 1..15 && 0 !in callbackRegisters) {
            "Downloads: The worker hook needs v0 free and its receiver in a low register"
        }
        """
            move-object/from16 v0, p1
            invoke-static { v$receiverRegister, v0, v$responseRegister }, $storeDownloadStartTime
        """
    }
    worker.method.addInstructionsAtControlFlowLabel(success.index, instructions)

    applyOfflineDownloadStart(playerResponseModelType, descriptorType, startTimeMs)
}

private fun applyOfflineDownloadStart(modelType: String, descriptorType: String, startTimeMs: FieldReference) {
    val load = offlineDownloadLoadFingerprint(modelType, descriptorType)
        .instructionMatches.first().getMethodCalled()
    val (player) = offlineDownloadPlayerFingerprint(load.parameterTypes.first().toString(), modelType)
        .match(load).instructionMatches
    val playerType = player.fieldReference.type
    val apply = offlineDownloadPositionApplyFingerprint(playerType).match(load)
    val (setter, startPlayback) = apply.instructionMatches
    val applyStartMethod = ImmutableMethod(
        apply.classDef.type,
        "patch_applyDownloadStart",
        listOf(
            ImmutableMethodParameter(playerType, null, null),
            ImmutableMethodParameter(descriptorType, null, null)
        ),
        "V",
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(4),
    ).toMutable().apply {
        addInstructionsWithLabels(
            0,
            """
                if-eqz p1, :done
                iget-object v0, p1, $startTimeMs
                if-eqz v0, :done
                const/4 v1, 0x0
                iput-object v1, p1, $startTimeMs
                invoke-virtual { v0 }, Ljava/lang/Long;->longValue()J
                move-result-wide v0
                invoke-static { p0, v0, v1 }, ${setter.methodReference}
                :done
                return-void
            """
        )
    }

    // YouTube can replace the card's start time while applying the response. Use the position
    // carried by this request after YouTube chooses the start and before playback begins.
    val (_, _, _, playerRegister) = startPlayback.instruction.registersUsed
    val implementation = apply.method.implementation!!
    val contextRegister = apply.method.p0Register + 1
    // Reuse p1 only if the rest of the method cannot reach code that uses it.
    // A wide operand starting at p0 also occupies p1.
    check(
        implementation.tryBlocks.isEmpty() &&
            implementation.instructions.drop(startPlayback.index).none { instruction ->
                instruction.registersUsed.any { it in contextRegister - 1..contextRegister } ||
                        instruction.opcode == Opcode.PACKED_SWITCH ||
                        instruction.opcode == Opcode.SPARSE_SWITCH ||
                        (instruction is BuilderOffsetInstruction && instruction.target.location.index < startPlayback.index)
            }
    ) {
        "Downloads: LocalDirector must leave its context register unused before playback"
    }
    apply.classDef.methods.add(applyStartMethod)
    apply.method.addInstructionsAtControlFlowLabel(
        startPlayback.index,
        """
            move-object/from16 p1, v$playerRegister
            invoke-static/range { p1 .. p2 }, $applyStartMethod
        """
    )
}
