/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4600
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.numberOfParameterRegistersLogical
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.shared.misc.bridge.addBridge
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val OFFLINE_PLAYBACK_WORKER_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/InAppDownloadsPatch$OfflinePlaybackWorker;"

/**
 * Playlist ID of the Downloads list, which a saved video played from Downloads is started with.
 */
internal const val DOWNLOADS_PLAYLIST_ID = "PPSV"

/**
 * Starts a saved video played from Downloads at the position saved in Downloads.
 *
 * This works around a YouTube bug that also happens without patches, including with Premium
 * downloads (https://github.com/MorpheApp/morphe-patches/issues/4568).
 *
 * YouTube saves the position of a saved video in its Downloads entry, and separately in its own
 * playback position record. The two can differ, and the play command of a Downloads card keeps
 * the position from when the card was built. Saved videos can then start at 0:00 or at an older
 * position. For example, watch a saved video from Downloads to 6:00, close it, open it again from
 * the same card and watch to 9:00, then close it and open it again: it starts at 6:00, not 9:00.
 *
 * How it works:
 * 1. When the offline worker has loaded the saved player response of a video played from
 *    Downloads, the position is read from the Downloads entry and converted to a start time with
 *    the same calculation a Downloads card uses. It's kept in a field added to the descriptor.
 * 2. When LocalDirector loads the video, the start time is set after YouTube has chosen its own
 *    start time and right before playback starts.
 *
 * Future notes:
 * - If YouTube fixes this, this hook is no longer needed. It's harmless if it stays, since it
 *   only sets the start time that YouTube intends to use. To check, patch without this hook
 *   (remove the [hookOfflineDownloadResume] call) and repeat the steps above. If the saved video
 *   then starts at the newest position in every supported version, remove this file, its
 *   fingerprints in Fingerprints.kt, and InAppDownloadsPatch.setDownloadStartTime() with the
 *   OfflinePlaybackWorker interface.
 * - If only newer versions are fixed, keep the hook and skip it for those versions with
 *   versionCheckPatch.
 * - If a fingerprint here stops matching in a new version, first check whether the bug is still
 *   there before fixing the fingerprint.
 */
