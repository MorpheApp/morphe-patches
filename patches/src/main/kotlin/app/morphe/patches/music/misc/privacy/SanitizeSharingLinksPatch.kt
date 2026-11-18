package app.morphe.patches.music.misc.privacy

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.privacy.sanitizeSharingLinksPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/patches/SanitizeSharingLinksPatch;"

val sanitizeSharingLinksPatch = sanitizeSharingLinksPatch(
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
        )

        compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)
    },
    executeBlock = {
        // System share sheet link, used without a network connection
        // or with the 'Open system share sheet' patch.
        ShareLinkWithSystemShareSheetFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[2].index
                val urlRegister = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$urlRegister }, $EXTENSION_CLASS->sanitize(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$urlRegister
                    """
                )
            }
        }
    },
    preferenceScreen = PreferenceScreen.MISC,
    replaceMusicLinksWithYouTube = true
)
