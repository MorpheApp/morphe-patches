/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4958
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.feed

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.getFreeRegisterProvider
import app.morphe.patches.reddit.layout.tabletgrid.FEED_TYPE_CLASS
import app.morphe.patches.reddit.layout.tabletgrid.LatestFeedScreenFingerprint
import app.morphe.patches.reddit.misc.extension.sharedExtensionPatch
import app.morphe.util.findFieldFromToString
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/FeedHookPatch;"
private const val FEED_CONTEXT_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/FeedHookPatch$FeedContext;"
private const val VISIBILITY_FLOW_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/FeedHookPatch$VisibilityFlow;"

/**
 * Compose LazyListState type of the list feed.
 */
internal lateinit var listFeedStateType: String
    private set

/**
 * Compose LazyStaggeredGridState type of the grid feed.
 */
internal lateinit var gridFeedStateType: String
    private set

/**
 * Tracks the feeds and their scroll states, to find the feed on screen.
 */
internal val feedHookPatch = patch {
    dependsOn(sharedExtensionPatch)

    execute {
        // region Feed visibility.

        val flowField = FeedContextToStringFingerprint.method
            .findFieldFromToString(", feedVisibilityFlow=")

        FeedContextToStringFingerprint.classDef.addPatchInterface(
            FEED_CONTEXT_INTERFACE,
            "patch_getFeedVisibilityFlow",
            flowField
        )

        // StateFlow interface.
        mutableClassDefBy(flowField.type).addPatchInterface(
            VISIBILITY_FLOW_INTERFACE,
            "patch_getValue",
            """
                invoke-interface { p0 }, ${flowField.type}->getValue()Ljava/lang/Object;
                move-result-object v0
                return-object v0
            """
        )

        FeedVisibilityEventFingerprint.method.addInstruction(
            0,
            "invoke-static { }, $EXTENSION_CLASS->onFeedVisibilityChanged()V"
        )

        // endregion

        // region Feed scroll states.

        // List feed.
        LatestFeedScreenFingerprint.instructionMatches[1].getMethodCalled().apply {
            // Some app versions sort the list feed parameters by type,
            // so the arguments are found by type and not by position.
            val listParameters = parameterTypes.map { it.toString() }
            val feedContextIndex = listParameters.indexOf(FeedContextToStringFingerprint.classDef.type)
            val lazyStateIndex = listParameters.indexOfFirst {
                it.startsWith("Landroidx/compose/foundation/lazy/")
            }
            val feedTypeIndex = listParameters.indexOf(FEED_TYPE_CLASS)

            listFeedStateType = listParameters[lazyStateIndex]

            addInstructions(
                0,
                """
                    move-object/from16 v0, p$feedContextIndex
                    move-object/from16 v1, p$lazyStateIndex
                    move-object/from16 v2, p$feedTypeIndex
                    invoke-static { v0, v1, v2 }, $EXTENSION_CLASS->onListFeed(${FEED_CONTEXT_INTERFACE}Ljava/lang/Object;Ljava/lang/Enum;)V
                """
            )
        }

        // Grid feed, used by search results and "Tablet grid feed".
        GridFeedFingerprint.let {
            gridFeedStateType = it.instructionMatches[0].methodReference.returnType

            it.method.apply {
                val index = it.instructionMatches[1].index
                val stateRegister = getInstruction<OneRegisterInstruction>(index).registerA
                val registers = getFreeRegisterProvider(index + 1, 2, stateRegister)
                val feedContextRegister = registers.getFreeRegister()
                val gridStateRegister = registers.getFreeRegister()

                addInstructions(
                    index + 1,
                    """
                        move-object/from16 v$feedContextRegister, p1
                        move-object/from16 v$gridStateRegister, v$stateRegister
                        invoke-static { v$feedContextRegister, v$gridStateRegister }, $EXTENSION_CLASS->onGridFeed(${FEED_CONTEXT_INTERFACE}Ljava/lang/Object;)V
                    """
                )
            }
        }

        // endregion
    }
}
