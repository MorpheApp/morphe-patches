package app.morphe.patches.youtube.misc.fix.verticalscroll

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.playservice.is_21_18_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

val fixVerticalScrollPatch = bytecodePatch(
    description = "Fixes issues with refreshing the feed when the first component is of type EmptyComponent.",
) {

    dependsOn(versionCheckPatch)

    execute {
        if (is_21_18_or_greater) {
            // Can cause issues with scrolling.
            ChoreographerPostFrameCallbackFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    false
                )
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
