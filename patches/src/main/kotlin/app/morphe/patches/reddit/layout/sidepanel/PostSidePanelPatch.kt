/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4978
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.sidepanel

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/PostSidePanelPatch;"

@Suppress("unused")
val postSidePanelPatch = patch(
    name = "Post side panel",
    description = "On wide tablet screens, posts open in a panel on the right with the feed visible on the left."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        ScreenCreateViewFingerprint.let {
            val method = it.method
            val returnMatch = it.instructionMatches[3]
            val toolbarMatch = it.instructionMatches[2]

            val vScreen = toolbarMatch.instruction.registersUsed[0]
            val vView = returnMatch.instruction.registersUsed[0]

            val invokeViewCreatedSmali = if (vScreen <= 15 && vView <= 15) {
                "invoke-static { v$vScreen, v$vView }, $EXTENSION_CLASS->onScreenViewCreated(Ljava/lang/Object;Landroid/view/View;)V"
            } else if (vView == vScreen + 1) {
                "invoke-static/range { v$vScreen .. v$vView }, $EXTENSION_CLASS->onScreenViewCreated(Ljava/lang/Object;Landroid/view/View;)V"
            } else {
                throw PatchException("Registers cannot be passed to onScreenViewCreated: v$vScreen, v$vView")
            }

            method.replaceInstruction(returnMatch.index, invokeViewCreatedSmali)
            method.addInstruction(returnMatch.index + 1, "return-object v$vView")
        }

        val presentationGetterMatch = ScreenCreateViewFingerprint.instructionMatches[0]
        val presentationGetterName = presentationGetterMatch.methodReference.name
        val presentationGetterReturnType = presentationGetterMatch.methodReference.returnType

        val presentationGetterFingerprint = Fingerprint(
            definingClass = "Lcom/reddit/frontpage/presentation/listing/linkpager/refactor/PostDetailPagerScreen;",
            name = presentationGetterName,
            returnType = presentationGetterReturnType,
            parameters = listOf()
        )

        val modalClass = ModalPresentationFingerprint.instructionMatches.first().methodReference.definingClass

        val helperMethod = ImmutableMethod(
            presentationGetterFingerprint.classDef.type,
            "patch_createSidePanelPresentation",
            listOf(),
            presentationGetterReturnType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(4)
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    new-instance v0, $modalClass
                    const/4 v1, 0x1
                    const/4 v2, 0x0
                    const/16 v3, 0x1e
                    invoke-direct { v0, v1, v2, v2, v3 }, $modalClass-><init>(ZLkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function2;I)V
                    return-object v0
                """
            )
        }

        presentationGetterFingerprint.classDef.methods.add(helperMethod)

        presentationGetterFingerprint.method.cloneParameters().addInstructionsWithLabels(
            0,
            """
                invoke-static { p0 }, $EXTENSION_CLASS->usePanel(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :full_screen
                invoke-static { }, $helperMethod
                move-result-object v0
                return-object v0
                :full_screen
                nop
            """
        )

        val helperReference = NavigateToScreenFingerprint.instructionMatches.first().methodReference

        FeedCardOpenPostFingerprint.method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v1, p1
                move-object/from16 v2, p3
                invoke-static { v2 }, $EXTENSION_CLASS->usePanel(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :card_transition
                const/4 v0, 0x0
                invoke-static { v1, v2, v0 }, $helperReference
                return-void
                :card_transition
                nop
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
