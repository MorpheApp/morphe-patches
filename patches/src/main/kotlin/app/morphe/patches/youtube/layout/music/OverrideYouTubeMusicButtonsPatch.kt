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
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.xml.XmlElement
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.fiveRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/OverrideYouTubeMusicButtonsPatch;"

private fun overrideYouTubeMusicManifestPatch() = patch{
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        resourceXml("AndroidManifest.xml").use { manifest ->
            // Only launchable apps are needed here, and QUERY_ALL_PACKAGES additionally exposes
            // OEM media route providers whose route descriptors crash the app when unmarshalled.
            fun XmlElement.declares(tag: String, name: String) = children(tag).any { it.attribute("android:name") == name }

            val queried = manifest.elements("queries").any { queries ->
                queries.children("intent").any { intent ->
                    intent.declares("action", "android.intent.action.MAIN") &&
                            intent.declares("category", "android.intent.category.LAUNCHER")
                }
            }

            if (!queried) {
                val queries = manifest.elements("queries").firstOrNull() ?: run {
                    val application = manifest.elements("application").first()
                    application.parent!!.insertChild(application.index, "queries")
                }
                queries.appendChild("intent") {
                    appendChild("action") { setAttribute("android:name", "android.intent.action.MAIN") }
                    appendChild("category") { setAttribute("android:name", "android.intent.category.LAUNCHER") }
                }
            }
        }
    }
}

val overrideYouTubeMusicButtonsPatch = patch(
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

        // Before Android 11, the deep link is opened only if YouTube Music is installed,
        // otherwise the Play Store page of YouTube Music is opened instead.
        AppDeepLinkEndpointFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[1].index

                replaceInstruction(
                    index,
                    "invoke-static { ${fiveRegisters(index)} }, $EXTENSION_CLASS->" +
                            "getApplicationInfo(Landroid/content/pm/PackageManager;Ljava/lang/String;I)Landroid/content/pm/ApplicationInfo;"
                )
            }
        }

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
