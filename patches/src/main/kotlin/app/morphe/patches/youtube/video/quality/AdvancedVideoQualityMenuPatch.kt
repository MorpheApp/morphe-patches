/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.quality

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.youtube.misc.recyclerviewtree.addRecyclerViewTreeHook
import app.morphe.patches.youtube.misc.recyclerviewtree.recyclerViewTreeHookPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_FILTER =
    "Lapp/morphe/extension/youtube/patches/components/AdvancedVideoQualityMenuFilter;"

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/playback/quality/AdvancedVideoQualityMenuPatch;"

private const val EXTENSION_SHORTS_QUALITY_MENU_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/playback/quality/AdvancedVideoQualityMenuPatch$ShortsQualityMenuInterface;"

internal val advancedVideoQualityMenuPatch = bytecodePatch {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        lithoFilterPatch,
        recyclerViewTreeHookPatch,
        resourceMappingPatch
    )

    execute {
        settingsMenuVideoQualityGroup.add(
            SwitchPreference("morphe_advanced_video_quality_menu", summary = true)
        )

        // region Patch for the old type of the video quality menu.
        // Used for regular videos when spoofing to old app version,
        // and for the Shorts quality flyout on newer app versions.
        ShowVideoQualityQuickMenuFingerprint.matchAll().forEach {
            it.method.apply {
                val match = it.instructionMatches[2]
                val index = match.index
                val register = findFreeRegister(index)

                addInstructionsWithLabels(
                    index,
                    """
                        invoke-static { }, $EXTENSION_CLASS->showShortsQualityMenu()Z
                        move-result v$register
                        if-eqz v$register, :ignore
                        return-void                        
                        :ignore
                        nop
                    """
                )
            }
        }

        ShortsQualityConstructorFingerprint.let {
            it.classDef.apply {
                interfaces.add(EXTENSION_SHORTS_QUALITY_MENU_INTERFACE)

                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_showShortsQualityMenu",
                        listOf(),
                        "V",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        annotations,
                        null,
                        MutableMethodImplementation(3),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                const/4 v0, 0x1
                                invoke-virtual { p0, v0 }, ${ShortsQualityMenuFingerprint.method}
                                return-void
                            """
                        )
                    }
                )
            }

            it.method.apply {
                val index = it.instructionMatches.first().index

                addInstruction(
                    index,
                    "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->initialize($EXTENSION_SHORTS_QUALITY_MENU_INTERFACE)V"
                )
            }
        }

        // endregion

        // region Patch for the new type of the video quality menu.

        addRecyclerViewTreeHook(EXTENSION_CLASS)

        // Required to check if the video quality menu is currently shown in order to click on the "Advanced" item.
        addLithoFilter(EXTENSION_FILTER)

        // endregion
    }
}
