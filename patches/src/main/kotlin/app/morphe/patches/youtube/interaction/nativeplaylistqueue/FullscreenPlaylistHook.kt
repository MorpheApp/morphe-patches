/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchSingle
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal fun installFullscreenPlaylist(context: BytecodePatchContext) = with(context) {
    val container = QuickActionsContainerFingerprint.matchSingle()
        .instructionMatches.last().instruction.getReference<FieldReference>()!!
    val controllerType = container.definingClass
    val callback = quickActionsModelCallbackFingerprint(controllerType).matchSingle()
    val setter = callback.instructionMatches.last().getInstruction<Instruction>()
        .getReference<MethodReference>()!!
    val modelType = setter.parameterTypes.single().toString()
    val reset = quickActionsResetFingerprint(controllerType, setter).matchSingle()
    val size = quickActionsSizeFingerprint(controllerType).matchSingle()
    val dimensions = size.instructionMatches[1].getInstruction<Instruction>().getReference<FieldReference>()!!
    val emit = size.instructionMatches[2].getInstruction<Instruction>().getReference<MethodReference>()!!

    // The native BehaviorSubject owns the last measured dimensions. Re-emitting them
    // runs YouTube's original measuring/binding callback, including its lifecycle guards.
    val currentDimensions = Fingerprint(
        definingClass = dimensions.type,
        parameters = emptyList(),
        returnType = "Ljava/lang/Object;",
        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL)
    ).matchSingle().originalMethod
    val controller = mutableClassDefBy(controllerType)
    controller.interfaces.add(FULLSCREEN_CONTROLLER_INTERFACE)
    controller.addBridgeMethod("patch_parseQuickActions", listOf("[B"), "Ljava/lang/Object;", 2, """
        ${parseProtoInstructions(modelType, bytes = "p1", result = "v0", registry = "v1")}
        return-object v0
    """)

    controller.addBridgeMethod("patch_refreshQuickActions", listOf("Ljava/lang/Object;"), "V", 2, """
        const/4 v0, 0x0
        # The native reset also releases the presenter's cached component tree.
        # Merely resetting the rendered flag reuses the previous button layout.
        # Its lifecycle argument is unused; null is safe for this UI-only reset.
        invoke-virtual {p0, v0}, ${reset.originalMethod}
        check-cast p1, $modelType
        invoke-virtual {p0, p1}, $setter
        iget-object v0, p0, $dimensions
        invoke-virtual {v0}, $currentDimensions
        move-result-object v1
        if-eqz v1, :done
        invoke-virtual {v0, v1}, $emit

        :done
        return-void
    """)

    // The setter's original null handling, visibility policy and other action bindings
    // all remain in place. Only our local queue can supply the additional button.
    Fingerprint(definingClass = controllerType, name = setter.name,
        parameters = listOf(modelType), returnType = "V").matchSingle().method.addInstructionsWithLabels(0, """
        invoke-static/range {p0 .. p1}, $FULLSCREEN_ACTIONS_CLASS->onModelLoaded(${FULLSCREEN_CONTROLLER_INTERFACE}Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object p1
        check-cast p1, $modelType
    """)
}
