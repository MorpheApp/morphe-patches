package app.morphe.patches.youtube.misc.fix.verticalscroll

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.youtube.misc.playservice.is_21_18_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_33_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

val fixVerticalScrollPatch = patch(
    description = "Fixes issues with refreshing the feed when the first component is of type EmptyComponent, " +
            "stops the watch page from scrolling to the related videos when the video ends, " +
            "and makes the \"More videos\" button of the fullscreen player always open the related videos.",
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

        // The "More videos" button of the fullscreen player opens the related videos only if their panel peeks.
        // The panel can be left in another state, such as hidden while the time bar is scrubbed,
        // and the button then does nothing until the device is rotated. The panel is opened from any state.
        FullscreenMoreVideosButtonFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[2].index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                replaceInstruction(index, "const/4 v$register, 0x1")
            }
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
