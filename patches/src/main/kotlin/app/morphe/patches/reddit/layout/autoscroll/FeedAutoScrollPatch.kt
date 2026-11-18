/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4958
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.autoscroll

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.getFreeRegisterProvider
import app.morphe.patches.reddit.layout.tabletgrid.ListFeedFingerprint
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findFieldFromToString
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/FeedAutoScrollPatch;"
private const val FEED_CONTEXT_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/FeedAutoScrollPatch$FeedContext;"
private const val VISIBILITY_FLOW_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/FeedAutoScrollPatch$VisibilityFlow;"
private const val SCROLLABLE_STATE_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/FeedAutoScrollPatch$ScrollableState;"

@Suppress("unused")
val feedAutoScrollPatch = patch(
    name = "Feed auto-scroll",
    description = "Adds an option to slowly scroll the feed on screen automatically."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

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

        fun addScrollableState(stateType: String) {
            val dispatchRawDelta = Fingerprint(
                definingClass = stateType,
                returnType = "F",
                parameters = listOf("F")
            ).method

            mutableClassDefBy(stateType).addPatchInterface(
                SCROLLABLE_STATE_INTERFACE,
                "patch_dispatchRawDelta",
                dispatchRawDelta
            )
        }

        // List feed.
        ListFeedFingerprint.method.apply {
            addScrollableState(parameterTypes[3].toString())

            addInstructions(
                0,
                """
                    move-object/from16 v0, p1
                    move-object/from16 v1, p3
                    move-object/from16 v2, p5
                    invoke-static { v0, v1, v2 }, $EXTENSION_CLASS->onListFeed($FEED_CONTEXT_INTERFACE${SCROLLABLE_STATE_INTERFACE}Ljava/lang/Enum;)V
                """
            )
        }

        // Grid feed, used by search results and "Tablet grid feed".
        GridFeedFingerprint.let {
            addScrollableState(it.instructionMatches[0].methodReference.returnType)

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
                        invoke-static { v$feedContextRegister, v$gridStateRegister }, $EXTENSION_CLASS->onGridFeed($FEED_CONTEXT_INTERFACE$SCROLLABLE_STATE_INTERFACE)V
                    """
                )
            }
        }

        // endregion

        // region Button.

        MainActivityOnResumeFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS->onMainActivityResumed(Landroid/app/Activity;)V"
        )

        MainActivityOnPauseFingerprint.method.addInstruction(
            0,
            "invoke-static { }, $EXTENSION_CLASS->onMainActivityPaused()V"
        )

        NavBarStateChangeFingerprint.method.addInstruction(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS->onNavBarStateChange(F)V"
        )

        PostDetailPagerInflateFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[2].index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static/range { v$register .. v$register }, " +
                            "$EXTENSION_CLASS->onPostDetailPagerViewCreated(Landroid/view/View;)V"
                )
            }
        }

        // endregion

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
