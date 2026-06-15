package app.morphe.patches.youtube.misc.links

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
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
        Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            parameters = listOf("L", "Ljava/util/Map;"),
            returnType = "V",
            strings = listOf(
                "parentCsn",
                "URL_KEY",
                "navigation_endpoint",
                "WEB_VIEW_BOTTOM_SHEET_TAG",
            ),
            filters = listOf(
                string("android.intent.action.VIEW"),
                methodCall(
                    opcode = Opcode.INVOKE_DIRECT,
                    smali = "Landroid/content/Intent;-><init>(Ljava/lang/String;Landroid/net/Uri;)V",
                    location = InstructionLocation.MatchAfterImmediately(),
                ),
            )
        ).apply {
            val instructionIndex = instructionMatches.last().index
            val instructionRegister = method.getInstruction<BuilderInstruction35c>(instructionIndex).registerE

            method.addInstructions(
                instructionIndex,
                patchLogic("v$instructionRegister"),
            )
        }

        // Override URI for video descriptions and community posts
        Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
            parameters = listOf("Landroid/content/Context;", "Landroid/net/Uri;"),
            returnType = "Z",
            strings = listOf(
                "android.intent.action.VIEW",
                "text/html",
                "Activity not found to view uri",
            ),
        ).method.addInstructions(
            0,
            patchLogic("p1"),
        )
    }
}
