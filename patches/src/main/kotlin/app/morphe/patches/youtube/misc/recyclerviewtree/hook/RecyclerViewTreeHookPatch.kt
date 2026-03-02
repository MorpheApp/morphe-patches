package app.morphe.patches.youtube.misc.recyclerviewtree.hook

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import java.lang.ref.WeakReference

private lateinit var addRecyclerViewTreeHookMethodRef : WeakReference<MutableMethod>
private var addRecyclerViewTreeHookInsertIndex = -1

fun addRecyclerViewTreeHook(classDescriptor: String) {
    val recyclerViewParameter = 2
    addRecyclerViewTreeHookMethodRef.get()!!.addInstruction(
        addRecyclerViewTreeHookInsertIndex,
        "invoke-static/range { p$recyclerViewParameter .. p$recyclerViewParameter }, " +
                "$classDescriptor->onFlyoutMenuCreate(Landroid/support/v7/widget/RecyclerView;)V",
    )
}


val recyclerViewTreeHookPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch)

    execute {
        RecyclerViewTreeObserverFingerprint.let {
            addRecyclerViewTreeHookMethodRef = WeakReference(it.method)
            addRecyclerViewTreeHookInsertIndex = it.instructionMatches.first().index + 1
        }
    }
}
