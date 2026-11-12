package app.morphe.patches.youtube.misc.fix.verticalscroll

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.youtube.misc.playservice.is_21_18_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_33_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

val fixVerticalScrollPatch = patch(
    description = "Fixes issues with refreshing the feed when the first component is of type EmptyComponent, " +
            "and stops the watch page from scrolling to the related videos when the video ends.",
) {

    dependsOn(versionCheckPatch)

    execute {
        if (is_21_18_or_greater) {
            // Can cause issues with scrolling.
            ChoreographerPostFrameCallbackFeatureFlagFingerprint.matchAll().forEach {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    false
                )
            }
        }

        if (is_21_33_or_greater) {
            // A/B test: when the video ends, the list below the player scrolls down to the
            // related videos and hides the title, the action buttons and the comments.
            ScrollToRelatedItemsOnVideoEndFingerprint.method.addInstruction(0, "return-void")
        }

        CanScrollVerticallyFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "const/4 v$register, 0x0",
                )
            }
        }
    }
}
