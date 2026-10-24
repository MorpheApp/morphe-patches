/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchSingle
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/** Keep native listeners and visibility, including controls that bypass the navigator for history. */
internal fun installPlaylistPlayerControls(
    context: BytecodePatchContext,
    navigatorType: String,
    enumType: String,
    wrapperType: String
) = with(context) {
    val availability = listOf("NEXT", "PREVIOUS").map { intent ->
        val enumField = playlistNavigationIntentFingerprint(enumType, intent).matchSingle()
            .instructionMatches.last().instruction.getReference<FieldReference>()!!
        val request = playlistNavigationSingletonFingerprint(wrapperType, enumField).matchSingle()
            .instructionMatches.last().instruction.getReference<FieldReference>()!!
        request to playlistControlAvailabilityFingerprint(navigatorType, request).matchSingle()
    }

    val controlsType = availability.first().second.originalClassDef.type
    check(availability.all { it.second.originalClassDef.type == controlsType }) {
        "Native playlist queue: Next/Previous must share their native controls owner"
    }

    val controls = mutableClassDefBy(controlsType)
    val navigatorField = availability.first().second.instructionMatches[4]
        .instruction.getReference<FieldReference>()!!

    controls.interfaces.add(PLAYER_CONTROLS_INTERFACE)
    controls.addBridgeMethod("patch_getNavigator", emptyList(), "Ljava/lang/Object;", 1, """
        iget-object v0, p0, $navigatorField
        return-object v0
    """)

    // Recompute both cached enablement values, even when native history delegates are present.
    controls.addBridgeMethod(
        "patch_refreshNavigation",
        emptyList(),
        "V",
        1,
        availability.joinToString("\n") { (_, match) -> "invoke-direct {p0}, ${match.originalMethod}" } +
                "\nreturn-void"
    )

    val constructor = playlistPlayerControlsConstructorFingerprint(controlsType, navigatorType).matchSingle().method
    constructor.findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
        constructor.addInstruction(
            index,
            "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->onPlayerControlsCreated($PLAYER_CONTROLS_INTERFACE)V"
        )
    }

    availability.forEachIndexed { direction, (request, match) ->
        val method = match.method
        val compare = match.instructionMatches.last().getInstruction<TwoRegisterInstruction>()
        val cachedMatch = match.instructionMatches[match.instructionMatches.lastIndex - 1]
        val cached = cachedMatch.getInstruction<TwoRegisterInstruction>()
        check(compare.registerA == cached.registerA || compare.registerB == cached.registerA) {
            "Native playlist queue: control availability must compare the cached and computed values"
        }

        val originalRegister = if (compare.registerA == cached.registerA) compare.registerB else compare.registerA
        val insertIndex = cachedMatch.index
        val freeRegister = method.findFreeRegister(insertIndex)
        method.addInstructionsAtControlFlowLabel(insertIndex, """
            const/4 v$freeRegister, ${if (direction == 0) 1 else 0}
            invoke-static {p0, v$freeRegister, v$originalRegister}, $EXTENSION_CLASS->playerControlAvailability(${PLAYER_CONTROLS_INTERFACE}ZZ)Z
            move-result v$originalRegister
        """)

        // The delegate read joins both paths of the optional native logging callback.
        // Intercept here so logging runs, but a local queue cannot fall into history/restart.
        val delegateField = match.instructionMatches.first().instruction.getReference<FieldReference>()!!
        var press = playlistControlPressFingerprint(controlsType, delegateField, request).matchSingle()
        if (press.method.implementation!!.registerCount <= 1) {
            press.method.cloneParameters()
            press = playlistControlPressFingerprint(controlsType, delegateField, request).matchSingle()
        }

        val pressIndex = press.instructionMatches[1].index
        val resultRegister = press.method.findFreeRegister(pressIndex)
        val callback = if (direction == 0) "onNextPressed" else "onPreviousPressed"
        press.method.addInstructionsAtControlFlowLabel(pressIndex, """
            invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->$callback($PLAYER_CONTROLS_INTERFACE)Z
            move-result v$resultRegister
            if-eqz v$resultRegister, :native_press
            return-void

            :native_press
            nop
        """)
    }
}
