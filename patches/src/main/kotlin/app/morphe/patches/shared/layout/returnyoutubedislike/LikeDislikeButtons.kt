/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3075
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.layout.returnyoutubedislike

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

/**
 * Draws the like and dislike counts over the action bar buttons, which YouTube and YouTube Music
 * lay out with the same Litho components.
 *
 * @param extensionClass The class with onComponentHostContentDescription and onYogaSetWidth.
 */
internal fun BytecodePatchContext.hookLikeDislikeButtons(extensionClass: String) {
    // Verify stubbed classes are not obfuscated.
    classDefBy("Lcom/facebook/litho/ComponentHost;")
    classDefBy("Lcom/facebook/litho/TextContent;")
    classDefBy("Lcom/facebook/yoga/YogaNative;")

    // The compact action bar has icon only like and dislike buttons with no text to hook,
    // so the counts are drawn over the button host views, which are found by their view tag.
    ComponentHostSetContentDescriptionFingerprint.method.addInstructions(
        0,
        """
            invoke-static { p0, p1 }, $extensionClass->onComponentHostContentDescription(Lcom/facebook/litho/ComponentHost;Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
            move-result-object p1
        """
    )

    // The dislike icon is given a margin so the button wrapping it grows and leaves room for
    // the count. A width is no use, since the layout stretches the icon over whatever it gets.
    //
    // The only long of the node is the pointer to its native counterpart.

    // The method reads the same pointer itself, so it always has the two locals this needs.
    YogaSetWidthFingerprint.let {
        val register = it.instructionMatches.last().getInstruction<FiveRegisterInstruction>().registerC
        it.method.addInstruction(
            it.instructionMatches.last().index,
            "invoke-static { v$register, v${register + 1}, p1 }, $extensionClass->onYogaSetWidth(JF)V"
        )
    }
}
