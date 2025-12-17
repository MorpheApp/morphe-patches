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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/BypassURLRedirectsPatch;"

val bypassURLRedirectsPatch = bytecodePatch(
    name = "Bypass URL redirects",
    description = "Adds an option to bypass URL redirects and open the original URL directly.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        PreferenceScreen.MISC.addPreferences(
            SwitchPreference("morphe_bypass_url_redirects"),
        )

        arrayOf(
            HttpUriParserFingerprint to 0,

            if (is_20_49_or_greater) {
                // Code has moved, and now seems to be an account url
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
                    "invoke-static { v$uriStringRegister }, $EXTENSION_CLASS_DESCRIPTOR->" +
                            "parseRedirectUri(Ljava/lang/String;)Landroid/net/Uri;",
                )
            }
        }
    }
}
