/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2628
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.playlistautoplay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.AccessFlags

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/DisablePlaylistAutoplayPatch;"

@Suppress("unused")
val disablePlaylistAutoplayPatch = bytecodePatch(
    name = "Disable playlist autoplay",
    description = "Adds an option to stop a playlist from automatically advancing to the next video.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        resourceMappingPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_disable_playlist_autoplay", summary = true)
        )

        val enumType = NavigationIntentEnumFingerprint.originalClassDef.type

        val wrapperClassDef = Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
            parameters = listOf(enumType, "L", "L")
        ).originalClassDef
        val wrapperType = wrapperClassDef.type
        val enumField = wrapperClassDef.fields.first { it.type == enumType }

        Fingerprint(
            returnType = "V",
            parameters = listOf(wrapperType),
            custom = { method, classDef ->
                method.implementation != null && classDef.methods.any { sibling ->
                    sibling.implementation != null &&
                            sibling.returnType == "I" &&
                            sibling.parameterTypes.singleOrNull() == wrapperType
                }
            }
        ).matchAll().forEach { match ->
            val method = match.method
            val freeRegister = method.findFreeRegister(0)

            method.addInstructionsWithLabels(
                0,
                """
                    iget-object v$freeRegister, p1, $enumField
                    invoke-static { v$freeRegister }, $EXTENSION_CLASS->shouldSkipPlaylistAutoplay(Ljava/lang/Enum;)Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :continue
                    return-void
                    :continue
                    nop
                """
            )
        }
    }
}
