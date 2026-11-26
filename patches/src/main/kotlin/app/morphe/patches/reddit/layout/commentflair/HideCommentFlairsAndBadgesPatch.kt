/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5298
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.commentflair

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HideCommentFlairsAndBadgesPatch;"

@Suppress("unused")
val hideCommentFlairsAndBadgesPatch = patch(
    name = "Hide comment flairs and badges",
    description = "Adds options to hide user flairs and community badges next to the authors of comments."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        CommentAuthorFlairTextFingerprint.method.filterReturnedObject(
            "getUserFlairText",
            "Ljava/lang/String;"
        )
        CommentAuthorFlairRichTextFingerprint.method.filterReturnedObject(
            "getUserFlairRichText",
            "Ljava/util/List;"
        )
        CommentAuthorAchievementsBadgeFingerprint.method.filterReturnedObject(
            "getCommunityBadge",
            "Ljava/lang/Object;",
            "Lcom/reddit/domain/model/AchievementBadge;"
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

private fun MutableMethod.filterReturnedObject(
    extensionMethod: String,
    type: String,
    castType: String? = null
) {
    findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
        val register = getInstruction<OneRegisterInstruction>(index).registerA
        val castInstruction = if (castType != null) "\ncheck-cast v$register, $castType" else ""

        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static { v$register }, $EXTENSION_CLASS->$extensionMethod($type)$type
                move-result-object v$register$castInstruction
            """
        )
    }
}
