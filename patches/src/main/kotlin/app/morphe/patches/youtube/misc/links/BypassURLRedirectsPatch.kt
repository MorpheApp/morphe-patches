package app.morphe.patches.youtube.misc.links

import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_37_or_greater
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
        addResourcesPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "19.43.41",
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        addResources("youtube", "misc.links.bypassURLRedirectsPatch")

        PreferenceScreen.MISC.addPreferences(
            SwitchPreference("morphe_bypass_url_redirects"),
        )

        arrayOf(
            if (is_20_37_or_greater) {
                (abUriParserFingerprint to 2)
            } else {
                (abUriParserLegacyFingerprint to 2)
            },
            httpUriParserFingerprint to 0
        ).forEach { (fingerprint, index) ->
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
