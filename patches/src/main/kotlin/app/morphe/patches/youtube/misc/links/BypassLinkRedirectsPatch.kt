package app.morphe.patches.youtube.misc.links

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_37_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_49_or_greater
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

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

        arrayOf(
            HttpUriParserFingerprint to 0,

            if (is_20_49_or_greater) {
                // Code has moved, and now seems to be an account link
                // and may not be anything to do with sharing links.
                null to -1
            } else if (is_20_37_or_greater) {
                AbUriParserFingerprint to 2
            } else {
                AbUriParserLegacyFingerprint to 2
            }
        ).forEach { (fingerprint, index) ->
            if (fingerprint == null) return@forEach

            fingerprint.method.apply {
                val insertIndex = fingerprint.instructionMatches[index].index
                val uriStringRegister = getInstruction<FiveRegisterInstruction>(insertIndex).registerC

                replaceInstruction(
                    insertIndex,
                    "invoke-static { v$uriStringRegister }, $EXTENSION_CLASS->" +
                            "parseRedirectUri(Ljava/lang/String;)Landroid/net/Uri;",
                )
            }
        }
    }
}
