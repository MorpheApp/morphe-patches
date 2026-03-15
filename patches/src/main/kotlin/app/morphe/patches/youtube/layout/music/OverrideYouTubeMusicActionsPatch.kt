/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.music

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference.Sorting
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/OverrideYouTubeMusicActionsPatch;"

private fun overrideYouTubeMusicManifestPatch() = resourcePatch{
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        val targetPackage = "app.morphe.android.apps.youtube.music"
        val manifestFile = get("AndroidManifest.xml")
        var manifestContent = manifestFile.readText()

        manifestContent = if (manifestContent.contains("</queries>")) {
            manifestContent.replace(
                "</queries>",
                "<package android:name=\"$targetPackage\"/></queries>"
            )
        } else {
            manifestContent.replace(
                "</manifest>",
                "<queries><package android:name=\"$targetPackage\"/></queries>\n</manifest>"
            )
        }
        manifestFile.writeText(manifestContent)
    }
}

@Suppress("unused")
val overrideYouTubeMusicActionsPatch = bytecodePatch(
    name = "Override YouTube Music actions",
    description = "Overrides the YouTube Music button to open Morphe Music directly.",
) {
    dependsOn(settingsPatch)
    dependsOn(overrideYouTubeMusicManifestPatch())
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            PreferenceCategory(
                titleKey = null,
                sorting = Sorting.UNSORTED,
                tag = "app.morphe.extension.shared.settings.preference.NoTitlePreferenceCategory",
                preferences = setOf(
                    SwitchPreference(key = "morphe_override_youtube_music_button"),
                    TextPreference(key = "morphe_music_package_name")
                )
            )
        )

        classDefForEach { classDef ->
            if (classDef.type == EXTENSION_CLASS_DESCRIPTOR) return@classDefForEach
            var needsPatch = false
            classDef.methods.forEach { method ->
                if (method.implementation?.instructions?.any {
                        it.opcode == Opcode.INVOKE_VIRTUAL &&
                                (it as? ReferenceInstruction)?.reference?.let { ref ->
                                    val mRef = ref as? MethodReference
                                    mRef?.definingClass == "Landroid/content/Intent;" &&
                                            (mRef.name == "setPackage" || mRef.name == "setData")
                                } == true
                    } == true) {
                    needsPatch = true
                }
            }

            if (needsPatch) {
                val mutableClass = mutableClassDefBy(classDef.type)
                classDef.methods.forEach { method ->
                    val instructions = method.implementation?.instructions?.toList() ?: return@forEach
                    val targetIndices = instructions.mapIndexedNotNull { index, instruction ->
                        if (instruction.opcode == Opcode.INVOKE_VIRTUAL) {
                            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                            if (ref?.definingClass == "Landroid/content/Intent;") {
                                if (ref.name == "setPackage" && ref.parameterTypes == listOf("Ljava/lang/String;")) {
                                    index to "overrideSetPackage(Landroid/content/Intent;Ljava/lang/String;)Landroid/content/Intent;"
                                } else if (ref.name == "setData" && ref.parameterTypes == listOf("Landroid/net/Uri;")) {
                                    index to "overrideSetData(Landroid/content/Intent;Landroid/net/Uri;)Landroid/content/Intent;"
                                } else null
                            } else null
                        } else null
                    }

                    if (targetIndices.isNotEmpty()) {
                        val mutableMethod = mutableClass.findMutableMethodOf(method)
                        targetIndices.reversed().forEach { (index, methodDescriptor) ->
                            val instruction = instructions[index]
                            val invokeString = if (instruction is RegisterRangeInstruction) {
                                "invoke-static/range {v${instruction.startRegister} .. v${instruction.startRegister + instruction.registerCount - 1}}"
                            } else {
                                val i = instruction as FiveRegisterInstruction
                                "invoke-static {v${i.registerC}, v${i.registerD}}"
                            }

                            mutableMethod.replaceInstruction(
                                index,
                                "$invokeString, $EXTENSION_CLASS_DESCRIPTOR->$methodDescriptor"
                            )
                        }
                    }
                }
            }
        }
    }
}