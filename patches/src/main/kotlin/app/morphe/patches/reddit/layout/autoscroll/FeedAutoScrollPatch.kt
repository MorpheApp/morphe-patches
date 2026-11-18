/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4958
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.autoscroll

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.layout.tabletgrid.ListFeedFingerprint
import app.morphe.patches.reddit.layout.tabletgrid.SearchGridFeedFingerprint
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findFieldFromToString
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/FeedAutoScrollPatch;"

@Suppress("unused")
val feedAutoScrollPatch = patch(
    name = "Feed auto-scroll",
    description = "Adds an option to slowly scroll the feed on screen automatically."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        val feedContextType = FeedContextToStringFingerprint.classDef.type
        val flowField = FeedContextToStringFingerprint.method
            .findFieldFromToString(", feedVisibilityFlow=")
        val flowType = flowField.type

        val listFeedMethod = ListFeedFingerprint.method
        val listParameters = listFeedMethod.parameterTypes.map { it.toString() }
        if (listParameters.getOrNull(1) != feedContextType) {
            throw PatchException("ListFeed parameter 1 is not FeedContext: ${listParameters.getOrNull(1)}")
        }
        val listStateType = listParameters[3]

        listFeedMethod.addInstructions(
            0,
            """
                move-object/from16 v0, p1
                move-object/from16 v1, p3
                move-object/from16 v2, p5
                invoke-static { v0, v1, v2 }, $EXTENSION_CLASS->onListFeed(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )

        val masonryClass = classDefBy(
            SearchGridFeedFingerprint.instructionMatches[1]
                .methodReference.definingClass
        )
        val masonryMatch = MasonryGridStateFingerprint.match(masonryClass)
        val masonryParameters = masonryMatch.method.parameterTypes.map { it.toString() }
        if (masonryParameters.getOrNull(1) != feedContextType) {
            throw PatchException("Masonry parameter 1 is not FeedContext: ${masonryParameters.getOrNull(1)}")
        }

        val stateCallMatch = masonryMatch.instructionMatches.first()
        val gridStateType = stateCallMatch.methodReference.returnType

        val nextInstruction = masonryMatch.method.getInstruction(stateCallMatch.index + 1)
        if (nextInstruction.opcode != Opcode.MOVE_RESULT_OBJECT) {
            throw PatchException(
                "Expected MOVE_RESULT_OBJECT after rememberLazyStaggeredGridState call, got ${nextInstruction.opcode}"
            )
        }
        val stateRegister = (nextInstruction as OneRegisterInstruction).registerA

        masonryMatch.method.apply {
            addInstructions(
                stateCallMatch.index + 2,
                """
                    invoke-static/range { v$stateRegister .. v$stateRegister }, $EXTENSION_CLASS->onGridState(Ljava/lang/Object;)V
                """
            )

            addInstructions(
                0,
                """
                    move-object/from16 v0, p1
                    invoke-static { v0 }, $EXTENSION_CLASS->onGridFeedContext(Ljava/lang/Object;)V
                """
            )
        }

        FeedVisibilityEventFingerprint.method.addInstructions(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS->onFeedVisibilityChanged()V
            """
        )

        MainActivityOnResumeFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0 }, $EXTENSION_CLASS->onMainActivityResumed(Landroid/app/Activity;)V
            """
        )

        MainActivityOnPauseFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0 }, $EXTENSION_CLASS->onMainActivityPaused(Landroid/app/Activity;)V
            """
        )

        NavBarStateChangeFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->onNavBarFraction(F)V
            """
        )

        val inflateMatch = PostPagerInflateFingerprint.instructionMatches[1]
        val inflateResultInstruction = PostPagerInflateFingerprint.method.getInstruction(inflateMatch.index + 1)
        if (inflateResultInstruction.opcode != Opcode.MOVE_RESULT_OBJECT) {
            throw PatchException(
                "Expected MOVE_RESULT_OBJECT after fragment_pager inflate call, got ${inflateResultInstruction.opcode}"
            )
        }
        val postViewRegister = (inflateResultInstruction as OneRegisterInstruction).registerA

        PostPagerInflateFingerprint.method.addInstructions(
            inflateMatch.index + 2,
            """
                invoke-static/range { v$postViewRegister .. v$postViewRegister }, $EXTENSION_CLASS->onPostPageViewCreated(Landroid/view/View;)V
            """
        )

        val listDispatchName = Fingerprint(
            definingClass = listStateType,
            returnType = "F",
            parameters = listOf("F")
        ).method.name

        val gridDispatchName = Fingerprint(
            definingClass = gridStateType,
            returnType = "F",
            parameters = listOf("F")
        ).method.name

        Fingerprint(
            definingClass = EXTENSION_CLASS,
            name = "getVisibilityFlow"
        ).method.addInstructions(
            0,
            """
                check-cast p0, $feedContextType
                iget-object p0, p0, $flowField
                return-object p0
            """
        )

        Fingerprint(
            definingClass = EXTENSION_CLASS,
            name = "getFlowValue"
        ).method.addInstructions(
            0,
            """
                check-cast p0, $flowType
                invoke-interface { p0 }, $flowType->getValue()Ljava/lang/Object;
                move-result-object p0
                return-object p0
            """
        )

        Fingerprint(
            definingClass = EXTENSION_CLASS,
            name = "dispatchListScroll"
        ).method.addInstructions(
            0,
            """
                check-cast p0, $listStateType
                invoke-virtual { p0, p1 }, $listStateType->$listDispatchName(F)F
                move-result p0
                return p0
            """
        )

        Fingerprint(
            definingClass = EXTENSION_CLASS,
            name = "dispatchGridScroll"
        ).method.addInstructions(
            0,
            """
                check-cast p0, $gridStateType
                invoke-virtual { p0, p1 }, $gridStateType->$gridDispatchName(F)F
                move-result p0
                return p0
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
