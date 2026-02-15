package app.morphe.patches.reddit.layout.screenshotpopup

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/reddit/patches/DisableScreenshotPopupPatch;"

@Suppress("unused")
val disableScreenshotPopupPatch = bytecodePatch(
    name = "Disable screenshot popup",
    description = "Adds an option to disable the popup that appears when taking a screenshot."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {

        listOf(
            RedditScreenshotTriggerSharingListenerFingerprint,
            ScreenshotTakenBannerFingerprint
        ).forEach { fingerprint ->
            fingerprint.let {
                it.method.apply {
                    val booleanIndex = it.instructionMatches[1].index
                    val booleanRegister =
                        getInstruction<OneRegisterInstruction>(booleanIndex).registerA

                    addInstructions(
                        booleanIndex + 1,
                        """
                            invoke-static { v$booleanRegister }, $EXTENSION_CLASS_DESCRIPTOR->disableScreenshotPopup(Ljava/lang/Boolean;)Ljava/lang/Boolean;
                            move-result-object v$booleanRegister
                            """
                    )
                }
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS_DESCRIPTOR)
    }
}
