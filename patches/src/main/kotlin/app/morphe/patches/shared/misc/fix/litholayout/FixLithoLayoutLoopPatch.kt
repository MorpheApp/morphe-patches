/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5331
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.fix.litholayout

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchDefinition
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/components/LithoFilterPatch;"

/**
 * Litho calculates the layouts on a pool of 1 to 3 layout threads. More than one thread causes layout issues in
 * unpatched YouTube, such as the watch and playlist shelves of the 'You' tab that are sometimes hidden.
 * Some A/B tests of the Litho configuration also make the layouts run on the wrong thread, move between threads,
 * or calculate the layouts of list items again in a loop. A loop keeps a layout thread and the main thread busy
 * until the app is closed, also while the app is in the background, which uses much battery.
 * The tests are kept off, as for the users who are not in them.
 *
 * @param fixLayoutConfiguration Whether the A/B flags of the Litho configuration, the state update loops and the map
 *                               of the heights of the list items are fixed. Only checked in YouTube.
 * @param overrideMeasureItemsFlag Whether the app has the A/B flag that measures the list items after each layout.
 * @param forceRemoveReleasedItemHeightsFlag Whether the app has the A/B flag that removes the heights of the
 *                                           released list items.
 * @param forceDetachOnlyOwnTreeFlag Whether the app has the A/B flag that detaches the old tree of a Litho view
 *                                   only if the tree is still in the view.
 * @param block Callback to add app-specific dependencies (sharedExtensionPatch, versionCheckPatch).
 */
internal fun sharedFixLithoLayoutLoopPatch(
    fixLayoutConfiguration: Boolean,
    overrideMeasureItemsFlag: () -> Boolean,
    forceRemoveReleasedItemHeightsFlag: () -> Boolean,
    forceDetachOnlyOwnTreeFlag: () -> Boolean,
    block: PatchDefinition.() -> Unit,
): Patch = patch(
    description = "Fixes the high CPU usage of Litho layouts that are calculated again in a loop, " +
            "and the layout issues of more than one layout thread."
) {
    block()

    execute {
        LithoThreadExecutorFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->getExecutorCorePoolSize(I)I
                move-result p1
                invoke-static { p2 }, $EXTENSION_CLASS->getExecutorMaxThreads(I)I
                move-result p2
            """
        )

        if (!fixLayoutConfiguration) return@execute

        LithoConfigurationFeatureFlagsFingerprint.method.apply {
            insertLiteralOverride(LAYOUTS_ON_MAIN_THREAD_FLAG, false)
            insertLiteralOverride(INTERRUPTIBLE_LAYOUTS_FLAG, false)

            if (overrideMeasureItemsFlag()) {
                insertLiteralOverride(MEASURE_ITEMS_AFTER_EACH_LAYOUT_FLAG, false)
            }

            // The fixes of these A/B tests are always on, as the server can turn them off.
            if (forceRemoveReleasedItemHeightsFlag()) {
                insertLiteralOverride(REMOVE_RELEASED_ITEM_HEIGHTS_FLAG, true)
            }
            if (forceDetachOnlyOwnTreeFlag()) {
                insertLiteralOverride(DETACH_ONLY_OWN_TREE_FLAG, true)
            }
        }

        // A component that dispatches a state update each time it's laid out, such as from its render,
        // makes the layouts loop. Litho reports the 50th state update of a layout as a loop, which is an
        // exception only in its debug builds, and the layouts go on forever. The later state updates of
        // the loop are not laid out. They are still queued, and the next layout that is not started by
        // a state update applies them and starts counting again.
        ComponentTreeUpdateStateFingerprint.let {
            it.method.apply {
                val countRegister = getInstruction<TwoRegisterInstruction>(
                    it.instructionMatches.first().index
                ).registerA
                val limitIndex = it.instructionMatches[1].index
                val limitRegister = getInstruction<OneRegisterInstruction>(limitIndex).registerA

                // The method holds the lock of the tree here, as its other early returns do.
                addInstructionsWithLabels(
                    limitIndex,
                    """
                        const/16 v$limitRegister, $MAX_STATE_UPDATES_DURING_LAYOUT
                        if-le v$countRegister, v$limitRegister, :lay_out
                        monitor-exit p0
                        return-void
                        :lay_out
                        nop
                    """
                )
            }
        }

        // The measure listeners of the list items change the map of the item heights on the layout threads,
        // and the list changes it on the main thread, partly without a lock. A hash map changed by two threads
        // at the same time can break, and a read or a change of a broken map can loop forever on a layout thread.
        // Newer versions create a concurrent map only for the users of an A/B test, so it's always created.
        RecyclerBinderConstructorFingerprint.let {
            it.method.apply {
                val initIndex = it.instructionMatches.first().index
                val register = getInstruction<FiveRegisterInstruction>(initIndex).registerC
                val newInstanceIndex = indexOfFirstInstructionReversedOrThrow(initIndex) {
                    opcode == Opcode.NEW_INSTANCE &&
                            getReference<TypeReference>()?.type == "Ljava/util/HashMap;"
                }
                if (getInstruction<OneRegisterInstruction>(newInstanceIndex).registerA != register) {
                    throw PatchException("Unexpected register of the item heights map")
                }

                replaceInstruction(
                    newInstanceIndex,
                    "new-instance v$register, Ljava/util/concurrent/ConcurrentHashMap;"
                )
                replaceInstruction(
                    initIndex,
                    "invoke-direct { v$register }, Ljava/util/concurrent/ConcurrentHashMap;-><init>()V"
                )
            }
        }
    }
}
