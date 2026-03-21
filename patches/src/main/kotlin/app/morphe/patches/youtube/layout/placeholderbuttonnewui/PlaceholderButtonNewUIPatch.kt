/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.placeholderbuttonnewui

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.PackageName
import app.morphe.patcher.patch.VersionName
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.util.registersUsed

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/PlaceholderButtonNewUIPatch;"

val COMPATIBILITY_YOUTUBE_TEMP: Pair<PackageName, Set<VersionName>> = Pair(
    "com.google.android.youtube",
    setOf(
        "21.12.522",
    )
)

@Suppress("unused")
internal val placeholderButtonNewUIPatch = bytecodePatch(
    name = "Placeholder button exploder UI",
    description = "Adds a placeholder button to the video player, that match the latest UI style.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_TEMP)

    execute {
        ExploderUIFullscreenButtonFingerprint.match(
            ExploderUIFullscreenButtonParentFingerprint.originalClassDef
        ).let {
            it.method.apply {
                val moveResultObjectIndex = it.instructionMatches.first().index + 2
                val moveResultObjectInstruction = implementation!!.instructions[moveResultObjectIndex]

                addInstruction(
                    moveResultObjectIndex + 1,

                    "invoke-static { v${moveResultObjectInstruction.registersUsed[0]} }, ${EXTENSION_CLASS_DESCRIPTOR}->inject(Landroid/view/View;)V",
                )
            }
        }


    }
}
