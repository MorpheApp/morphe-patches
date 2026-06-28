package app.morphe.patches.youtube.misc.links

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/BypassLinkRedirectsPatch;"

@Suppress("unused")
val bypassLinkRedirectsPatch = bytecodePatch(
    name = "Bypass link redirects",
    description = "Adds an option to bypass redirects and open the original link directly.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.MISC.addPreferences(
            SwitchPreference("morphe_bypass_link_redirects", summary = true),
        )

        fun patchLogic(instructionRegister: String): String {
            return """
                invoke-static { $instructionRegister }, $EXTENSION_CLASS->parseRedirectUri(Landroid/net/Uri;)Landroid/net/Uri;
                move-result-object $instructionRegister
            """
        }

        // Override URI for video comments
        VideoCommentsUriFingerprint.apply {
            val instructionIndex = instructionMatches.last().index
            val instructionRegister = method.getInstruction<BuilderInstruction35c>(instructionIndex).registerE

            method.addInstructions(
                instructionIndex,
                patchLogic("v$instructionRegister"),
            )
        }

        // Override URI for video descriptions and community posts
        DescriptionAndPostUriFingerprint.method.addInstructions(
            0,
            patchLogic("p1"),
        )
    }
}
