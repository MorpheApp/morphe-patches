/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5331
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.litholayout

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_51_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_23_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_28_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/FixLithoLayoutLoopPatch;"

/**
 * Some A/B tests of the Litho configuration make the layouts run on the wrong thread, move between threads,
 * or calculate the layouts of list items again in a loop. A loop keeps a layout thread and the main thread busy
 * until the app is closed, also while the app is in the background, which uses much battery.
 * The tests are kept off, as for the users who are not in them.
 */
val fixLithoLayoutLoopPatch = patch(
    description = "Fixes the high CPU usage of Litho layouts that are calculated again in a loop."
) {
    dependsOn(
        sharedExtensionPatch,
        versionCheckPatch
    )

    execute {
        LithoConfigurationFeatureFlagsFingerprint.method.apply {
            insertLiteralOverride(LAYOUTS_ON_MAIN_THREAD_FLAG, false)
            insertLiteralOverride(INTERRUPTIBLE_LAYOUTS_FLAG, false)

            if (is_21_28_or_greater) {
                insertLiteralOverride(MEASURE_ITEMS_AFTER_EACH_LAYOUT_FLAG, false)
            }

            // The fixes of these A/B tests are always on, as the server can turn them off.
            if (is_21_23_or_greater) {
                insertLiteralOverride(REMOVE_RELEASED_ITEM_HEIGHTS_FLAG, true)
            }
            if (is_20_51_or_greater) {
                insertLiteralOverride(DETACH_ONLY_OWN_TREE_FLAG, true)
            }
        }

        // A component that dispatches a state update each time it's laid out, such as from its render,
        // makes the layouts loop. Litho reports the 50th state update of a layout as a loop, which is an
        // exception only in its debug builds, and the layouts go on. The report is only logged,
        // as a component tree whose later state updates are not laid out can keep showing an old layout.
        ComponentTreeUpdateStateFingerprint.let {
            it.method.addInstruction(
                it.instructionMatches.first().index,
                "invoke-static/range { p2 .. p2 }, $EXTENSION_CLASS->onStateUpdateLoop(Ljava/lang/String;)V"
            )
        }

        // The measure listeners of the list items change the map of the item heights on the layout threads,
        // and the list changes it on the main thread, partly without a lock. A hash map changed by two threads
        // at the same time can break, and a read or a change of a broken map can loop forever on a layout thread.
        // Newer versions create a concurrent map only for the users of an A/B test, so it's always created.
        RecyclerBinderConstructorFingerprint.let {
            it.method.apply {
                val newInstanceIndex = it.instructionMatches.first().index
                val register = getInstruction<OneRegisterInstruction>(newInstanceIndex).registerA

                replaceInstruction(
                    newInstanceIndex,
                    "new-instance v$register, Ljava/util/concurrent/ConcurrentHashMap;"
                )
                replaceInstruction(
                    it.instructionMatches[1].index,
                    "invoke-direct { v$register }, Ljava/util/concurrent/ConcurrentHashMap;-><init>()V"
                )
            }
        }

        // The measure listener of a list item checks if the map has the height of the item, and then gets it.
        // The main thread can remove the height in between without a lock, such as when the item is released,
        // and the listener does not check for null. An item whose height was removed meanwhile is handled
        // the same as an item without a height. The A/B test of the concurrent map checks for null instead.
        ItemMeasureListenerFingerprint.let {
            it.method.apply {
                val noHeightTarget = getInstruction<BuilderInstruction21t>(
                    it.instructionMatches[2].index
                ).target.location.instruction!!
                val castIndex = it.instructionMatches.last().index
                val heightRegister = getInstruction<OneRegisterInstruction>(castIndex).registerA

                addInstructionsWithLabels(
                    castIndex + 1,
                    "if-eqz v$heightRegister, :no_height",
                    ExternalLabel("no_height", noHeightTarget)
                )
            }
        }
    }
}
