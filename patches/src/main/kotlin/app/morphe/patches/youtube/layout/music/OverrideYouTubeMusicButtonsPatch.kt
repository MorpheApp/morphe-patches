/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.music

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.fiveRegisters
import app.morphe.util.matchAllMethodIndicesForEach
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/OverrideYouTubeMusicButtonsPatch;"

private fun overrideYouTubeMusicManifestPatch() = resourcePatch{
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        val manifestFile = get("AndroidManifest.xml")
        val manifestContent = manifestFile.readText()

        // Only launchable apps are needed here, and QUERY_ALL_PACKAGES additionally exposes
        // OEM media route providers whose route descriptors crash the app when unmarshalled.
        val queryTag = "<intent><action android:name=\"android.intent.action.MAIN\"/>" +
                "<category android:name=\"android.intent.category.LAUNCHER\"/></intent>"

        if (!manifestContent.contains(queryTag)) {
            manifestFile.writeText(
                if (manifestContent.contains("</queries>")) {
                    manifestContent.replace("</queries>", "$queryTag</queries>")
                } else {
                    manifestContent.replace(
                        "<application",
                        "<queries>$queryTag</queries>\n    <application"
                    )
                }
            )
        }
    }
}

@Suppress("unused")
val overrideYouTubeMusicButtonsPatch = bytecodePatch(
    name = "Override YouTube Music buttons",
    description = "Overrides YouTube Music buttons to open Morphe Music or any compatible third-party client.",
) {
    dependsOn(settingsPatch, overrideYouTubeMusicManifestPatch())
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            noTitleUnsortedPreferenceCategory(
                SwitchPreference(key = "morphe_override_youtube_music_buttons", summary = true),
                TextPreference(key = "morphe_custom_music_package_name")
            )
        )

        arrayOf(
            "Landroid/content/Intent;->setPackage(Ljava/lang/String;)Landroid/content/Intent;"
                    to "overrideSetPackage(Landroid/content/Intent;Ljava/lang/String;)Landroid/content/Intent;",
            "Landroid/content/Intent;->setData(Landroid/net/Uri;)Landroid/content/Intent;"
                    to "overrideSetData(Landroid/content/Intent;Landroid/net/Uri;)Landroid/content/Intent;",
            "Landroid/content/Intent;->setComponent(Landroid/content/ComponentName;)Landroid/content/Intent;"
                    to "overrideSetComponent(Landroid/content/Intent;Landroid/content/ComponentName;)Landroid/content/Intent;",
        ).forEach { (smali, methodDescriptor) ->
            Fingerprint(
                filters = listOf(
                    methodCall(opcode = Opcode.INVOKE_VIRTUAL, smali = smali)
                ),
                custom = { _, classDef ->
                    classDef.type != EXTENSION_CLASS
                }
            ).matchAllMethodIndicesForEach { index ->
                val instruction = getInstruction(index)
                val invokeString = if (instruction is RegisterRangeInstruction) {
                    "invoke-static/range { v${instruction.startRegister} .. v${instruction.startRegister + instruction.registerCount - 1} }"
                } else {
                    "invoke-static { ${fiveRegisters(index)} }"
                }

                replaceInstruction(
                    index,
                    "$invokeString, $EXTENSION_CLASS->$methodDescriptor"
                )
            }
        }
    }
}