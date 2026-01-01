package app.morphe.patches.shared.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

lateinit var lithoColorOverrideHook: (targetMethodClass: String, targetMethodName: String) -> Unit
    private set

val lithoColorHookPatch = bytecodePatch(
    description = "Adds a hook to set color of Litho components.",
) {

    execute {
        var insertionIndex = LithoOnBoundsChangeFingerprint.instructionMatches.last().index - 1

        lithoColorOverrideHook = { targetMethodClass, targetMethodName ->
            LithoOnBoundsChangeFingerprint.method.addInstructions(
                insertionIndex,
                """
                    invoke-static { p1 }, $targetMethodClass->$targetMethodName(I)I
                    move-result p1
                """
            )
            insertionIndex += 2
        }
    }
}