internal fun hookOfflineDownloadResume() {
    val playerResponseModelType = OfflinePlayerResponseFingerprint.originalMethod.returnType
    val playerResponseModelClass = playerResponseModelFromBytesFingerprint(playerResponseModelType).classDef.type
    val descriptor = PlaybackStartDescriptorIdsFingerprint
    val descriptorType = descriptor.classDef.type
    val (_, videoIdGetter, _, playlistIdGetter) = descriptor.instructionMatches

    // The start time in milliseconds for the descriptor, or null to keep the start time chosen by YouTube.
    val startTimeMsField = ImmutableField(
        descriptorType,
        "patch_downloadStartTimeMs",
        "Ljava/lang/Long;",
        AccessFlags.PUBLIC.value or AccessFlags.VOLATILE.value,
        null,
        null,
        null
    ).toMutable()
    descriptor.classDef.fields.add(startTimeMsField)

    val worker = offlinePlaybackWorkerFingerprint(descriptorType)
    val entryGetter = offlinePlaybackEntryFingerprint(worker.classDef.type).method
    val savedPositionSeconds = offlineDownloadPositionCopyFingerprint(entryGetter.returnType)
        .instructionMatches[2].fieldReference
    val calculateStartTimeSeconds = OfflineDownloadCardCommandFingerprint.instructionMatches.first().methodReference
    val durationSecondsGetter = offlineDownloadDurationFingerprint(playerResponseModelClass)
        .instructionMatches[1].methodReference.name

    worker.classDef.interfaces.add(OFFLINE_PLAYBACK_WORKER_INTERFACE)
    worker.classDef.addBridge(
        "patch_setDownloadStartTime",
        listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
        "V",
        8,
        """
            check-cast p1, $descriptorType
            const/4 v0, 0x0
            iput-object v0, p1, $startTimeMsField
            if-eqz p2, :done

            invoke-virtual { p1 }, ${playlistIdGetter.methodReference}
            move-result-object v0
            const-string v1, "$DOWNLOADS_PLAYLIST_ID"
            invoke-virtual { v1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :done

            invoke-virtual { p1 }, ${videoIdGetter.methodReference}
            move-result-object v0
            invoke-static { v0 }, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z
            move-result v1
            if-nez v1, :done

            # Downloads entry of the current account, or null if the video is not saved.
            invoke-direct { p0, v0 }, $entryGetter
            move-result-object v0
            if-eqz v0, :done
            iget-wide v0, v0, $savedPositionSeconds
            const-wide/16 v2, 0x0
            cmp-long v4, v0, v2
            if-ltz v4, :done

            check-cast p2, $playerResponseModelType
            invoke-interface { p2 }, $playerResponseModelType->$durationSecondsGetter()I
            move-result v2
            if-lez v2, :done
            int-to-long v2, v2

            # Same calculation as a Downloads card, which starts at 0:00 near the end of the video.
            invoke-static { v2, v3, v0, v1 }, $calculateStartTimeSeconds
            move-result v0
            float-to-long v0, v0
            const-wide/16 v2, 0x3e8
            mul-long/2addr v0, v2
            invoke-static { v0, v1 }, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;
            move-result-object v0
            iput-object v0, p1, $startTimeMsField

            :done
            return-void
        """
    )

    // Read the Downloads entry when the worker has loaded the saved player response.
    worker.method.cloneParameters().apply {
        val success = worker.instructionMatches.first()
        val responseRegister = success.instruction.registersUsed[2]

        // The parameter registers of the cloned method are not used by the original code.
        addInstructionsAtControlFlowLabel(
            success.index + numberOfParameterRegistersLogical,
            """
                move-object/from16 p2, v$responseRegister
                invoke-static/range { p0 .. p2 }, $EXTENSION_IN_APP_DOWNLOADS_CLASS->setDownloadStartTime(${OFFLINE_PLAYBACK_WORKER_INTERFACE}Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )
    }

    applyDownloadStartTime(playerResponseModelType, descriptorType, startTimeMsField)
}

/**
 * YouTube can replace the start time of the play command while it loads the player response,
 * so the Downloads start time is set after YouTube sets its own and before playback starts.
 */
private fun applyDownloadStartTime(
    playerResponseModelType: String,
    descriptorType: String,
    startTimeMsField: FieldReference
) {
    val loadMethod = localDirectorLoadFingerprint(playerResponseModelType, descriptorType)
        .instructionMatches.first().getMethodCalled()
    val loadTargetType = loadMethod.parameterTypes.first().toString()
    val playerField = localDirectorLoadPlayerFingerprint(loadTargetType, playerResponseModelType)
        .match(loadMethod).instructionMatches.first().fieldReference
    val playerType = playerField.type
    val startPlayback = localDirectorStartPlaybackFingerprint(playerType).match(loadMethod)
    val (startTimeSetter, startPlaybackCall) = startPlayback.instructionMatches

    val helperMethod = ImmutableMethod(
        startPlayback.classDef.type,
        "patch_applyDownloadStartTime",
        listOf(
            ImmutableMethodParameter(loadTargetType, null, null),
            ImmutableMethodParameter(descriptorType, null, null)
        ),
        "V",
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(5),
    ).toMutable().apply {
        addInstructionsWithLabels(
            0,
            """
                if-eqz p1, :done
                iget-object v0, p1, $startTimeMsField
                if-eqz v0, :done

                # Use the start time once, and not again if the descriptor is loaded again.
                const/4 v1, 0x0
                iput-object v1, p1, $startTimeMsField

                iget-object v2, p0, $playerField
                invoke-virtual { v0 }, Ljava/lang/Long;->longValue()J
                move-result-wide v0
                invoke-static { v2, v0, v1 }, ${startTimeSetter.methodReference}

                :done
                return-void
            """
        )
    }
    startPlayback.classDef.methods.add(helperMethod)

    startPlayback.method.cloneParameters().apply {
        // p1 is the load target and p2 is the descriptor. The original code does not use the
        // parameter registers of the cloned method.
        addInstructionsAtControlFlowLabel(
            startPlaybackCall.index + numberOfParameterRegistersLogical,
            "invoke-static/range { p1 .. p2 }, $helperMethod"
        )
    }
}
