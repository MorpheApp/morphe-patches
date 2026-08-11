package app.morphe.patches.reddit.misc.update

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT

@Suppress("unused")
val disableAppUpdatePatch = bytecodePatch(
    name = "Disable app update",
    description = "Disables the Reddit forced in-app update popup."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    execute {
        val targetClass = GooglePlayUpdateCheckFingerprint.classDef

        val getStateMethod = targetClass.methods.firstOrNull { method ->
            method.name == "c" && method.returnType == "Ljava/lang/Object;"
        } ?: throw PatchException("Could not find update check method in GooglePlayImmediateUpdateCheck")

        getStateMethod.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """
        )
    }
}
