package app.morphe.patches.youtube.misc.recyclerviewtree.hook

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch

lateinit var addRecyclerViewTreeHook: (String) -> Unit
    private set

val recyclerViewTreeHookPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch)

    execute {
        RecyclerViewTreeObserverFingerprint.method.apply {
            val insertIndex = RecyclerViewTreeObserverFingerprint.instructionMatches.first().index + 1
            val recyclerViewParameter = 2

            addRecyclerViewTreeHook = { classDescriptor ->
                addInstruction(
                    insertIndex,
                    "invoke-static/range { p$recyclerViewParameter .. p$recyclerViewParameter }, " +
                        "$classDescriptor->onFlyoutMenuCreate(Landroid/support/v7/widget/RecyclerView;)V",
                )
            }
        }
    }
}
