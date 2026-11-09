/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.downloads

import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.shared.misc.bridge.addBridge
import app.morphe.patches.youtube.video.information.PlaybackStartDescriptorToStringFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val EXTENSION_OFFLINE_DOWNLOAD_POSITION =
    "Lapp/morphe/extension/youtube/patches/OfflineDownloadPosition;"

internal fun hookOfflineDownloadResume(
    playerResponseModelType: String,
    playerResponseGetter: MethodReference,
    offlineStateField: FieldReference
) {
    val descriptorFingerprint = PlaybackStartDescriptorToStringFingerprint
    val descriptorType = descriptorFingerprint.classDef.type
    val (_, videoIdGetter, playlistIdGetter) = descriptorFingerprint.instructionMatches
    val refreshMethod = offlineDownloadLoadFingerprint(playerResponseModelType, descriptorType)
        .instructionMatches.first().getMethodCalled()
    val (contextPlayerMatch, playerResponseModelMatch) =
        offlineDownloadPlayerFingerprint(refreshMethod, playerResponseModelType).instructionMatches
    val playerType = contextPlayerMatch.fieldReference.type
    val playerResponseModelGetter = playerResponseModelMatch.methodReference
    val normalizePosition = OfflineDownloadCardPositionFingerprint.method
    val durationGetter = offlineDownloadDurationFingerprint(playerResponseGetter.definingClass)
        .instructionMatches[1].methodReference.name
    val databaseNameInstructions =
        offlineDownloadDatabaseNameInstructions(refreshMethod, playerResponseModelType, descriptorType)

    val applyFingerprint = offlineDownloadPositionApplyFingerprint(refreshMethod.cloneParameters(), playerType)
    val (setterMatch, applyMatch) = applyFingerprint.instructionMatches
    val directorType = refreshMethod.definingClass
    val methodName = "patch_applyOfflineDownloadPosition"
    mutableClassDefBy(directorType).addBridge(
        methodName,
        listOf(directorType, playerType, descriptorType),
        "V",
        9,
        """
            if-eqz p2, :skip
            invoke-virtual { p2 }, ${playlistIdGetter.methodReference}
            move-result-object v0
            const-string v1, "PPSV"
            invoke-virtual { v1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :skip

            invoke-interface { p1 }, $playerResponseModelGetter
            move-result-object v3
            if-eqz v3, :skip
            invoke-interface { v3 }, $playerResponseModelType->${playerResponseGetter.name}()${playerResponseGetter.returnType}
            move-result-object v2
            if-eqz v2, :skip
            iget-object v2, v2, $offlineStateField
            invoke-static { v2 }, $EXTENSION_OFFLINE_DOWNLOAD_POSITION->isPlayableDownload(Lcom/google/protobuf/MessageLite;)Z
            move-result v0
            if-eqz v0, :skip

            invoke-virtual { p2 }, ${videoIdGetter.methodReference}
            move-result-object v2
            if-eqz v2, :skip
            $databaseNameInstructions
            invoke-static { v0, v2 }, $EXTENSION_OFFLINE_DOWNLOAD_POSITION->getPlaybackPositionSeconds(Ljava/lang/String;Ljava/lang/String;)J
            move-result-wide v0
            const-wide/16 v4, 0x0
            cmp-long v2, v0, v4
            if-ltz v2, :skip

            invoke-interface { v3 }, $playerResponseModelType->$durationGetter()I
            move-result v4
            if-lez v4, :skip
            int-to-long v4, v4
            invoke-static { v4, v5, v0, v1 }, $normalizePosition
            move-result v0
            float-to-long v0, v0
            const-wide/16 v2, 0x3e8
            mul-long/2addr v0, v2
            invoke-static { p1, v0, v1 }, ${setterMatch.methodReference}

            :skip
            return-void
        """,
        AccessFlags.PRIVATE.value or AccessFlags.STATIC.value
    )

    // Use the player that receives the position. The descriptor can be cleared before this call.
    val playerRegister = applyMatch.instruction.registersUsed[3]
    applyFingerprint.method.addInstructionsAtControlFlowLabel(
        applyMatch.index,
        """
            move-object/from16 p1, v$playerRegister
            invoke-static/range { p0 .. p2 }, $directorType->$methodName($directorType$playerType$descriptorType)V
        """
    )
}
