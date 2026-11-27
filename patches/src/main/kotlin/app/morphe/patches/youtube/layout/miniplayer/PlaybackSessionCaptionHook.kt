/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/issues/4863
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.miniplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patches.youtube.layout.captions.CaptionTrackIsDisableOptionFingerprint
import app.morphe.patches.youtube.layout.captions.DefaultCaptionTrackFingerprint
import app.morphe.patches.youtube.layout.captions.SubtitleManagerSelectedTrackFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val SESSION = "Lapp/morphe/extension/youtube/patches/playback/PlaybackSessionPatch;"
private const val CAPTIONS = $$"Lapp/morphe/extension/youtube/patches/playback/PlaybackSessionPatch$CaptionSource;"

/** Restores the CC choice before the native subtitle manager selects its default track. */
internal fun installPlaybackSessionCaptionHooks(): Boolean {
    val defaultTrack = DefaultCaptionTrackFingerprint.matchOrNull()?.originalMethod ?: return false
    val managerType = defaultTrack.definingClass
    val trackType = defaultTrack.returnType
    val isDisabled = CaptionTrackIsDisableOptionFingerprint.matchOrNull()?.originalMethod ?: return false
    if (isDisabled.definingClass != trackType) return false

    val selection = SubtitleManagerSelectedTrackFingerprint(trackType).matchOrNull() ?: return false
    val selectionMethod = selection.originalMethod
    val eventType = selectionMethod.parameterTypes.single().toString()
    val selectedTrack = selection.instructionMatches.last().fieldReference
    val videoId = Fingerprint(
        definingClass = managerType,
        name = selectionMethod.name,
        parameters = listOf(eventType),
        returnType = "V",
        filters = listOf(
            methodCall(definingClass = "this", parameters = emptyList(), returnType = "Ljava/lang/String;"),
            opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
            methodCall(definingClass = eventType, name = "<init>", location = MatchAfterImmediately()),
        ),
    ).matchOrNull()?.instructionMatches?.first()?.getMethodCalled() ?: return false

    val captionGate = Fingerprint(
        definingClass = managerType,
        returnType = "V",
        filters = listOf(
            opcode(Opcode.IF_EQZ),
            newInstance(eventType, location = MatchAfterImmediately()),
            methodCall(reference = { defaultTrack }, location = MatchAfterImmediately()),
        ),
    ).matchOrNull() ?: return false
    // Segmented captions load their tracks from the playback manifest in the same initializer.
    val manifestGate = Fingerprint(
        definingClass = managerType,
        name = captionGate.originalMethod.name,
        parameters = captionGate.originalMethod.parameterTypes.map { it.toString() },
        returnType = "V",
        filters = listOf(
            opcode(Opcode.IF_EQZ),
            newInstance(eventType, location = MatchAfterImmediately()),
            methodCall(definingClass = "this", parameters = emptyList(), returnType = "Ljava/util/List;",
                location = MatchAfterImmediately()),
        ),
    ).matchOrNull()
    val gates = listOfNotNull(captionGate, manifestGate).map { gate ->
        val branch = gate.instructionMatches.first()
        val enabled = branch.getInstruction<OneRegisterInstruction>().registerA
        val manager = gate.instructionMatches.last().getInstruction<FiveRegisterInstruction>().registerC
        if (enabled > 15 || manager > 15) return false
        Triple(branch.index, enabled, manager)
    }.sortedByDescending { it.first }

    selection.classDef.apply {
        addPatchInterface(CAPTIONS, "patch_sessionCaptionVideoId", videoId)
        addPatchInterface(
            CAPTIONS,
            "patch_sessionCaptionsEnabled",
            """
                iget-object v0, p0, $selectedTrack
                if-eqz v0, :disabled
                invoke-virtual {v0}, $isDisabled
                move-result v0
                xor-int/lit8 v0, v0, 0x1
                return v0
                :disabled
                const/4 v0, 0x0
                return v0
            """,
        )
    }
    selection.method.addInstruction(0, "invoke-static/range {p0 .. p0}, $SESSION->attachCaptions($CAPTIONS)V")
    gates.forEach { (index, enabled, manager) ->
        captionGate.method.addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static {v$enabled, v$manager}, $SESSION->restoreCaptionPreference(Z$CAPTIONS)Z
                move-result v$enabled
            """,
        )
    }
    return true
}
